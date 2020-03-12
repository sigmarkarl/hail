import argparse
import datetime
import json
import os
import sys

import hail as hl

from .utils import run_all, run_pattern, run_list, RunConfig, init_logging


def main(args_):
    parser = argparse.ArgumentParser()

    parser.add_argument('--tests', '-t',
                        type=str,
                        required=False,
                        help='Run specific comma-delimited tests instead of running all tests.')
    parser.add_argument('--cores', '-c',
                        type=int,
                        default=1,
                        help='Number of cores to use.')
    parser.add_argument('--pattern', '-k', type=str, required=False,
                        help='Run all tests that substring match the pattern')
    parser.add_argument("--n-iter", "-n",
                        type=int,
                        default=3,
                        help='Number of iterations for each test.')
    parser.add_argument("--log", "-l",
                        type=str,
                        help='Log file path')
    parser.add_argument("--quiet", "-q",
                        action="store_true",
                        help="Do not print testing information to stderr in real time.")
    parser.add_argument("--verbose", "-v",
                        action="store_true",
                        help="Do not silence Hail logging to standard output.")
    parser.add_argument("--output", "-o",
                        type=str,
                        help="Output file path.")
    parser.add_argument("--data-dir", "-d",
                        type=str,
                        help="Data directory.")
    parser.add_argument('--timeout',
                        type=int,
                        default=1800,
                        help="Timeout in seconds after which benchmarks will be interrupted")
    parser.add_argument('--dry-run',
                        action='store_true',
                        help='Print benchmarks to execute, but do not run.')

    args = parser.parse_args(args_)

    init_logging()

    records = []

    def handler(stats):
        records.append(stats)

    data_dir = args.data_dir or os.environ.get('HAIL_BENCHMARK_DIR') or '/tmp/hail_benchmark_data'
    config = RunConfig(args.n_iter, handler, noisy=not args.quiet, timeout=args.timeout, dry_run=args.dry_run,
                       data_dir=data_dir, cores=args.cores, verbose=args.verbose, log=args.log)
    if args.tests:
        run_list(args.tests.split(','), config)
    if args.pattern:
        run_pattern(args.pattern, config)
    if not args.pattern and not args.tests:
        run_all(config)

    if args.dry_run:
        return

    data = {'config': {'cores': args.cores,
                       'version': hl.__version__,
                       'timestamp': str(datetime.datetime.now()),
                       'system': sys.platform},
            'benchmarks': records}
    if args.output:
        with open(args.output, 'w') as out:
            json.dump(data, out)
    else:
        print(json.dumps(data))