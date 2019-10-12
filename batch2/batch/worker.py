import abc
import os
import sys
import time
import logging
import asyncio
import random
import json
import aiohttp
import base64
import uuid
import shutil
from aiohttp import web
import concurrent
import aiodocker
from aiodocker.exceptions import DockerError

# import uvloop

from hailtop.config import DeployConfig
from gear import configure_logging

from .utils import parse_cpu, parse_image_tag
from .semaphore import NullWeightedSemaphore, WeightedSemaphore
from .log_store import LogStore
from .google_storage import GCS

# uvloop.install()

configure_logging()
log = logging.getLogger('batch2-agent')

docker = aiodocker.Docker()

MAX_IDLE_TIME_WITH_PODS = 60 * 2  # seconds
MAX_IDLE_TIME_WITHOUT_PODS = 60 * 1  # seconds


class Error:
    def __init__(self, reason, msg):
        self.reason = reason
        self.message = msg

    def to_dict(self):
        return {
            'reason': self.reason,
            'message': self.message
        }

    def __str__(self):
        return f'{self.reason}: {self.message}'


class UnknownVolume(Error):
    def __init__(self, msg):
        super(UnknownVolume, self).__init__('UnknownVolume', msg)


class ImagePullBackOff(Error):
    def __init__(self, msg):
        super(ImagePullBackOff, self).__init__('ImagePullBackOff', msg)


class RunContainerError(Error):
    def __init__(self, msg):
        super(RunContainerError, self).__init__('RunContainerError', msg)


