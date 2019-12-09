import random
import math
import collections
from hailtop.batch_client.client import BatchClient, Job
import json
import os
import base64
import pkg_resources
import secrets
import time
import unittest
import aiohttp
import requests
from hailtop.config import get_deploy_config
from hailtop.auth import service_auth_headers

from .serverthread import ServerThread
from .utils import legacy_batch_status

deploy_config = get_deploy_config()


def poll_until(p, max_polls=None):
    i = 0
    while True and (max_polls is None or i < max_polls):
        x = p()
        if x:
            return x
        # max 4.5s
        j = random.randrange(math.floor(1.1 ** min(i, 40)))
        time.sleep(0.100 * j)
        i = i + 1
    raise ValueError(f'poll_until: exceeded max polls: {i} {max_polls}')


class Test(unittest.TestCase):
    def setUp(self):
        self.client = BatchClient('test')

    def tearDown(self):
        self.client.close()

    def test_job(self):
        builder = self.client.create_batch()
        j = builder.create_job('ubuntu:18.04', ['echo', 'test'])
        b = builder.submit()
        status = j.wait()
        self.assertTrue('attributes' not in status, (status, j.log()))
        self.assertEqual(status['state'], 'Success', (status, j.log()))
        self.assertEqual(j._get_exit_code(status, 'main'), 0, (status, j.log()))

        self.assertEqual(j.log()['main'], 'test\n', status)

    def test_msec_mcpu(self):
        builder = self.client.create_batch()
        # two jobs so the batch msec_mcpu computation is non-trivial
        builder.create_job('ubuntu:18.04', ['echo', 'foo'])
        builder.create_job('ubuntu:18.04', ['echo', 'bar'])
        b = builder.submit()

        batch = b.wait()
        assert batch['state'] == 'success', batch

        batch_msec_mcpu2 = 0
        for job in b.jobs():
            job_status = job['status']

            # tests run at 100mcpu
            job_msec_mcpu2 = 100 * max(job_status['end_time'] - job_status['start_time'], 0)
            # greater than in case there are multiple attempts
            assert job['msec_mcpu'] >= job_msec_mcpu2, batch

            batch_msec_mcpu2 += job_msec_mcpu2

        assert batch['msec_mcpu'] == batch_msec_mcpu2, batch

    def test_attributes(self):
        a = {
            'name': 'test_attributes',
            'foo': 'bar'
        }
        builder = self.client.create_batch()
        j = builder.create_job('ubuntu:18.04', ['true'], attributes=a)
        builder.submit()
        status = j.status()
        assert(status['attributes'] == a)

    def test_garbage_image(self):
        builder = self.client.create_batch()
        j = builder.create_job('dsafaaadsf', ['echo', 'test'])
        builder.submit()
        status = j.wait()
        assert j._get_exit_codes(status) == {'main': None}, status
        assert j._get_error(status, 'main') is not None
        assert status['state'] == 'Error', status

    def test_bad_command(self):
        builder = self.client.create_batch()
        j = builder.create_job('ubuntu:18.04', ['sleep 5'])
        builder.submit()
        status = j.wait()
        assert j._get_exit_codes(status) == {'main': None}, status
        assert j._get_error(status, 'main') is not None
        assert status['state'] == 'Error', status

    def test_invalid_resource_requests(self):
        builder = self.client.create_batch()
        resources = {'cpu': '1', 'memory': '28Gi'}
        builder.create_job('ubuntu:18.04', ['true'], resources=resources)
        with self.assertRaisesRegex(aiohttp.client.ClientResponseError, 'resource requests.*unsatisfiable'):
            builder.submit()

        builder = self.client.create_batch()
        resources = {'cpu': '0', 'memory': '1Gi'}
        builder.create_job('ubuntu:18.04', ['true'], resources=resources)
        with self.assertRaisesRegex(aiohttp.client.ClientResponseError, 'bad resource request.*cpu cannot be 0'):
            builder.submit()

    def test_out_of_memory(self):
        builder = self.client.create_batch()
        resources = {'cpu': '0.1', 'memory': '10M'}
        j = builder.create_job('python:3.6-slim-stretch',
                               ['python', '-c', 'x = "a" * 400 * 1000**2'],
                               resources=resources)
        builder.submit()
        status = j.wait()
        assert j._get_out_of_memory(status, 'main')

    def test_unsubmitted_state(self):
        builder = self.client.create_batch()
        j = builder.create_job('ubuntu:18.04', ['echo', 'test'])

        with self.assertRaises(ValueError):
            j.batch_id
        with self.assertRaises(ValueError):
            j.id
        with self.assertRaises(ValueError):
            j.status()
        with self.assertRaises(ValueError):
            j.is_complete()
        with self.assertRaises(ValueError):
            j.log()
        with self.assertRaises(ValueError):
            j.wait()

        builder.submit()
        with self.assertRaises(ValueError):
            builder.create_job('ubuntu:18.04', ['echo', 'test'])

    def test_list_batches(self):
        tag = secrets.token_urlsafe(64)
        b1 = self.client.create_batch(attributes={'tag': tag, 'name': 'b1'})
        b1.create_job('ubuntu:18.04', ['sleep', '3600'])
        b1 = b1.submit()

        b2 = self.client.create_batch(attributes={'tag': tag, 'name': 'b2'})
        b2.create_job('ubuntu:18.04', ['echo', 'test'])
        b2 = b2.submit()

        def assert_batch_ids(expected, q=None):
            batches = self.client.list_batches(q)
            # list_batches returns all batches for all prev run tests
            actual = set([b.id for b in batches]).intersection({b1.id, b2.id})
            self.assertEqual(actual, expected)

        assert_batch_ids({b1.id, b2.id})

        assert_batch_ids({b1.id, b2.id}, f'tag={tag}')

        b2.wait()

        assert_batch_ids({b1.id}, f'!complete tag={tag}')
        assert_batch_ids({b2.id}, f'complete tag={tag}')

        assert_batch_ids({b1.id}, f'!success tag={tag}')
        assert_batch_ids({b2.id}, f'success tag={tag}')

        b1.cancel()
        b1.wait()

        assert_batch_ids({b1.id}, f'!success tag={tag}')
        assert_batch_ids({b2.id}, f'success tag={tag}')

        assert_batch_ids(set(), f'!complete tag={tag}')
        assert_batch_ids({b1.id, b2.id}, f'complete tag={tag}')

        assert_batch_ids({b2.id}, f'tag={tag} name=b2')

    def test_include_jobs(self):
        b1 = self.client.create_batch()
        for i in range(2):
            b1.create_job('ubuntu:18.04', ['true'])
        b1 = b1.submit()
        s = b1.status()
        assert 'jobs' not in s

    def test_fail(self):
        b = self.client.create_batch()
        j = b.create_job('ubuntu:18.04', ['false'])
        b.submit()
        status = j.wait()
        self.assertEqual(j._get_exit_code(status, 'main'), 1)

    def test_running_job_log_and_status(self):
        b = self.client.create_batch()
        j = b.create_job('ubuntu:18.04', ['sleep', '300'])
        b = b.submit()

        while True:
            if j.status()['state'] == 'Running' or j.is_complete():
                break

        j.log()
        # FIXME after batch1 goes away, check running status
        b.cancel()

    def test_deleted_job_log(self):
        b = self.client.create_batch()
        j = b.create_job('ubuntu:18.04', ['echo', 'test'])
        b = b.submit()
        j.wait()
        b.delete()

        try:
            j.log()
        except aiohttp.ClientResponseError as e:
            if e.status == 404:
                pass
            else:
                self.assertTrue(False, f"batch should have deleted log {e}")

    def test_delete_batch(self):
        b = self.client.create_batch()
        j = b.create_job('ubuntu:18.04', ['sleep', '30'])
        b = b.submit()
        b.delete()

        # verify doesn't exist
        try:
            self.client.get_job(*j.id)
        except aiohttp.ClientResponseError as e:
            if e.status == 404:
                pass
            else:
                raise

    def test_cancel_batch(self):
        b = self.client.create_batch()
        j = b.create_job('ubuntu:18.04', ['sleep', '30'])
        b = b.submit()

        status = j.status()
        assert status['state'] in ('Ready', 'Running'), status

        b.cancel()

        status = j.wait()
        assert status['state'] == 'Cancelled', status
        assert 'log' not in status, status

        # cancelled job has no log
        try:
            j.log()
        except aiohttp.ClientResponseError as e:
            if e.status == 404:
                pass
            else:
                raise

    def test_get_nonexistent_job(self):
        try:
            self.client.get_job(1, 666)
        except aiohttp.ClientResponseError as e:
            if e.status == 404:
                pass
            else:
                raise

    def test_get_job(self):
        b = self.client.create_batch()
        j = b.create_job('ubuntu:18.04', ['true'])
        b.submit()

        j2 = self.client.get_job(*j.id)
        status2 = j2.status()
        assert (status2['batch_id'], status2['job_id']) == j.id

    def test_batch(self):
        b = self.client.create_batch()
        j1 = b.create_job('ubuntu:18.04', ['false'])
        j2 = b.create_job('ubuntu:18.04', ['sleep', '1'])
        j3 = b.create_job('ubuntu:18.04', ['sleep', '30'])
        b = b.submit()

        j1.wait()
        j2.wait()
        b.cancel()
        b.wait()
        bstatus = legacy_batch_status(b)

        assert len(bstatus['jobs']) == 3, bstatus
        state_count = collections.Counter([j['state'] for j in bstatus['jobs']])
        n_cancelled = state_count['Cancelled']
        n_complete = state_count['Error'] + state_count['Failed'] + state_count['Success']
        assert n_cancelled <= 1, bstatus
        assert n_cancelled + n_complete == 3, bstatus

        n_failed = sum([Job._get_exit_code(j, 'main') > 0 for j in bstatus['jobs'] if j['state'] in ('Failed', 'Error')])
        assert n_failed == 1, bstatus

    def test_batch_status(self):
        b1 = self.client.create_batch()
        b1.create_job('ubuntu:18.04', ['true'])
        b1 = b1.submit()
        b1.wait()
        b1s = b1.status()
        assert b1s['complete'] and b1s['state'] == 'success', b1s

        b2 = self.client.create_batch()
        b2.create_job('ubuntu:18.04', ['false'])
        b2.create_job('ubuntu:18.04', ['true'])
        b2 = b2.submit()
        b2.wait()
        b2s = b2.status()
        assert b2s['complete'] and b2s['state'] == 'failure', b2s

        b3 = self.client.create_batch()
        b3.create_job('ubuntu:18.04', ['sleep', '30'])
        b3 = b3.submit()
        b3s = b3.status()
        assert not b3s['complete'] and b3s['state'] == 'running', b3s
        b3.cancel()

        b4 = self.client.create_batch()
        b4.create_job('ubuntu:18.04', ['sleep', '30'])
        b4 = b4.submit()
        b4.cancel()
        b4.wait()
        b4s = b4.status()
        assert b4s['complete'] and b4s['state'] == 'cancelled', b4s

    def test_log_after_failing_job(self):
        b = self.client.create_batch()
        j = b.create_job('ubuntu:18.04', ['/bin/sh', '-c', 'echo test; exit 127'])
        b.submit()
        status = j.wait()
        self.assertTrue('attributes' not in status)
        self.assertEqual(status['state'], 'Failed')
        self.assertEqual(j._get_exit_code(status, 'main'), 127)

        self.assertEqual(j.log()['main'], 'test\n')

        self.assertTrue(j.is_complete())

    def test_authorized_users_only(self):
        endpoints = [
            (requests.get, '/api/v1alpha/batches/0/jobs/0', 401),
            (requests.get, '/api/v1alpha/batches/0/jobs/0/log', 401),
            (requests.get, '/api/v1alpha/batches', 401),
            (requests.post, '/api/v1alpha/batches/create', 401),
            (requests.post, '/api/v1alpha/batches/0/jobs/create', 401),
            (requests.get, '/api/v1alpha/batches/0', 401),
            (requests.delete, '/api/v1alpha/batches/0', 401),
            (requests.patch, '/api/v1alpha/batches/0/close', 401),
            # redirect to auth/login
            (requests.get, '/batches', 302),
            (requests.get, '/batches/0', 302),
            (requests.post, '/batches/0/cancel', 401),
            (requests.get, '/batches/0/jobs/0', 302)]
        for f, url, expected in endpoints:
            full_url = deploy_config.url('batch', url)
            r = f(full_url, allow_redirects=False)
            assert r.status_code == expected, (full_url, r, expected)

    def test_bad_token(self):
        token = base64.urlsafe_b64encode(secrets.token_bytes(32)).decode('ascii')
        bc = BatchClient('test', _token=token)
        try:
            b = bc.create_batch()
            j = b.create_job('ubuntu:18.04', ['false'])
            b.submit()
            assert False, j
        except aiohttp.ClientResponseError as e:
            assert e.status == 401, e
        finally:
            bc.close()

    def test_gcr_image(self):
        builder = self.client.create_batch()
        j = builder.create_job(os.environ['HAIL_BASE_IMAGE'], ['echo', 'test'])
        b = builder.submit()
        status = j.wait()

        self.assertEqual(status['state'], 'Success', (status, j.log()))

    def test_service_account(self):
        b = self.client.create_batch()
        j = b.create_job(
            os.environ['CI_UTILS_IMAGE'],
            ['/bin/sh', '-c', 'kubectl get pods -l app=batch-driver'],
            service_account={
                'namespace': os.environ['HAIL_BATCH_PODS_NAMESPACE'],
                'name': 'ci-agent'
            })
        b.submit()
        status = j.wait()
        assert j._get_exit_code(status, 'main') == 0, status
