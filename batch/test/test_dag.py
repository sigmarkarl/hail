import os
import time
import re
import pytest
import aiohttp
from flask import Response
from hailtop.batch_client.client import BatchClient, Job
import hailtop.batch_client.aioclient as aioclient
from hailtop.auth import get_userinfo

from .utils import batch_status_job_counter, batch_status_exit_codes, \
    legacy_batch_status
from .serverthread import ServerThread


@pytest.fixture
def client():
    client = BatchClient('test')
    yield client
    client.close()


def test_simple(client):
    batch = client.create_batch()
    head = batch.create_job('ubuntu:18.04', command=['echo', 'head'])
    tail = batch.create_job('ubuntu:18.04', command=['echo', 'tail'], parents=[head])
    batch = batch.submit()
    batch.wait()
    status = legacy_batch_status(batch)
    assert batch_status_job_counter(status, 'Success') == 2, status
    assert batch_status_exit_codes(status) == [
        {'main': 0}, {'main': 0}], status


def test_missing_parent_is_400(client):
    try:
        batch = client.create_batch()
        fake_job = aioclient.Job.unsubmitted_job(batch._async_builder, 10000)
        fake_job = Job.from_async_job(fake_job)
        batch.create_job('ubuntu:18.04', command=['echo', 'head'], parents=[fake_job])
        batch.submit()
    except ValueError as err:
        assert re.search('parents with invalid job ids', str(err))
        return
    assert False


def test_dag(client):
    batch = client.create_batch()
    head = batch.create_job('ubuntu:18.04', command=['echo', 'head'])
    left = batch.create_job('ubuntu:18.04', command=['echo', 'left'], parents=[head])
    right = batch.create_job('ubuntu:18.04', command=['echo', 'right'], parents=[head])
    tail = batch.create_job('ubuntu:18.04', command=['echo', 'tail'], parents=[left, right])
    batch = batch.submit()
    batch.wait()
    status = legacy_batch_status(batch)
    assert batch_status_job_counter(status, 'Success') == 4, status
    for node in [head, left, right, tail]:
        status = node.status()
        assert status['state'] == 'Success'
        assert node._get_exit_code(status, 'main') == 0


def test_cancel_tail(client):
    batch = client.create_batch()
    head = batch.create_job('ubuntu:18.04', command=['echo', 'head'])
    left = batch.create_job('ubuntu:18.04', command=['echo', 'left'], parents=[head])
    right = batch.create_job('ubuntu:18.04', command=['echo', 'right'], parents=[head])
    tail = batch.create_job(
        'ubuntu:18.04',
        command=['/bin/sh', '-c', 'while true; do sleep 86000; done'],
        parents=[left, right])
    batch = batch.submit()
    left.wait()
    right.wait()
    batch.cancel()
    batch.wait()
    status = legacy_batch_status(batch)
    assert batch_status_job_counter(status, 'Success') == 3, status
    for node in [head, left, right]:
        status = node.status()
        assert status['state'] == 'Success'
        assert node._get_exit_code(status, 'main') == 0
    assert tail.status()['state'] == 'Cancelled'


def test_cancel_left_after_tail(client):
    batch = client.create_batch()
    head = batch.create_job('ubuntu:18.04', command=['echo', 'head'])
    left = batch.create_job(
        'ubuntu:18.04',
        command=['/bin/sh', '-c', 'while true; do sleep 86000; done'],
        parents=[head])
    right = batch.create_job('ubuntu:18.04', command=['echo', 'right'], parents=[head])
    tail = batch.create_job('ubuntu:18.04', command=['echo', 'tail'], parents=[left, right])
    batch = batch.submit()
    head.wait()
    right.wait()
    batch.cancel()
    batch.wait()
    status = legacy_batch_status(batch)
    assert batch_status_job_counter(status, 'Success') == 2, status
    for node in [head, right]:
        status = node.status()
        assert status['state'] == 'Success'
        assert node._get_exit_code(status, 'main') == 0
    for node in [left, tail]:
        assert node.status()['state'] == 'Cancelled'


