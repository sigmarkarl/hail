import os
import sys
import json
from shlex import quote as shq
import logging
import asyncio
import random
import traceback
import base64
import uuid
import shutil
import aiohttp
from aiohttp import web
import concurrent
import aiodocker
from aiodocker.exceptions import DockerError
import google.oauth2.service_account
from hailtop.utils import time_msecs, request_retry_transient_errors

# import uvloop

from hailtop.config import DeployConfig
from gear import configure_logging

from .utils import parse_cpu_in_mcpu, parse_image_tag, parse_memory_in_bytes, \
    adjust_cores_for_memory_request, cores_mcpu_to_memory_bytes
from .semaphore import WeightedSemaphore, NullWeightedSemaphore
from .log_store import LogStore

# uvloop.install()

configure_logging()
log = logging.getLogger('batch-worker')

MAX_IDLE_TIME_MSECS = 5 * 60 * 1000

CORES = int(os.environ['CORES'])
NAME = os.environ['NAME']
NAMESPACE = os.environ['NAMESPACE']
# ACTIVATION_TOKEN
IP_ADDRESS = os.environ['IP_ADDRESS']
BUCKET_NAME = os.environ['BUCKET_NAME']
INSTANCE_ID = os.environ['INSTANCE_ID']
PROJECT = os.environ['PROJECT']
WORKER_TYPE = os.environ['WORKER_TYPE']

log.info(f'CORES {CORES}')
log.info(f'NAME {NAME}')
log.info(f'NAMESPACE {NAMESPACE}')
# ACTIVATION_TOKEN
log.info(f'IP_ADDRESS {IP_ADDRESS}')
log.info(f'WORKER_TYPE {WORKER_TYPE}')
log.info(f'BUCKET_NAME {BUCKET_NAME}')
log.info(f'INSTANCE_ID {INSTANCE_ID}')
log.info(f'PROJECT {PROJECT}')

deploy_config = DeployConfig('gce', NAMESPACE, {})

docker = aiodocker.Docker()


async def docker_call_retry(f, *args, **kwargs):
    delay = 0.1
    while True:
        try:
            return await f(*args, **kwargs)
        except DockerError as e:
            # 408 request timeout, 503 service unavailable
            if e.status == 408 or e.status == 503:
                log.exception('in docker call, retrying')
            else:
                raise
        except asyncio.TimeoutError:
            log.exception('in docker call, retrying')
        # exponentially back off, up to (expected) max of 30s
        t = delay * random.random()
        await asyncio.sleep(t)
        delay = min(delay * 2, 60.0)


class JobDeletedError(Exception):
    pass


class ContainerStepManager:
    def __init__(self, container, name, state):
        self.container = container
        self.state = state
        self.name = name
        self.timing = None

    async def __aenter__(self):
        if self.container.job.deleted:
            raise JobDeletedError()
        if self.state:
            log.info(f'{self.container} state changed: {self.container.state} => {self.state}')
            self.container.state = self.state
        self.timing = {}
        self.timing['start_time'] = time_msecs()
        self.container.timing[self.name] = self.timing

    async def __aexit__(self, exc_type, exc, tb):
        finish_time = time_msecs()
        self.timing['finish_time'] = finish_time
        start_time = self.timing['start_time']
        self.timing['duration'] = finish_time - start_time