class Container:
    def __init__(self, spec, pod, log_directory):
        self.pod = pod
        self._container = None
        self.name = spec['name']
        self.spec = spec
        self.cores = parse_cpu(spec['resources']['requests']['cpu'])
        self.exit_code = None
        self.id = pod.name + '-' + self.name
        self.error = None
        self.log_directory = log_directory

        tag = parse_image_tag(self.spec['image'])
        if not tag:
            log.info(f'adding latest tag to image {self.spec["image"]} for container {self.id}')
            self.spec['image'] += ':latest'

    async def create(self, volumes):
        log.info(f'creating container {self.id}')

        config = {
            "AttachStdin": False,
            "AttachStdout": False,
            "AttachStderr": False,
            "Tty": False,
            'OpenStdin': False,
            'Cmd': self.spec['command'],
            'Image': self.spec['image'],
            'HostConfig': {'CpuPeriod': 100000,
                           'CpuQuota': int(self.cores * 100000)}
        }

        volume_mounts = []
        errs = []
        for mount in self.spec['volume_mounts']:
            mount_name = mount['name']
            mount_path = mount['mount_path']
            if mount_name in volumes:
                volume_path = volumes[mount_name].path
                if volume_path:
                    volume_mounts.append(f'{volume_path}:{mount_path}')
                else:
                    errs.append(f'unknown secret {mount_name} specified in volume_mounts')
            else:
                errs.append(f'unknown volume {mount_name} specified in volume_mounts')

        if errs:
            self.error = UnknownVolume('\n'.join(errs))
            log.info(f'caught {self.error.reason} error while creating container {self.id}: {self.error.message}')
            return False

        if volume_mounts:
            config['HostConfig']['Binds'] = volume_mounts

        n_tries = 1
        error = None
        image = config['Image']
        while n_tries <= 3:
            try:
                self._container = await docker.containers.create(config, name=self.id)
                self._container = await docker.containers.get(self._container._id)
                return True
            except DockerError as create_error:
                log.info(f'Attempt {n_tries}: caught error while creating container {self.id}: {create_error.message}')
                if create_error.status == 404:
                    try:
                        log.info(f'pulling image {image} for container {self.id}')
                        await docker.pull(image)
                    except DockerError as pull_error:
                        log.info(f'caught error pulling image {image} for container {self.id}: {pull_error.status} {pull_error.message}')
                        error = ImagePullBackOff(msg=pull_error.message)
                else:
                    error = Error(reason='Unknown', msg=create_error.message)

            await asyncio.sleep(1)
            n_tries += 1

        self.error = error
        return False

    async def run(self):
        assert self.error is None
        log.info(f'running container {self.id}')

        n_tries = 1
        error = None

        while n_tries <= 5:
            try:
                await self._container.start()
                await self._container.wait()
                error = None
                break
            except DockerError as err:
                log.info(f'Attempt {n_tries}: caught error while starting container {self.id}: {err.message}, retrying')
                error = RunContainerError(err.message)

            await asyncio.sleep(1)
            n_tries += 1

        if error:
            self.error = error

        self._container = await docker.containers.get(self._container._id)
        self.exit_code = self._container['State']['ExitCode']

        log_path = LogStore.container_log_path(self.log_directory, self.name)
        status_path = LogStore.container_status_path(self.log_directory, self.name)
        log.info(f'writing log for {self.id} to {log_path}')
        log.info(f'writing status for {self.id} to {status_path}')

        log_data = await self.log()
        status_data = json.dumps(self._container._container, indent=4)

        upload_log = self.pod.worker.gcs_client.write_gs_file(log_path, log_data)
        upload_status = self.pod.worker.gcs_client.write_gs_file(status_path, status_data)
        await asyncio.gather(upload_log, upload_status)
        log.info(f'uploaded all logs for container {self.id}')

    async def delete(self):
        if self._container is not None:
            await self._container.stop()
            await self._container.delete()
            self._container = None

    @property
    def status(self):
        if self._container is not None:
            return self._container._container
        return None

    async def log(self):
        logs = await self._container.log(stderr=True, stdout=True)
        return "".join(logs)

    def to_dict(self):
        if self._container is None:
            waiting_reason = self.error.to_dict() if self.error else {}
            return {
                'image': self.spec['image'],
                'imageID': 'unknown',
                'name': self.name,
                'ready': False,
                'restartCount': 0,
                'state': {'waiting': waiting_reason}
            }

        state = {}
        if self.status['State']['Status'] == 'created' and not self.error:
            state['waiting'] = {}
        elif self.status['State']['Status'] == 'running':
            state['running'] = {
                'started_at': self.status['State']['StartedAt']
            }
        elif self.status['State']['Status'] == 'exited' or \
                (self.error and isinstance(self.error, RunContainerError)):  # FIXME: there's other docker states such as dead and oomed
            state['terminated'] = {
                'exitCode': self.status['State']['ExitCode'],
                'startedAt': self.status['State']['StartedAt'],  # This is 0 if RunContainerError, different from k8s
                'finishedAt': self.status['State']['FinishedAt'],  # This is 0 if RunContainerError, different from k8s
                'message': self.status['State']['Error']
            }
        else:
            raise Exception(f'unknown docker state {self.status["State"]}')

        return {
            'containerID': f'docker://{self.status["Id"]}',
            'image': self.spec['image'],
            'imageID': self.status['Image'],
            'name': self.name,
            'ready': False,
            'restartCount': self.status['RestartCount'],
            'state': state
        }


class Volume:
    @staticmethod
    @abc.abstractmethod
    def create(*args):
        return

    @abc.abstractmethod
    def path(self):
        return

    @abc.abstractmethod
    def delete(self):
        return


class Secret(Volume):
    @staticmethod
    async def create(name, file_path, secret_data):
        if secret_data is None:
            return Secret(name, None)

        os.makedirs(file_path)
        for file_name, data in secret_data.items():
            with open(f'{file_path}/{file_name}', 'w') as f:
                f.write(base64.b64decode(data).decode())
        return Secret(name, file_path)

    def __init__(self, name, file_path):
        self.name = name
        self.file_path = file_path

    @property
    def path(self):
        return self.file_path

    async def delete(self):
        shutil.rmtree(self.path, ignore_errors=True)


