import abc
import os
import subprocess as sp
import uuid
import time
import copy
from shlex import quote as shq
import webbrowser
from hailtop.config import get_deploy_config
from hailtop.batch_client.client import BatchClient

from .resource import InputResourceFile, TaskResourceFile


class Backend:
    """
    Abstract class for backends.
    """

    @abc.abstractmethod
    def _run(self, pipeline, dry_run, verbose, delete_scratch_on_exit, **backend_kwargs):
        """
        Execute a pipeline.

        Warning
        -------
        This method should not be called directly. Instead, use :meth:`.Pipeline.run`.
        """
        return


class LocalBackend(Backend):
    """
    Backend that executes pipelines on a local computer.

    Examples
    --------

    >>> local_backend = LocalBackend(tmp_dir='/tmp/user/')
    >>> p = Pipeline(backend=local_backend)

    Parameters
    ----------
    tmp_dir: :obj:`str`, optional
        Temporary directory to use.
    gsa_key_file: :obj:`str`, optional
        Mount a file with a gsa key to `/gsa-key/key.json`. Only used if a
        task specifies a docker image. This option will override the value set by
        the environment variable `HAIL_PIPELINE_GSA_KEY_FILE`.
    extra_docker_run_flags: :obj:`str`, optional
        Additional flags to pass to `docker run`. Only used if a task specifies
        a docker image. This option will override the value set by the environment
        variable `HAIL_PIPELINE_EXTRA_DOCKER_RUN_FLAGS`.
    """

    def __init__(self, tmp_dir='/tmp/', gsa_key_file=None, extra_docker_run_flags=None):
        self._tmp_dir = tmp_dir

        flags = ''

        if extra_docker_run_flags is not None:
            flags += extra_docker_run_flags
        elif os.environ.get('HAIL_PIPELINE_EXTRA_DOCKER_RUN_FLAGS') is not None:
            flags += os.environ['HAIL_PIPELINE_EXTRA_DOCKER_RUN_FLAGS']

        if gsa_key_file is None:
            gsa_key_file = os.environ.get('HAIL_PIPELINE_GSA_KEY_FILE')
        if gsa_key_file is not None:
            flags += f' -v {gsa_key_file}:/gsa-key/key.json'

        self._extra_docker_run_flags = flags

    def _run(self, pipeline, dry_run, verbose, delete_scratch_on_exit):  # pylint: disable=R0915
        """
        Execute a pipeline.

        Warning
        -------
        This method should not be called directly. Instead, use :meth:`.Pipeline.run`.

        Parameters
        ----------
        pipeline: :class:`.Pipeline`
            Pipeline to execute.
        dry_run: :obj:`bool`
            If `True`, don't execute code.
        verbose: :obj:`bool`
            If `True`, print debugging output.
        delete_scratch_on_exit: :obj:`bool`
            If `True`, delete temporary directories with intermediate files.
        """
        tmpdir = self._get_scratch_dir()

        script = ['#!/bin/bash',
                  'set -e' + 'x' if verbose else '',
                  '\n',
                  '# change cd to tmp directory',
                  f"cd {tmpdir}",
                  '\n']

        copied_input_resource_files = set()
        os.makedirs(tmpdir + 'inputs/', exist_ok=True)

        def copy_input(task, r):
            if isinstance(r, InputResourceFile):
                if r not in copied_input_resource_files:
                    copied_input_resource_files.add(r)

                    if r._input_path.startswith('gs://'):
                        return [f'gsutil cp {r._input_path} {r._get_path(tmpdir)}']

                    absolute_input_path = shq(os.path.realpath(r._input_path))
                    if task._image is not None:  # pylint: disable-msg=W0640
                        return [f'cp {absolute_input_path} {r._get_path(tmpdir)}']

                    return [f'ln -sf {absolute_input_path} {r._get_path(tmpdir)}']

                return []

            assert isinstance(r, TaskResourceFile)
            return []

        def copy_external_output(r):
            def _cp(dest):
                if not dest.startswith('gs://'):
                    dest = os.path.abspath(dest)
                    directory = os.path.dirname(dest)
                    os.makedirs(directory, exist_ok=True)
                    return 'cp'
                return 'gsutil cp'

            if isinstance(r, InputResourceFile):
                return [f'{_cp(dest)} {shq(r._input_path)} {shq(dest)}'
                        for dest in r._output_paths]

            assert isinstance(r, TaskResourceFile)
            return [f'{_cp(dest)} {r._get_path(tmpdir)} {shq(dest)}'
                    for dest in r._output_paths]

        write_inputs = [x for r in pipeline._input_resources for x in copy_external_output(r)]
        if write_inputs:
            script += ["# Write input resources to output destinations"]
            script += write_inputs
            script += ['\n']

        for task in pipeline._tasks:
            os.makedirs(tmpdir + task._uid + '/', exist_ok=True)

            script.append(f"# {task._uid} {task.name if task.name else ''}")

            script += [x for r in task._inputs for x in copy_input(task, r)]

            resource_defs = [r._declare(tmpdir) for r in task._mentioned]

            if task._image:
                defs = '; '.join(resource_defs) + '; ' if resource_defs else ''
                cmd = " && ".join(task._command)
                memory = f'-m {task._memory}' if task._memory else ''
                cpu = f'--cpus={task._cpu}' if task._cpu else ''

                script += [f"docker run "
                           f"{self._extra_docker_run_flags} "
                           f"-v {tmpdir}:{tmpdir} "
                           f"-w {tmpdir} "
                           f"{memory} "
                           f"{cpu} "
                           f"{task._image} /bin/bash "
                           f"-c {shq(defs + cmd)}",
                           '\n']
            else:
                script += resource_defs
                script += task._command

            script += [x for r in task._external_outputs for x in copy_external_output(r)]
            script += ['\n']

        script = "\n".join(script)
        if dry_run:
            print(script)
        else:
            try:
                sp.check_output(script, shell=True)
            except sp.CalledProcessError as e:
                print(e)
                print(e.output)
                raise
            finally:
                if delete_scratch_on_exit:
                    sp.run(f'rm -rf {tmpdir}', shell=True)

        print('Pipeline completed successfully!')

    def _get_scratch_dir(self):
        def _get_random_name():
            directory = self._tmp_dir + '/pipeline-{}/'.format(uuid.uuid4().hex[:12])

            if os.path.isdir(directory):
                return _get_random_name()
            os.makedirs(directory, exist_ok=True)
            return directory

        return _get_random_name()