class Container:
    def __init__(self, job, name, spec):
        self.job = job
        self.name = name
        self.spec = spec

        image = spec['image']
        tag = parse_image_tag(self.spec['image'])
        if not tag:
            log.info(f'adding latest tag to image {self.spec["image"]} for {self}')
            image += ':latest'
        self.image = image

        req_cpu_in_mcpu = parse_cpu_in_mcpu(spec['cpu'])
        req_memory_in_bytes = parse_memory_in_bytes(spec['memory'])

        self.cpu_in_mcpu = adjust_cores_for_memory_request(req_cpu_in_mcpu, req_memory_in_bytes, WORKER_TYPE)
        self.memory_in_bytes = cores_mcpu_to_memory_bytes(self.cpu_in_mcpu, WORKER_TYPE)

        self.container = None
        self.state = 'pending'
        self.error = None
        self.timing = {}
        self.container_status = None
        self.log = None

    def container_config(self):
        config = {
            "AttachStdin": False,
            "AttachStdout": False,
            "AttachStderr": False,
            "Tty": False,
            'OpenStdin': False,
            'Cmd': self.spec['command'],
            'Image': self.image,
            'HostConfig': {'CpuPeriod': 100000,
                           'CpuQuota': self.cpu_in_mcpu * 100,
                           'Memory': self.memory_in_bytes}
        }

        env = self.spec.get('env')
        if env:
            config['Env'] = env

        volume_mounts = self.spec.get('volume_mounts')
        if volume_mounts:
            config['HostConfig']['Binds'] = volume_mounts

        return config

    def step(self, name, **kwargs):
        state = kwargs.get('state', name)
        return ContainerStepManager(self, name, state)

    async def get_container_status(self):
        if not self.container:
            return None

        try:
            c = await docker_call_retry(self.container.show)
        except DockerError as e:
            if e.status == 404:
                return None
            raise

        log.info(f'{self} container info {c}')
        cstate = c['State']
        status = {
            'state': cstate['Status'],
            'started_at': cstate['StartedAt'],
            'finished_at': cstate['FinishedAt'],
            'out_of_memory': cstate['OOMKilled']
        }
        cerror = cstate['Error']
        if cerror:
            status['error'] = cerror
        else:
            status['exit_code'] = cstate['ExitCode']

        return status

    async def run(self, worker, cpu_sem):
        try:
            async with self.step('pulling'):
                if self.image.startswith('gcr.io/'):
                    key = base64.b64decode(
                        self.job.gsa_key['privateKeyData']).decode()
                    auth = {
                        'username': '_json_key',
                        'password': key
                    }
                    # Pull to verify this user has access to this
                    # image.
                    # FIXME improve the performance of this with a
                    # per-user image cache.
                    await docker_call_retry(
                        docker.images.pull, self.image, auth=auth)
                else:
                    # this caches public images
                    try:
                        await docker_call_retry(docker.images.get, self.image)
                    except DockerError as e:
                        if e.status == 404:
                            await docker_call_retry(docker.images.pull, self.image)

            async with self.step('creating'):
                config = self.container_config()
                log.info(f'starting {self} config {config}')
                self.container = await docker_call_retry(
                    docker.containers.create,
                    config, name=f'batch-{self.job.batch_id}-job-{self.job.job_id}-{self.name}')

            async with cpu_sem(self.cpu_in_mcpu):
                async with self.step('runtime', state=None):
                    if self.name == 'main':
                        asyncio.ensure_future(worker.post_job_started(self.job))

                    async with self.step('starting'):
                        await docker_call_retry(self.container.start)

                    async with self.step('running'):
                        await docker_call_retry(self.container.wait)

            self.container_status = await self.get_container_status()
            log.info(f'{self}: container status {self.container_status}')

            async with self.step('uploading_log'):
                await worker.log_store.write_log_file(
                    self.job.batch_id, self.job.job_id, self.name,
                    await self.get_container_log())

            async with self.step('deleting'):
                await self.delete_container()

            if 'error' in self.container_status:
                self.state = 'error'
            elif self.container_status['exit_code'] == 0:
                self.state = 'succeeded'
            else:
                self.state = 'failed'
        except Exception:
            log.exception(f'while running {self}')

            self.state = 'error'
            self.error = traceback.format_exc()
        finally:
            await self.delete_container()

    async def get_container_log(self):
        logs = await docker_call_retry(self.container.log, stderr=True, stdout=True)
        return "".join(logs)

    async def get_log(self):
        if self.log:
            return self.log

        if self.container:
            return await self.get_container_log()

        return None

    async def delete_container(self):
        if self.container:
            try:
                log.info(f'{self}: deleting container')
                await docker_call_retry(self.container.stop)
                # v=True deletes anonymous volumes created by the container
                await docker_call_retry(self.container.delete, v=True)
                self.container = None
            except Exception:
                log.exception('while deleting up container, ignoring')

    async def delete(self):
        log.info(f'deleting {self}')
        await self.delete_container()

    # {
    #   name: str,
    #   state: str, (pending, pulling, creating, starting, running, uploading_log, deleting, suceeded, error, failed)
    #   timing: dict(str, float),
    #   error: str, (optional)
    #   container_status: { (from docker container state)
    #     state: str,
    #     started_at: str, (date)
    #     finished_at: str, (date)
    #     out_of_memory: boolean
    #     error: str, (one of error, exit_code will be present)
    #     exit_code: int
    #   }
    # }
    async def status(self, state=None):
        if not state:
            state = self.state
        status = {
            'name': self.name,
            'state': state,
            'timing': self.timing
        }
        if self.error:
            status['error'] = self.error
        if self.container_status:
            status['container_status'] = self.container_status
        elif self.container:
            status['container_status'] = await self.get_container_status()
        return status

    def __str__(self):
        return f'container {self.job.id}/{self.name}'


