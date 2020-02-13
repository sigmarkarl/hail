import secrets
import logging
import json
from functools import wraps
import concurrent
import asyncio
from aiohttp import web
import aiohttp_session
import kubernetes_asyncio as kube
import google.oauth2.service_account
from prometheus_async.aio.web import server_stats
from gear import Database, setup_aiohttp_session, web_authenticated_developers_only, \
    check_csrf_token, transaction
from hailtop.config import get_deploy_config
from hailtop.utils import time_msecs
from web_common import setup_aiohttp_jinja2, setup_common_static_routes, render_template, \
    set_message
import googlecloudprofiler
import uvloop

from ..batch import mark_job_complete, mark_job_started
from ..log_store import LogStore
from ..batch_configuration import REFRESH_INTERVAL_IN_SECONDS, \
    DEFAULT_NAMESPACE, BATCH_BUCKET_NAME, HAIL_SHA, HAIL_SHOULD_PROFILE
from ..google_compute import GServices
from ..globals import HTTP_CLIENT_MAX_SIZE

from .instance_pool import InstancePool
from .scheduler import Scheduler
from .k8s_cache import K8sCache

uvloop.install()

log = logging.getLogger('batch')

log.info(f'REFRESH_INTERVAL_IN_SECONDS {REFRESH_INTERVAL_IN_SECONDS}')

routes = web.RouteTableDef()

deploy_config = get_deploy_config()


def authorization_token(request):
    auth_header = request.headers.get('Authorization')
    if not auth_header:
        return None
    if not auth_header.startswith('Bearer '):
        return None
    return auth_header[7:]


def batch_only(fun):
    @wraps(fun)
    async def wrapped(request):
        token = authorization_token(request)
        if not token:
            raise web.HTTPUnauthorized()

        if not secrets.compare_digest(token, request.app['internal_token']):
            raise web.HTTPUnauthorized()

        return await fun(request)
    return wrapped


def instance_from_request(request):
    instance_name = request.headers.get('X-Hail-Instance-Name')
    if not instance_name:
        return None

    instance_pool = request.app['inst_pool']
    return instance_pool.name_instance.get(instance_name)


def activating_instances_only(fun):
    @wraps(fun)
    async def wrapped(request):
        instance = instance_from_request(request)
        if not instance:
            log.info('instance not found')
            raise web.HTTPUnauthorized()

        if instance.state != 'pending':
            log.info('instance not pending')
            raise web.HTTPUnauthorized()

        activation_token = authorization_token(request)
        if not activation_token:
            log.info('activation token not found')
            raise web.HTTPUnauthorized()

        db = request.app['db']
        record = await db.select_and_fetchone(
            'SELECT state FROM instances WHERE name = %s AND activation_token = %s;',
            (instance.name, activation_token))
        if not record:
            log.info('instance, activation token not found in database')
            raise web.HTTPUnauthorized()

        resp = await fun(request, instance)

        return resp
    return wrapped


def active_instances_only(fun):
    @wraps(fun)
    async def wrapped(request):
        instance = instance_from_request(request)
        if not instance:
            log.info('instance not found')
            raise web.HTTPUnauthorized()

        if instance.state != 'active':
            log.info('instance not active')
            raise web.HTTPUnauthorized()

        token = authorization_token(request)
        if not token:
            log.info('token not found')
            raise web.HTTPUnauthorized()

        db = request.app['db']
        record = await db.select_and_fetchone(
            'SELECT state FROM instances WHERE name = %s AND token = %s;',
            (instance.name, token))
        if not record:
            log.info('instance, token not found in database')
            raise web.HTTPUnauthorized()

        await instance.mark_healthy()

        return await fun(request, instance)
    return wrapped


@routes.get('/healthcheck')
async def get_healthcheck(request):  # pylint: disable=W0613
    return web.Response()


@routes.patch('/api/v1alpha/batches/{user}/{batch_id}/close')
@batch_only
async def close_batch(request):
    db = request.app['db']

    user = request.match_info['user']
    batch_id = int(request.match_info['batch_id'])

    record = db.select_and_fetchone(
        '''
SELECT state FROM batches WHERE user = %s AND id = %s;
''',
        (user, batch_id))
    if not record:
        raise web.HTTPNotFound()

    request.app['scheduler_state_changed'].set()

    return web.Response()


@routes.post('/api/v1alpha/batches/cancel')
@batch_only
async def cancel_batch(request):
    request.app['cancel_running_state_changed'].set()
    request.app['cancel_ready_state_changed'].set()
    return web.Response()