def test_callback(client):
    from flask import Flask, request
    app = Flask('test-client')
    callback_body = []

    @app.route('/test', methods=['POST'])
    def test():
        body = request.get_json()
        callback_body.append(body)
        return Response(status=200)

    try:
        server = ServerThread(app)
        server.start()
        b = client.create_batch(
            callback=server.url_for('/test'),
            attributes={'foo': 'bar'})
        head = b.create_job('alpine:3.8', command=['echo', 'head'])
        tail = b.create_job('alpine:3.8', command=['echo', 'tail'], parents=[head])
        b = b.submit()
        b.wait()

        i = 0
        while not callback_body:
            time.sleep(0.100 * (3/2) ** i)
            i += 1
            if i > 14:
                break
        callback_body = callback_body[0]

        # verify msec_mcpu, cost present
        callback_body.pop('cost')
        callback_body.pop('msec_mcpu')
        assert (callback_body == {
            'id': b.id,
            'billing_project': 'test',
            'state': 'success',
            'complete': True,
            'closed': True,
            'n_jobs': 2,
            'n_completed': 2,
            'n_succeeded': 2,
            'n_failed': 0,
            'n_cancelled': 0,
            'attributes': {'foo': 'bar'}
        }), callback_body
    finally:
        if server:
            server.shutdown()
            server.join()


def test_no_parents_allowed_in_other_batches(client):
    b1 = client.create_batch()
    b2 = client.create_batch()
    head = b1.create_job('ubuntu:18.04', command=['echo', 'head'])
    try:
        b2.create_job('ubuntu:18.04', command=['echo', 'tail'], parents=[head])
    except ValueError as err:
        assert re.search('parents from another batch', str(err))
        return
    assert False


def test_input_dependency(client):
    user = get_userinfo()
    batch = client.create_batch()
    head = batch.create_job('ubuntu:18.04',
                            command=['/bin/sh', '-c', 'echo head1 > /io/data1 ; echo head2 > /io/data2'],
                            output_files=[('/io/data*', f'gs://{user["bucket_name"]}')])
    tail = batch.create_job('ubuntu:18.04',
                            command=['/bin/sh', '-c', 'cat /io/data1 ; cat /io/data2'],
                            input_files=[(f'gs://{user["bucket_name"]}/data*', '/io/')],
                            parents=[head])
    batch.submit()
    tail.wait()
    assert head._get_exit_code(head.status(), 'main') == 0, head._status
    assert tail.log()['main'] == 'head1\nhead2\n', tail.status()


def test_input_dependency_directory(client):
    user = get_userinfo()
    batch = client.create_batch()
    head = batch.create_job('ubuntu:18.04',
                            command=['/bin/sh', '-c', 'mkdir -p /io/test/; echo head1 > /io/test/data1 ; echo head2 > /io/test/data2'],
                            output_files=[('/io/test/', f'gs://{user["bucket_name"]}')])
    tail = batch.create_job('ubuntu:18.04',
                            command=['/bin/sh', '-c', 'cat /io/test/data1 ; cat /io/test/data2'],
                            input_files=[(f'gs://{user["bucket_name"]}/test', '/io/')],
                            parents=[head])
    batch.submit()
    tail.wait()
    assert head._get_exit_code(head.status(), 'main') == 0, head._status
    assert tail.log()['main'] == 'head1\nhead2\n', tail.status()


def test_always_run_cancel(client):
    batch = client.create_batch()
    head = batch.create_job('ubuntu:18.04', command=['echo', 'head'])
    left = batch.create_job(
        'ubuntu:18.04',
        command=['/bin/sh', '-c', 'while true; do sleep 86000; done'],
        parents=[head])
    right = batch.create_job('ubuntu:18.04', command=['echo', 'right'], parents=[head])
    tail = batch.create_job('ubuntu:18.04',
                            command=['echo', 'tail'],
                            parents=[left, right],
                            always_run=True)
    batch = batch.submit()
    right.wait()
    batch.cancel()
    batch.wait()
    status = legacy_batch_status(batch)
    assert batch_status_job_counter(status, 'Success') == 3, status
    assert batch_status_job_counter(status, 'Cancelled') == 1, status

    for node in [head, right, tail]:
        status = node.status()
        assert status['state'] == 'Success', status
        assert node._get_exit_code(status, 'main') == 0, status


def test_always_run_error(client):
    batch = client.create_batch()
    head = batch.create_job('ubuntu:18.04', command=['/bin/sh', '-c', 'exit 1'])
    tail = batch.create_job('ubuntu:18.04',
                            command=['echo', 'tail'],
                            parents=[head],
                            always_run=True)
    batch = batch.submit()
    batch.wait()
    status = legacy_batch_status(batch)
    assert batch_status_job_counter(status, 'Failed') == 1
    assert batch_status_job_counter(status, 'Success') == 1

    for job, ec, state in [(head, 1, 'Failed'), (tail, 0, 'Success')]:
        status = job.status()
        assert status['state'] == state, status
        assert job._get_exit_code(status, 'main') == ec, status