def populate_secret_host_path(host_path, secret_data):
    os.makedirs(host_path)
    if secret_data is not None:
        for filename, data in secret_data.items():
            with open(f'{host_path}/{filename}', 'w') as f:
                f.write(base64.b64decode(data).decode())


def copy_command(src, dst):
    if not dst.startswith('gs://'):
        mkdirs = f'mkdir -p {shq(os.path.dirname(dst))} && '
    else:
        mkdirs = ''
    return f'{mkdirs}retry gsutil -m cp -R {shq(src)} {shq(dst)}'


def copy(files):
    assert files

    copies = ' && '.join([copy_command(f['from'], f['to']) for f in files])
    return f'''
set -ex

function retry() {{
    "$@" ||
        (sleep 2 && "$@") ||
        (sleep 5 && "$@")
}}

retry gcloud -q auth activate-service-account --key-file=/gsa-key/privateKeyData

{copies}
'''


def copy_container(job, name, files, volume_mounts):
    sh_expression = copy(files)
    copy_spec = {
        'image': 'google/cloud-sdk:269.0.0-alpine',
        'name': name,
        'command': ['/bin/sh', '-c', sh_expression],
        'cpu': '500m' if files else '100m',
        'memory': '0.5Gi',
        'volume_mounts': volume_mounts
    }
    return Container(job, name, copy_spec)