class EmptyDir(Volume):
    # FIXME add size parameter
    @staticmethod
    async def create(name):
        config = {
            'Name': name
        }
        volume = await docker.volumes.create(config)
        return EmptyDir(name, volume)

    def __init__(self, name, volume):
        self.name = name
        self.volume = volume

    @property
    def path(self):
        return self.name

    async def delete(self):
        await self.volume.delete()


class BatchPod:
    async def _create_volumes(self):
        log.info(f'creating volumes for pod {self.name}')
        volumes = {}
        for volume_spec in self.spec['spec']['volumes']:
            name = volume_spec['name']
            if volume_spec['empty_dir'] is not None:
                volume = await EmptyDir.create(name)
                volumes[name] = volume
            elif volume_spec['secret'] is not None:
                secret_name = volume_spec['secret']['secret_name']
                path = f'{self.scratch}/secrets/{secret_name}'
                secret = await Secret.create(name, path, self.secrets_data.get(secret_name))
                volumes[name] = secret
            else:
                raise Exception(f'Unsupported volume type for {volume_spec}')
        return volumes

    def __init__(self, worker, parameters, cpu_sem):
        self.worker = worker
        self.spec = parameters['spec']
        self.secrets_data = parameters['secrets']
        self.output_directory = parameters['output_directory']

        self.metadata = self.spec['metadata']
        self.name = self.metadata['name']
        self.token = uuid.uuid4().hex
        self.events = []
        self.volumes = {}

        self.containers = {cspec['name']: Container(cspec, self, self.output_directory) for cspec in self.spec['spec']['containers']}
        self.phase = 'Pending'
        self.scratch = f'/batch/pods/{self.name}/{self.token}'

        self._run_task = asyncio.ensure_future(self.run(cpu_sem))

        self.last_updated = None

    async def _create(self):
        log.info(f'creating pod {self.name}')
        self.volumes = await self._create_volumes()
        created = await asyncio.gather(*[container.create(self.volumes) for container in self.containers.values()])  # FIXME: errors not handled properly
        return all(created)

    async def _cleanup(self):
        log.info(f'cleaning up pod {self.name}')
        await asyncio.gather(*[c.delete() for _, c in self.containers.items()])
        await asyncio.gather(*[v.delete() for _, v in self.volumes.items()])
        shutil.rmtree(self.scratch, ignore_errors=True)

    async def _mark_complete(self):
        body = {
            'inst_token': self.worker.token,
            'status': self.to_dict(),
            'events': self.events
        }

        while True:
            try:
                async with aiohttp.ClientSession(
                        raise_for_status=True, timeout=aiohttp.ClientTimeout(total=60)) as session:
                    async with session.post(self.worker.deploy_config.url('batch2-driver', '/api/v1alpha/instances/pod_complete'), json=body) as resp:
                        self.last_updated = time.time()
                        if resp.status == 200:
                            log.info(f'sent pod complete for {self.name}')
                        elif resp.status == 404:
                            log.info(f'sent pod complete for {self.name}, but driver did not recognize pod, ignoring')
                        else:
                            log.info(f'unknown response for {self.name}, {resp!r}')
                        return
            except asyncio.CancelledError:  # pylint: disable=try-except-raise
                raise
            except Exception:  # pylint: disable=broad-except
                log.exception(f'caught exception while marking {self.name} complete, will try again later')
            await asyncio.sleep(15)

    async def run(self, semaphore=None):
        start = time.time()
        create_task = None
        try:
            create_task = asyncio.ensure_future(self._create())
            created = await create_task
            if not created:
                log.info(f'unable to create all containers for {self.name}')
                await self._mark_complete()
                return

            self.phase = 'Running'

            if not semaphore:
                semaphore = NullWeightedSemaphore()

            last_ec = None
            for _, container in self.containers.items():
                async with semaphore(container.cores):
                    log.info(f'running container ({self.name}, {container.name}) with {container.cores} cores')
                    await container.run()
                    last_ec = container.exit_code
                    log.info(f'ran container {container.id} with exit code {container.exit_code} and error {container.error}')
                    if container.error or last_ec != 0:  # Docker sets exit code to 0 by default even if container errors
                        break

            self.phase = 'Succeeded' if last_ec == 0 else 'Failed'

            await self._mark_complete()
            log.info(f'took {time.time() - start} seconds to run pod {self.name}')

        except asyncio.CancelledError:
            log.info(f'pod {self.name} was cancelled')
            if create_task is not None:
                await create_task
            raise

    async def delete(self):
        log.info(f'deleting pod {self.name}')
        self._run_task.cancel()
        try:
            await self._run_task
        finally:
            await self._cleanup()

    async def log(self, container_name):
        c = self.containers[container_name]
        return await c.log()

    def container_status(self, container_name):
        c = self.containers[container_name]
        return c.status

    def to_dict(self):
        return {
            'metadata': self.metadata,
            'status': {
                'containerStatuses': [c.to_dict() for _, c in self.containers.items()],
                'phase': self.phase
            }
        }