@routes.post('/api/v1alpha/batches/delete')
@batch_only
async def delete_batch(request):
    request.app['cancel_running_state_changed'].set()
    request.app['cancel_ready_state_changed'].set()
    return web.Response()


async def activate_instance_1(request, instance):
    body = await request.json()
    ip_address = body['ip_address']

    log.info(f'activating {instance}')
    timestamp = time_msecs()
    token = await instance.activate(ip_address, timestamp)
    await instance.mark_healthy()

    with open('/gsa-key/key.json', 'r') as f:
        key = json.loads(f.read())
    return web.json_response({
        'token': token,
        'key': key
    })


@routes.post('/api/v1alpha/instances/activate')
@activating_instances_only
async def activate_instance(request, instance):
    return await asyncio.shield(activate_instance_1(request, instance))


async def deactivate_instance_1(instance):
    log.info(f'deactivating {instance}')
    await instance.deactivate('deactivated')
    await instance.mark_healthy()
    return web.Response()


@routes.post('/api/v1alpha/instances/deactivate')
@active_instances_only
async def deactivate_instance(request, instance):  # pylint: disable=unused-argument
    return await asyncio.shield(deactivate_instance_1(instance))


async def job_complete_1(request, instance):
    body = await request.json()
    job_status = body['status']

    batch_id = job_status['batch_id']
    job_id = job_status['job_id']
    attempt_id = job_status['attempt_id']

    state = job_status['state']
    if state == 'succeeded':
        new_state = 'Success'
    elif state == 'error':
        new_state = 'Error'
    else:
        assert state == 'failed', state
        new_state = 'Failed'

    start_time = job_status['start_time']
    end_time = job_status['end_time']
    status = job_status['status']

    await mark_job_complete(request.app, batch_id, job_id, attempt_id, instance.name,
                            new_state, status, start_time, end_time, 'completed')

    await instance.mark_healthy()

    return web.Response()


@routes.post('/api/v1alpha/instances/job_complete')
@active_instances_only
async def job_complete(request, instance):
    return await asyncio.shield(job_complete_1(request, instance))


async def job_started_1(request, instance):
    body = await request.json()
    job_status = body['status']

    batch_id = job_status['batch_id']
    job_id = job_status['job_id']
    attempt_id = job_status['attempt_id']
    start_time = job_status['start_time']

    await mark_job_started(request.app, batch_id, job_id, attempt_id, instance, start_time)

    await instance.mark_healthy()

    return web.Response()


@routes.post('/api/v1alpha/instances/job_started')
@active_instances_only
async def job_started(request, instance):
    return await asyncio.shield(job_started_1(request, instance))


@routes.get('/')
@routes.get('')
@web_authenticated_developers_only()
async def get_index(request, userdata):
    app = request.app
    db = app['db']
    instance_pool = app['inst_pool']

    ready_cores = await db.select_and_fetchone(
        '''
SELECT CAST(COALESCE(SUM(ready_cores_mcpu), 0) AS SIGNED) AS ready_cores_mcpu
FROM ready_cores;
''')
    ready_cores_mcpu = ready_cores['ready_cores_mcpu']

    page_context = {
        'config': instance_pool.config(),
        'instance_id': app['instance_id'],
        'n_instances_by_state': instance_pool.n_instances_by_state,
        'instances': instance_pool.name_instance.values(),
        'ready_cores_mcpu': ready_cores_mcpu,
        'live_free_cores_mcpu': instance_pool.live_free_cores_mcpu
    }
    return await render_template('batch-driver', request, userdata, 'index.html', page_context)


@routes.post('/config-update')
@check_csrf_token
@web_authenticated_developers_only()
async def config_update(request, userdata):  # pylint: disable=unused-argument
    app = request.app
    inst_pool = app['inst_pool']

    session = await aiohttp_session.get_session(request)

    def validate(name, value, predicate, description):
        if not predicate(value):
            set_message(session,
                        f'{name} invalid: {value}.  Must be {description}.',
                        'error')
            raise web.HTTPFound(deploy_config.external_url('batch-driver', '/'))
        return value

    def validate_int(name, value, predicate, description):
        try:
            i = int(value)
        except ValueError:
            set_message(session,
                        f'{name} invalid: {value}.  Must be an integer.',
                        'error')
            raise web.HTTPFound(deploy_config.external_url('batch-driver', '/'))
        return validate(name, i, predicate, description)

    post = await request.post()

    # FIXME can't adjust worker type, cores because we check if jobs
    # can be scheduled in the front-end before inserting into the
    # database

    # valid_worker_types = ('highcpu', 'standard', 'highmem')
    # worker_type = validate(
    #     'Worker type',
    #     post['worker_type'],
    #     lambda v: v in valid_worker_types,
    #     f'one of {", ".join(valid_worker_types)}')

    # valid_worker_cores = (1, 2, 4, 8, 16, 32, 64, 96)
    # worker_cores = validate_int(
    #     'Worker cores',
    #     post['worker_cores'],
    #     lambda v: v in valid_worker_cores,
    #     f'one of {", ".join(str(v) for v in valid_worker_cores)}')

    # worker_disk_size_gb = validate_int(
    #     'Worker disk size',
    #     post['worker_disk_size_gb'],
    #     lambda v: v > 0,
    #     'a positive integer')

    max_instances = validate_int(
        'Max instances',
        post['max_instances'],
        lambda v: v > 0,
        'a positive integer')

    pool_size = validate_int(
        'Worker pool size',
        post['pool_size'],
        lambda v: v > 0,
        'a positive integer')

    await inst_pool.configure(
        # worker_type, worker_cores, worker_disk_size_gb,
        max_instances, pool_size)

    set_message(session,
                'Updated batch configuration.',
                'info')

    return web.HTTPFound(deploy_config.external_url('batch-driver', '/'))