class Job:
    def secret_host_path(self, secret):
        return f'{self.scratch}/secrets/{secret["name"]}'

    def io_host_path(self):
        return f'{self.scratch}/io'

    def __init__(self, batch_id, user, gsa_key, job_spec):
        self.batch_id = batch_id
        self.user = user
        self.gsa_key = gsa_key
        self.job_spec = job_spec

        self.deleted = False

        token = uuid.uuid4().hex
        self.scratch = f'/batch/{token}'

        self.state = 'pending'
        self.error = None

        pvc_size = job_spec.get('pvc_size')
        input_files = job_spec.get('input_files')
        output_files = job_spec.get('output_files')

        copy_volume_mounts = []
        main_volume_mounts = []

        if job_spec.get('mount_docker_socket'):
            main_volume_mounts.append('/var/run/docker.sock:/var/run/docker.sock')

        if pvc_size or input_files or output_files:
            self.mount_io = True
            volume_mount = f'{self.io_host_path()}:/io'
            main_volume_mounts.append(volume_mount)
            copy_volume_mounts.append(volume_mount)
        else:
            self.mount_io = False

        secrets = job_spec.get('secrets')
        self.secrets = secrets
        if secrets:
            for secret in secrets:
                volume_mount = f'{self.secret_host_path(secret)}:{secret["mount_path"]}'
                main_volume_mounts.append(volume_mount)
                # this will be the user gsa-key
                if secret.get('mount_in_copy', False):
                    copy_volume_mounts.append(volume_mount)

        env = []
        for item in job_spec.get('env', []):
            env.append(f'{item["name"]}={item["value"]}')

        # create containers
        containers = {}

        if input_files:
            containers['input'] = copy_container(
                self, 'input', input_files, copy_volume_mounts)

        # main container
        main_spec = {
            'command': job_spec['command'],
            'image': job_spec['image'],
            'name': 'main',
            'env': env,
            'cpu': job_spec['resources']['cpu'],
            'memory': job_spec['resources']['memory'],
            'volume_mounts': main_volume_mounts
        }
        containers['main'] = Container(self, 'main', main_spec)

        if output_files:
            containers['output'] = copy_container(
                self, 'output', output_files, copy_volume_mounts)

        self.containers = containers

    @property
    def job_id(self):
        return self.job_spec['job_id']

    @property
    def attempt_id(self):
        return self.job_spec['attempt_id']

    @property
    def id(self):
        return (self.batch_id, self.job_id)

    async def run(self, worker):
        run_start_time = time_msecs()

        try:
            log.info(f'{self}: initializing')
            self.state = 'initializing'

            if self.mount_io:
                os.makedirs(self.io_host_path())

            if self.secrets:
                for secret in self.secrets:
                    populate_secret_host_path(self.secret_host_path(secret), secret['data'])

            self.state = 'running'

            input = self.containers.get('input')
            if input:
                log.info(f'{self}: running input')
                await input.run(worker, worker.cpu_null_sem)
                log.info(f'{self} input: {input.state}')

            if not input or input.state == 'succeeded':
                log.info(f'{self}: running main')

                main = self.containers['main']
                await main.run(worker, worker.cpu_sem)

                log.info(f'{self} main: {main.state}')

                output = self.containers.get('output')
                if output:
                    log.info(f'{self}: running output')
                    await output.run(worker, worker.cpu_null_sem)
                    log.info(f'{self} output: {output.state}')

                if main.state != 'succeeded':
                    self.state = main.state
                elif output:
                    self.state = output.state
                else:
                    self.state = 'succeeded'
            else:
                self.state = input.state
        except Exception:
            log.exception(f'while running {self}')

            self.state = 'error'
            self.error = traceback.format_exc()
        finally:
            run_end_time = time_msecs()
            run_duration = run_end_time - run_start_time

            log.info(f'{self}: marking complete')
            asyncio.ensure_future(worker.post_job_complete(self, run_duration))

            log.info(f'{self}: cleaning up')
            try:
                shutil.rmtree(self.scratch, ignore_errors=True)
            except Exception:
                log.exception('while deleting volumes')

    async def get_log(self):
        return {name: await c.get_log() for name, c in self.containers.items()}

    async def delete(self):
        log.info(f'deleting {self}')
        self.deleted = True
        for _, c in self.containers.items():
            await c.delete()

    # {
    #   name: str,
    #   batch_id: int,
    #   job_id: int,
    #   attempt_id: int,
    #   user: str,
    #   state: str, (pending, initializing, running, succeeded, error, failed)
    #   error: str, (optional)
    #   container_statuses: [Container.status],
    #   start_time: int,
    #   end_time: int
    # }
    async def status(self):
        status = {
            'worker': NAME,
            'batch_id': self.batch_id,
            'job_id': self.job_spec['job_id'],
            'attempt_id': self.job_spec['attempt_id'],
            'user': self.user,
            'state': self.state
        }
        if self.error:
            status['error'] = self.error

        cstatuses = {
            name: await c.status() for name, c in self.containers.items()
        }
        status['container_statuses'] = cstatuses

        main_timings = cstatuses['main']['timing']
        if 'runtime' in main_timings:
            status['start_time'] = main_timings['runtime'].get('start_time')
            status['end_time'] = main_timings['runtime'].get('finish_time')
        else:
            status['start_time'] = None
            status['end_time'] = None

        return status

    def __str__(self):
        return f'job {self.id}'


