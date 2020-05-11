import os
import random
import sys
import re

from benchmark_hail.run.resources import all_resources
from benchmark_hail.run.utils import list_benchmarks
from hailtop import batch as hb

if __name__ == '__main__':
    if len(sys.argv) != 6:
        raise RuntimeError(f'usage: <script.py> DOCKER_IMAGE_URL BUCKET_BASE SHA N_REPLICATES N_ITERS')
    BENCHMARK_IMAGE = sys.argv[1]
    BUCKET_BASE = sys.argv[2]
    SHA = sys.argv[3]
    N_REPLICATES = int(sys.argv[4])
    N_ITERS = int(sys.argv[5])

    labeled_sha = SHA
    label = os.environ.get('BENCHMARK_LABEL')
    if label:
        labeled_sha = f'{labeled_sha}-{label}'
    output_file = os.path.join(BUCKET_BASE, f'{labeled_sha}.json')

    b = hb.Batch(name=f'benchmark-{labeled_sha}',
                 backend=hb.ServiceBackend(billing_project='hail'),
                 default_image=BENCHMARK_IMAGE,
                 default_storage='100G',
                 default_memory='7G',
                 default_cpu=2,
                 attributes={'output_file': output_file,
                             'n_replicates': str(N_REPLICATES),
                             'n_iters': str(N_ITERS),
                             'image': str(BENCHMARK_IMAGE)})

    resource_tasks = {}
    for r in all_resources:
        j = b.new_job(f'create_resource_{r.name()}').cpu(4)
        j.command(f'hail-bench create-resources --data-dir benchmark-resources --group {r.name()}')
        j.command(f"time tar -cf {r.name()}.tar benchmark-resources/{r.name()} --exclude='*.crc'")
        j.command(f'ls -lh {r.name()}.tar')
        j.command(f'mv {r.name()}.tar {j.ofile}')
        resource_tasks[r] = j

    all_benchmarks = list_benchmarks()
    assert len(all_benchmarks) > 0

    all_output = []


    task_filter_regex = os.environ.get('BENCHMARK_REGEX')
    if task_filter_regex:
        task_filter = lambda t: re.match(task_filter_regex, t) is not None
    else:
        task_filter = lambda t: True


    n_passed_filter = 0
    task_fs = []
    for benchmark in all_benchmarks:
        if task_filter(benchmark.name):
            n_passed_filter += 1
            for replicate in range(N_REPLICATES):
                task_fs.append((benchmark.name, replicate, benchmark.groups))

    print(f'generating {n_passed_filter} * {N_REPLICATES} = {n_passed_filter * N_REPLICATES} individual benchmark tasks')

    random.shuffle(task_fs)

    for name, replicate, groups in task_fs:
        j = b.new_job(name=f'{name}_{replicate}')
        j.command('mkdir -p benchmark-resources')
        for resource_group in groups:
            resource_task = resource_tasks[resource_group]
            j.command(f'mv {resource_task.ofile} benchmark-resources/{resource_group.name()}.tar')
            j.command(f'time tar -xf benchmark-resources/{resource_group.name()}.tar')
        j.command(f'MKL_NUM_THREADS=1'
                  f'OPENBLAS_NUM_THREADS=1'
                  f'OMP_NUM_THREADS=1'
                  f'VECLIB_MAXIMUM_THREADS=1'
                  f'PYSPARK_SUBMIT_ARGS="--driver-memory 6G pyspark-shell" '
                  f'hail-bench run -o {j.ofile} -n {N_ITERS} --data-dir benchmark-resources -t {name}')
        all_output.append(j.ofile)

    combine_branch_factor = int(os.environ.get('BENCHMARK_BRANCH_FACTOR', 32))
    phase_i = 1
    while len(all_output) > combine_branch_factor:

        new_output = []

        job_i = 1
        i = 0
        while i < len(all_output):
            combine = b.new_job(f'combine_output_phase{phase_i}_job{job_i}')
            combine.command(
                f'hail-bench combine -o {combine.ofile} ' + ' '.join(all_output[i:i + combine_branch_factor]))
            new_output.append(combine.ofile)
            i += combine_branch_factor
            job_i += 1

        phase_i += 1
        all_output = new_output

    combine = b.new_job('final_combine_output')
    combine.command(f'hail-bench combine -o {combine.ofile} ' + ' '.join(all_output))
    combine.command(f'cat {combine.ofile}')

    print(f'writing output to {output_file}')

    b.write_output(combine.ofile, output_file)
    b.run()