@routes.get('/user_resources')
@web_authenticated_developers_only()
async def get_user_resources(request, userdata):
    app = request.app
    user_resources = await app['scheduler'].compute_fair_share()
    user_resources = sorted(user_resources.values(),
                            key=lambda record: record['ready_cores_mcpu'] + record['running_cores_mcpu'],
                            reverse=True)
    page_context = {
        'user_resources': user_resources
    }
    return await render_template('batch-driver', request, userdata,
                                 'user_resources.html', page_context)


async def check_incremental_loop(db):
    @transaction(db, read_only=True)
    async def check(tx):
        ready_cores = await tx.execute_and_fetchone('''
SELECT CAST(COALESCE(SUM(ready_cores_mcpu), 0) AS SIGNED) AS ready_cores_mcpu
FROM ready_cores
LOCK IN SHARE MODE;
''')
        ready_cores_mcpu = ready_cores['ready_cores_mcpu']

        computed_ready_cores = await tx.execute_and_fetchone('''
SELECT CAST(COALESCE(SUM(cores_mcpu), 0) AS SIGNED) AS ready_cores_mcpu
FROM jobs
INNER JOIN batches ON batches.id = jobs.batch_id
WHERE batches.`state` = 'running'
        AND jobs.state = 'Ready'
        # runnable
        AND (jobs.always_run OR NOT (jobs.cancelled OR batches.cancelled))
LOCK IN SHARE MODE;
''')
        computed_ready_cores_mcpu = computed_ready_cores['ready_cores_mcpu']

        if ready_cores_mcpu != computed_ready_cores_mcpu:
            log.error(f'ready_cores corrupt: ready_cores_mcpu {ready_cores_mcpu} != computed_ready_cores_mcpu {computed_ready_cores_mcpu}')

        user_resources = tx.execute_and_fetchall('''
SELECT user,
  CAST(COALESCE(SUM(n_ready_jobs), 0) AS SIGNED) AS n_ready_jobs,
  CAST(COALESCE(SUM(ready_cores_mcpu), 0) AS SIGNED) AS ready_cores_mcpu,
  CAST(COALESCE(SUM(n_running_jobs), 0) AS SIGNED) AS n_running_jobs,
  CAST(COALESCE(SUM(running_cores_mcpu), 0) AS SIGNED) AS running_cores_mcpu,
  CAST(COALESCE(SUM(n_cancelled_ready_jobs), 0) AS SIGNED) AS n_cancelled_ready_jobs,
  CAST(COALESCE(SUM(n_cancelled_running_jobs), 0) AS SIGNED) AS n_cancelled_running_jobs
FROM user_resources
GROUP BY user
LOCK IN SHARE MODE;
''')
        user_resources = {record['user']: record async for record in user_resources}

        computed_user_resources = tx.execute_and_fetchall('''
SELECT user,
    COALESCE(SUM(state = 'Ready' AND runnable), 0) as n_ready_jobs,
    COALESCE(SUM(IF(state = 'Ready' AND runnable, cores_mcpu, 0)), 0) as ready_cores_mcpu,
    COALESCE(SUM(state = 'Running' AND NOT cancelled), 0) as n_running_jobs,
    COALESCE(SUM(IF(state = 'Running' AND NOT cancelled, cores_mcpu, 0)), 0) as running_cores_mcpu,
    COALESCE(SUM(state = 'Ready' AND cancelled), 0) as n_cancelled_ready_jobs,
    COALESCE(SUM(state = 'Running' AND cancelled), 0) as n_cancelled_running_jobs
FROM (SELECT
    jobs.state,
    jobs.cores_mcpu,
    (jobs.always_run OR NOT (jobs.cancelled OR batches.cancelled)) AS runnable,
    (NOT jobs.always_run AND (jobs.cancelled OR batches.cancelled)) AS cancelled,
    batches.user
  FROM jobs
  INNER JOIN batches ON batches.id = jobs.batch_id
  WHERE batches.`state` = 'running'
  LOCK IN SHARE MODE) AS s
GROUP BY user;
''')
        computed_user_resources = {record['user']: record async for record in computed_user_resources}

        def user_get(d, u, f):
            if u not in d:
                return 0
            return d[u][f]

        fields = ['n_running_jobs', 'running_cores_mcpu', 'n_ready_jobs', 'ready_cores_mcpu',
                  'n_cancelled_ready_jobs', 'n_cancelled_running_jobs']
        users = set(user_resources.keys())
        users.update(computed_user_resources.keys())
        for u in users:
            for f in fields:
                v = user_get(user_resources, u, f)
                computed_v = user_get(user_resources, u, f)

                if v != computed_v:
                    log.error(f'user_resources corrupt: user_resources[{u}][{f}] {v} != computed_user_resources[{u}][{f}] {computed_v}')

    while True:
        try:
            await check()  # pylint: disable=no-value-for-parameter
        except Exception:
            log.exception('while checking incremental')
        # 10/s
        await asyncio.sleep(0.1)