class Worker:
    def __init__(self):
        self.cores_mcpu = CORES * 1000
        self.free_cores_mcpu = self.cores_mcpu
        self.last_updated = time_msecs()
        self.cpu_sem = WeightedSemaphore(self.cores_mcpu)
        self.cpu_null_sem = NullWeightedSemaphore()
        self.pool = concurrent.futures.ThreadPoolExecutor()
        self.jobs = {}

        # filled in during activation
        self.log_store = None
        self.headers = None

    async def run_job(self, job):
        try:
            await job.run(self)
        except Exception:
            log.exception(f'while running {job}, ignoring')

    async def create_job_1(self, request):
        body = await request.json()

        batch_id = body['batch_id']
        job_spec = body['job_spec']
        job_id = job_spec['job_id']
        id = (batch_id, job_id)

        # already running
        if id in self.jobs:
            return web.Response()

        job = Job(batch_id, body['user'], body['gsa_key'], job_spec)

        log.info(f'created {job}, adding to jobs')

        self.jobs[job.id] = job

        asyncio.ensure_future(self.run_job(job))

        return web.Response()

    async def create_job(self, request):
        return await asyncio.shield(self.create_job_1(request))

    async def get_job_log(self, request):
        batch_id = int(request.match_info['batch_id'])
        job_id = int(request.match_info['job_id'])
        id = (batch_id, job_id)
        job = self.jobs.get(id)
        if not job:
            raise web.HTTPNotFound()
        return web.json_response(await job.get_log())

    async def get_job_status(self, request):
        batch_id = int(request.match_info['batch_id'])
        job_id = int(request.match_info['job_id'])
        id = (batch_id, job_id)
        job = self.jobs.get(id)
        if not job:
            raise web.HTTPNotFound()
        return web.json_response(await job.status())

    async def delete_job_1(self, request):
        batch_id = int(request.match_info['batch_id'])
        job_id = int(request.match_info['job_id'])
        id = (batch_id, job_id)

        log.info(f'deleting job {id}, removing from jobs')

        job = self.jobs.pop(id, None)
        if job is None:
            raise web.HTTPNotFound()

        asyncio.ensure_future(job.delete())

        return web.Response()

    async def delete_job(self, request):
        return await asyncio.shield(self.delete_job_1(request))

    async def healthcheck(self, request):  # pylint: disable=unused-argument
        return web.Response()

    async def run(self):
        app_runner = None
        site = None
        try:
            app = web.Application()
            app.add_routes([
                web.post('/api/v1alpha/batches/jobs/create', self.create_job),
                web.delete('/api/v1alpha/batches/{batch_id}/jobs/{job_id}/delete', self.delete_job),
                web.get('/api/v1alpha/batches/{batch_id}/jobs/{job_id}/log', self.get_job_log),
                web.get('/api/v1alpha/batches/{batch_id}/jobs/{job_id}/status', self.get_job_status),
                web.get('/healthcheck', self.healthcheck)
            ])

            app_runner = web.AppRunner(app)
            await app_runner.setup()
            site = web.TCPSite(app_runner, '0.0.0.0', 5000)
            await site.start()

            await self.activate()

            idle_duration = time_msecs() - self.last_updated
            while self.jobs or idle_duration < MAX_IDLE_TIME_MSECS:
                log.info(f'n_jobs {len(self.jobs)} free_cores {self.free_cores_mcpu / 1000} idle {idle_duration}')
                await asyncio.sleep(15)
                idle_duration = time_msecs() - self.last_updated

            log.info(f'idle {idle_duration} seconds, exiting')

            async with aiohttp.ClientSession(
                    raise_for_status=True, timeout=aiohttp.ClientTimeout(total=60)) as session:
                # Don't retry.  If it doesn't go through, the driver
                # monitoring loops will recover.  If the driver is
                # gone (e.g. testing a PR), this would go into an
                # infinite loop and the instance won't be deleted.
                await session.post(
                    deploy_config.url('batch-driver', '/api/v1alpha/instances/deactivate'),
                    headers=self.headers)
            log.info('deactivated')
        finally:
            log.info('shutting down')
            if site:
                await site.stop()
                log.info('stopped site')
            if app_runner:
                await app_runner.cleanup()
                log.info('cleaned up app runner')

    async def post_job_complete_1(self, job, run_duration):
        body = {
            'status': await job.status()
        }

        start_time = time_msecs()
        delay_secs = 0.1
        while True:
            try:
                async with aiohttp.ClientSession(
                        raise_for_status=True, timeout=aiohttp.ClientTimeout(total=60)) as session:
                    await session.post(
                        deploy_config.url('batch-driver', '/api/v1alpha/instances/job_complete'),
                        json=body, headers=self.headers)
                    return
            except asyncio.CancelledError:  # pylint: disable=try-except-raise
                raise
            except Exception as e:
                if isinstance(e, aiohttp.ClientResponseError) and e.status == 404:   # pylint: disable=no-member
                    raise
                log.exception(f'failed to mark {job} complete, retrying')

            # unlist job after 3m or half the run duration
            now = time_msecs()
            elapsed = now - start_time
            if (job.id in self.jobs and
                    elapsed > 180 * 1000 and
                    elapsed > run_duration / 2):
                log.info(f'too much time elapsed marking {job} complete, removing from jobs, will keep retrying')
                del self.jobs[job.id]
                self.last_updated = time_msecs()

            # exponentially back off, up to (expected) max of 30s
            await asyncio.sleep(
                delay_secs * random.uniform(0.7, 1.3))
            delay_secs = min(delay_secs * 2, 30.0)

    async def post_job_complete(self, job, run_duration):
        try:
            await self.post_job_complete_1(job, run_duration)
        finally:
            log.info(f'{job} marked complete, removing from jobs')
            if job.id in self.jobs:
                del self.jobs[job.id]
                self.last_updated = time_msecs()

    async def post_job_started(self, job):
        body = {
            'status': await job.status()
        }

        async with aiohttp.ClientSession(
                raise_for_status=True, timeout=aiohttp.ClientTimeout(total=60)) as session:
            await request_retry_transient_errors(
                session, 'POST',
                deploy_config.url('batch-driver', '/api/v1alpha/instances/job_started'),
                json=body, headers=self.headers)

    async def activate(self):
        async with aiohttp.ClientSession(
                raise_for_status=True, timeout=aiohttp.ClientTimeout(total=60)) as session:
            resp = await request_retry_transient_errors(
                session, 'POST',
                deploy_config.url('batch-driver', '/api/v1alpha/instances/activate'),
                json={'ip_address': os.environ['IP_ADDRESS']},
                headers={
                    'X-Hail-Instance-Name': NAME,
                    'Authorization': f'Bearer {os.environ["ACTIVATION_TOKEN"]}'
                })
            resp_json = await resp.json()
            self.headers = {
                'X-Hail-Instance-Name': NAME,
                'Authorization': f'Bearer {resp_json["token"]}'
            }

            with open('key.json', 'w') as f:
                f.write(json.dumps(resp_json['key']))

            credentials = google.oauth2.service_account.Credentials.from_service_account_file(
                'key.json')
            self.log_store = LogStore(BUCKET_NAME, INSTANCE_ID, self.pool,
                                      project=PROJECT, credentials=credentials)


worker = Worker()

loop = asyncio.get_event_loop()
loop.run_until_complete(worker.run())
loop.run_until_complete(docker.close())
loop.run_until_complete(loop.shutdown_asyncgens())
loop.close()
log.info(f'closed')
sys.exit(0)