class Worker:
    def __init__(self, image, cores, deploy_config, token, ip_address):
        self.image = image
        self.cores = cores
        self.deploy_config = deploy_config
        self.token = token
        self.free_cores = cores
        self.last_updated = time.time()
        self.pods = {}
        self.cpu_sem = WeightedSemaphore(cores)
        self.ip_address = ip_address

        pool = concurrent.futures.ThreadPoolExecutor()

        self.gcs_client = GCS(pool)

    async def _create_pod(self, parameters):
        try:
            bp = BatchPod(self, parameters, self.cpu_sem)
            self.pods[bp.name] = bp
        except DockerError as err:
            log.exception(err)
            raise err
            # return web.Response(body=err.message, status=err.status)
        except Exception as err:
            log.exception(err)
            raise err

    async def create_pod(self, request):
        self.last_updated = time.time()
        parameters = await request.json()
        await asyncio.shield(self._create_pod(parameters))
        return web.Response()

    async def get_container_log(self, request):
        pod_name = request.match_info['pod_name']
        container_name = request.match_info['container_name']

        if pod_name not in self.pods:
            raise web.HTTPNotFound(reason='unknown pod name')
        bp = self.pods[pod_name]

        if container_name not in bp.containers:
            raise web.HTTPNotFound(reason='unknown container name')
        result = await bp.log(container_name)

        return web.json_response(result)

    async def get_container_status(self, request):
        pod_name = request.match_info['pod_name']
        container_name = request.match_info['container_name']

        if pod_name not in self.pods:
            raise web.HTTPNotFound(reason='unknown pod name')
        bp = self.pods[pod_name]

        if container_name not in bp.containers:
            raise web.HTTPNotFound(reason='unknown container name')
        result = bp.container_status(container_name)

        return web.json_response(result)

    async def get_pod(self, request):
        pod_name = request.match_info['pod_name']
        if pod_name not in self.pods:
            raise web.HTTPNotFound(reason='unknown pod name')
        bp = self.pods[pod_name]
        return web.json_response(bp.to_dict())

    async def _delete_pod(self, request):
        pod_name = request.match_info['pod_name']

        if pod_name not in self.pods:
            raise web.HTTPNotFound(reason='unknown pod name')
        bp = self.pods[pod_name]
        del self.pods[pod_name]

        asyncio.ensure_future(bp.delete())

    async def delete_pod(self, request):  # pylint: disable=unused-argument
        await asyncio.shield(self._delete_pod(request))
        return web.Response()

    async def list_pods(self, request):  # pylint: disable=unused-argument
        pods = [pod.to_dict() for _, pod in self.pods.items()]
        return web.json_response(pods)

    async def healthcheck(self, request):  # pylint: disable=unused-argument
        return web.Response()

    async def run(self):
        app_runner = None
        site = None
        try:
            app = web.Application()
            app.add_routes([
                web.post('/api/v1alpha/pods/create', self.create_pod),
                web.get('/api/v1alpha/pods/{pod_name}/containers/{container_name}/log', self.get_container_log),
                web.get('/api/v1alpha/pods/{pod_name}/containers/{container_name}/status', self.get_container_status),
                web.get('/api/v1alpha/pods/{pod_name}', self.get_pod),
                web.post('/api/v1alpha/pods/{pod_name}/delete', self.delete_pod),
                web.get('/api/v1alpha/pods', self.list_pods),
                web.get('/healthcheck', self.healthcheck)
            ])

            app_runner = web.AppRunner(app)
            await app_runner.setup()
            site = web.TCPSite(app_runner, '0.0.0.0', 5000)
            await site.start()

            await self.register()

            last_ping = time.time() - self.last_updated
            while (self.pods and last_ping < MAX_IDLE_TIME_WITH_PODS) \
                    or last_ping < MAX_IDLE_TIME_WITHOUT_PODS:
                log.info(f'n_pods {len(self.pods)} free_cores {self.free_cores} age {last_ping}')
                await asyncio.sleep(15)
                last_ping = time.time() - self.last_updated

            if self.pods:
                log.info(f'idle {MAX_IDLE_TIME_WITH_PODS} seconds with pods, exiting')
            else:
                log.info(f'idle {MAX_IDLE_TIME_WITHOUT_PODS} seconds with no pods, exiting')

            try:
                body = {'inst_token': self.token}
                async with aiohttp.ClientSession(
                        raise_for_status=True, timeout=aiohttp.ClientTimeout(total=60)) as session:
                    url = self.deploy_config.url('batch2-driver', '/api/v1alpha/instances/deactivate')
                    async with session.post(url, json=body):
                        log.info('deactivated')
            except asyncio.CancelledError:  # pylint: disable=try-except-raise
                raise
            except Exception:  # pylint: disable=broad-except
                log.exception('caught exception while deactivating')
        finally:
            log.info('shutting down')
            if site:
                await site.stop()
                log.info('stopped site')
            if app_runner:
                await app_runner.cleanup()
                log.info('cleaned up app runner')

    async def register(self):
        tries = 0
        while True:
            try:
                log.info('registering')
                body = {'inst_token': self.token,
                        'ip_address': self.ip_address}
                async with aiohttp.ClientSession(
                        raise_for_status=True, timeout=aiohttp.ClientTimeout(total=60)) as session:
                    url = self.deploy_config.url('batch2-driver', '/api/v1alpha/instances/activate')
                    async with session.post(url, json=body) as resp:
                        if resp.status == 200:
                            self.last_updated = time.time()
                            log.info('registered')
                            return
            except asyncio.CancelledError:  # pylint: disable=try-except-raise
                raise
            except Exception as e:  # pylint: disable=broad-except
                log.exception('caught exception while registering')
                if tries == 12:
                    log.info('register: giving up')
                    raise e
                tries += 1
            await asyncio.sleep(5 * random.uniform(1, 1.25))


cores = int(os.environ['CORES'])
namespace = os.environ['NAMESPACE']
inst_token = os.environ['INST_TOKEN']
ip_address = os.environ['INTERNAL_IP']
batch_worker_image = os.environ['BATCH_WORKER_IMAGE']

log.info(f'BATCH_WORKER_IMAGE={batch_worker_image}')

deploy_config = DeployConfig('gce', namespace, {})
worker = Worker(batch_worker_image, cores, deploy_config, inst_token, ip_address)

loop = asyncio.get_event_loop()
loop.run_until_complete(worker.run())
loop.run_until_complete(docker.close())
loop.run_until_complete(loop.shutdown_asyncgens())
loop.close()
log.info(f'closed')
sys.exit(0)