async def on_startup(app):
    pool = concurrent.futures.ThreadPoolExecutor()
    app['blocking_pool'] = pool

    kube.config.load_incluster_config()
    k8s_client = kube.client.CoreV1Api()
    k8s_cache = K8sCache(k8s_client, refresh_time=5)
    app['k8s_cache'] = k8s_cache

    db = Database()
    await db.async_init(maxsize=50)
    app['db'] = db

    row = await db.select_and_fetchone(
        'SELECT worker_type, worker_cores, worker_disk_size_gb, instance_id, internal_token FROM globals;')

    app['worker_type'] = row['worker_type']
    app['worker_cores'] = row['worker_cores']
    app['worker_disk_size_gb'] = row['worker_disk_size_gb']

    instance_id = row['instance_id']
    log.info(f'instance_id {instance_id}')
    app['instance_id'] = instance_id

    app['internal_token'] = row['internal_token']

    machine_name_prefix = f'batch-worker-{DEFAULT_NAMESPACE}-'

    credentials = google.oauth2.service_account.Credentials.from_service_account_file(
        '/gsa-key/key.json')
    gservices = GServices(machine_name_prefix, credentials)
    app['gservices'] = gservices

    scheduler_state_changed = asyncio.Event()
    app['scheduler_state_changed'] = scheduler_state_changed

    cancel_ready_state_changed = asyncio.Event()
    app['cancel_ready_state_changed'] = cancel_ready_state_changed

    cancel_running_state_changed = asyncio.Event()
    app['cancel_running_state_changed'] = cancel_running_state_changed

    log_store = LogStore(BATCH_BUCKET_NAME, instance_id, pool, credentials=credentials)
    app['log_store'] = log_store

    inst_pool = InstancePool(app, machine_name_prefix)
    app['inst_pool'] = inst_pool
    await inst_pool.async_init()

    scheduler = Scheduler(app)
    await scheduler.async_init()
    app['scheduler'] = scheduler

    # asyncio.ensure_future(check_incremental_loop(db))


async def on_cleanup(app):
    blocking_pool = app['blocking_pool']
    blocking_pool.shutdown()


def run():
    if HAIL_SHOULD_PROFILE:
        profiler_tag = f'{DEFAULT_NAMESPACE}'
        if profiler_tag == 'default':
            profiler_tag = DEFAULT_NAMESPACE + f'-{HAIL_SHA[0:12]}'
        googlecloudprofiler.start(
            service='batch-driver',
            service_version=profiler_tag,
            # https://cloud.google.com/profiler/docs/profiling-python#agent_logging
            verbose=3)

    app = web.Application(client_max_size=HTTP_CLIENT_MAX_SIZE)
    setup_aiohttp_session(app)

    setup_aiohttp_jinja2(app, 'batch.driver')
    setup_common_static_routes(routes)
    app.add_routes(routes)
    app.router.add_get("/metrics", server_stats)

    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)

    web.run_app(deploy_config.prefix_application(app,
                                                 'batch-driver',
                                                 client_max_size=HTTP_CLIENT_MAX_SIZE),
                host='0.0.0.0',
                port=5000)