class BatchBackend(Backend):
    """
    Backend that executes pipelines on Hail's Batch Service on Google Cloud.

    Examples
    --------

    >>> batch_backend = BatchBackend('test')
    >>> p = Pipeline(backend=batch_backend)
    >>> p.run() # doctest: +SKIP
    >>> batch_backend.close()

    Parameters
    ----------
    billing_project: :obj:`str`
        Name of billing project to use.
    """

    def __init__(self, billing_project):
        self._batch_client = BatchClient(billing_project)

    def close(self):
        """
        Close the connection with the Batch Service.

        Notes
        -----
        This method should be called after executing your pipelines at the
        end of your script.
        """
        self._batch_client.close()

    def _run(self,
             pipeline,
             dry_run,
             verbose,
             delete_scratch_on_exit,
             wait=True,
             open=False,
             disable_progress_bar=False):  # pylint: disable-msg=too-many-statements
        """
        Execute a pipeline.

        Warning
        -------
        This method should not be called directly. Instead, use :meth:`.Pipeline.run`
        and pass :class:`.BatchBackend` specific arguments as key-word arguments.

        Parameters
        ----------
        pipeline: :class:`.Pipeline`
            Pipeline to execute.
        dry_run: :obj:`bool`
            If `True`, don't execute code.
        verbose: :obj:`bool`
            If `True`, print debugging output.
        delete_scratch_on_exit: :obj:`bool`
            If `True`, delete temporary directories with intermediate files.
        wait: :obj:`bool`, optional
            If `True`, wait for the pipeline to finish executing before returning.
        open: :obj:`bool`, optional
            If `True`, open the UI page for the batch.
        disable_progress_bar: :obj:`bool`, optional
            If `True`, disable the progress bar.
        """
        build_dag_start = time.time()

        bucket = self._batch_client.bucket
        subdir_name = 'pipeline-{}'.format(uuid.uuid4().hex[:12])

        remote_tmpdir = f'gs://{bucket}/pipeline/{subdir_name}'
        local_tmpdir = f'/io/pipeline/{subdir_name}'

        default_image = 'ubuntu:latest'

        attributes = copy.deepcopy(pipeline.attributes)
        if pipeline.name is not None:
            attributes['name'] = pipeline.name

        batch = self._batch_client.create_batch(attributes=attributes)

        n_jobs_submitted = 0
        used_remote_tmpdir = False

        task_to_job_mapping = {}
        jobs_to_command = {}
        commands = []

        bash_flags = 'set -e' + ('x' if verbose else '') + '; '

        activate_service_account = 'gcloud -q auth activate-service-account ' \
                                   '--key-file=/gsa-key/key.json'

        def copy_input(r):
            if isinstance(r, InputResourceFile):
                return [(r._input_path, r._get_path(local_tmpdir))]
            assert isinstance(r, TaskResourceFile)
            return [(r._get_path(remote_tmpdir), r._get_path(local_tmpdir))]

        def copy_internal_output(r):
            assert isinstance(r, TaskResourceFile)
            return [(r._get_path(local_tmpdir), r._get_path(remote_tmpdir))]

        def copy_external_output(r):
            if isinstance(r, InputResourceFile):
                return [(r._input_path, dest) for dest in r._output_paths]
            assert isinstance(r, TaskResourceFile)
            return [(r._get_path(local_tmpdir), dest) for dest in r._output_paths]

        write_external_inputs = [x for r in pipeline._input_resources for x in copy_external_output(r)]
        if write_external_inputs:
            def _cp(src, dst):
                return f'gsutil -m cp -R {src} {dst}'

            write_cmd = bash_flags + activate_service_account + ' && ' + \
                ' && '.join([_cp(*files) for files in write_external_inputs])

            if dry_run:
                commands.append(write_cmd)
            else:
                j = batch.create_job(image='google/cloud-sdk:237.0.0-alpine',
                                     command=['/bin/bash', '-c', write_cmd],
                                     attributes={'name': 'write_external_inputs'})
                jobs_to_command[j] = write_cmd
                n_jobs_submitted += 1

        for task in pipeline._tasks:
            inputs = [x for r in task._inputs for x in copy_input(r)]

            outputs = [x for r in task._internal_outputs for x in copy_internal_output(r)]
            if outputs:
                used_remote_tmpdir = True
            outputs += [x for r in task._external_outputs for x in copy_external_output(r)]

            resource_defs = [r._declare(directory=local_tmpdir) for r in task._mentioned]

            if task._image is None:
                if verbose:
                    print(f"Using image '{default_image}' since no image was specified.")

            make_local_tmpdir = f'mkdir -p {local_tmpdir}/{task._uid}/; '
            defs = '; '.join(resource_defs) + '; ' if resource_defs else ''
            task_command = [cmd.strip() for cmd in task._command]

            cmd = bash_flags + make_local_tmpdir + defs + " && ".join(task_command)
            if dry_run:
                commands.append(cmd)
                continue

            parents = [task_to_job_mapping[t] for t in task._dependencies]

            attributes = copy.deepcopy(task.attributes)
            if task.name:
                attributes['name'] = task.name

            resources = {}
            if task._cpu:
                resources['cpu'] = task._cpu
            if task._memory:
                resources['memory'] = task._memory

            j = batch.create_job(image=task._image if task._image else default_image,
                                 command=['/bin/bash', '-c', cmd],
                                 parents=parents,
                                 attributes=attributes,
                                 resources=resources,
                                 input_files=inputs if len(inputs) > 0 else None,
                                 output_files=outputs if len(outputs) > 0 else None,
                                 pvc_size=task._storage,
                                 always_run=task._always_run,
                                 timeout=task._timeout)
            n_jobs_submitted += 1

            task_to_job_mapping[task] = j
            jobs_to_command[j] = cmd

        if dry_run:
            print("\n\n".join(commands))
            return None

        if delete_scratch_on_exit and used_remote_tmpdir:
            parents = list(jobs_to_command.keys())
            rm_cmd = f'gsutil -m rm -r {remote_tmpdir}'
            cmd = bash_flags + f'{activate_service_account} && {rm_cmd}'
            j = batch.create_job(
                image='google/cloud-sdk:237.0.0-alpine',
                command=['/bin/bash', '-c', cmd],
                parents=parents,
                attributes={'name': 'remove_tmpdir'},
                always_run=True)
            jobs_to_command[j] = cmd
            n_jobs_submitted += 1

        if verbose:
            print(f'Built DAG with {n_jobs_submitted} jobs in {round(time.time() - build_dag_start, 3)} seconds.')

        submit_batch_start = time.time()
        batch = batch.submit(disable_progress_bar=disable_progress_bar)

        jobs_to_command = {j.id: cmd for j, cmd in jobs_to_command.items()}

        if verbose:
            print(f'Submitted batch {batch.id} with {n_jobs_submitted} jobs in {round(time.time() - submit_batch_start, 3)} seconds:')
            for jid, cmd in jobs_to_command.items():
                print(f'{jid}: {cmd}')

            print('')

        deploy_config = get_deploy_config()
        url = deploy_config.url('batch', f'/batches/{batch.id}')
        print(f'Submitted batch {batch.id}, see {url}')

        if open:
            webbrowser.open(url)
        if wait:
            print(f'Waiting for batch {batch.id}...')
            status = batch.wait()
            print(f'Batch {batch.id} complete: {status["state"]}')
        return batch
