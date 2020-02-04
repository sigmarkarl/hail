import abc
import logging
import os
from urllib.request import urlretrieve
import subprocess

import hail as hl

gs_curl_root = 'https://storage.googleapis.com/hail-common/benchmark'


def download(data_dir, filename):
    url = os.path.join(gs_curl_root, filename)
    logging.info(f'downloading: {filename}')
    # Note: the below does not work on batch due to docker/ssl problems
    # dest = os.path.join(data_dir, filename)
    # urlretrieve(url, dest)
    subprocess.check_call(['wget', url, f'--directory-prefix={data_dir}', '-nv'])
    logging.info(f'done: {filename}')


class ResourceGroup(object, metaclass=abc.ABCMeta):
    def __init__(self, *files):
        self.files = files

    def exists(self, data_dir):
        name = self.name()
        resource_dir = os.path.join(data_dir, name)
        all_present = True
        for file in self.files:
            if os.path.exists(os.path.join(resource_dir, file)):
                logging.info(f'{name}: {file}: up to date')
            else:
                logging.info(f'{name}: {file}: missing')
                all_present = False
        if all_present:
            logging.info(f'{name}: all files up to date')
        else:
            logging.info(f'{name}: requires recreation')
        return all_present

    def create(self, data_dir):
        resource_dir = os.path.join(data_dir, self.name())
        os.makedirs(resource_dir, exist_ok=True)
        self._create(resource_dir)

    @abc.abstractmethod
    def _create(self, data_dir):
        pass

    def handle(self, resource=None):
        return self, lambda data_dir: os.path.join(data_dir, self.name(), self.path(resource))

    @abc.abstractmethod
    def path(self, resource):
        pass

    @abc.abstractmethod
    def name(self):
        pass


class Profile25(ResourceGroup):
    def __init__(self):
        super(Profile25, self).__init__('profile.vcf.bgz', 'profile.mt')

    def name(self):
        return 'profile25'

    def _create(self, data_dir):
        download(data_dir, 'profile.vcf.bgz')
        logging.info('Importing profile VCF...')
        mt = hl.import_vcf(os.path.join(data_dir, 'profile.vcf.bgz'), min_partitions=16)
        mt.write(os.path.join(data_dir, 'profile.mt'), overwrite=True)
        logging.info('Done writing profile MT')

    def path(self, resource):
        if resource == 'mt':
            return 'profile.mt'
        elif resource == 'vcf':
            return 'profile.vcf.bgz'
        raise KeyError(resource)


class ManyPartitionsTables(ResourceGroup):
    def __init__(self):
        super(ManyPartitionsTables, self).__init__('table_10M_par_1000.ht', 'table_10M_par_100.ht',
                                                   'table_10M_par_10.ht')

    def name(self):
        return 'many_partitions_tables'

    def _create(self, resource_dir):

        def compatible_checkpoint(obj, path):
            obj.write(path, overwrite=True)
            return hl.read_table(path)

        ht = hl.utils.range_table(10_000_000, 1000).annotate(**{f'f_{i}': hl.rand_unif(0, 1) for i in range(5)})
        logging.info('Writing 1000-partition table...')
        ht = compatible_checkpoint(ht, os.path.join(resource_dir, 'table_10M_par_1000.ht'))
        logging.info('Writing 100-partition table...')
        ht = compatible_checkpoint(ht.repartition(100, shuffle=False), os.path.join(resource_dir, 'table_10M_par_100.ht'))
        logging.info('Writing 10-partition table...')
        ht.repartition(10, shuffle=False).write(os.path.join(resource_dir, 'table_10M_par_10.ht'), overwrite=True)
        logging.info('done writing many-partitions tables.')

    def path(self, resource):
        if resource not in (10, 100, 1000):
            raise KeyError(resource)
        return f'table_10M_par_{resource}.ht'


class GnomadDPSim(ResourceGroup):
    def __init__(self):
        super(GnomadDPSim, self).__init__('gnomad_dp_simulation.mt')

    def name(self):
        return 'gnomad_dp_sim'

    def _create(self, resource_dir):
        logging.info('creating gnomad_dp_simulation matrix table...')
        mt = hl.utils.range_matrix_table(n_rows=250_000, n_cols=1_000, n_partitions=32)
        mt = mt.annotate_entries(x=hl.int(hl.rand_unif(0, 4.5) ** 3))
        mt.write(os.path.join(resource_dir, 'gnomad_dp_simulation.mt'), overwrite=True)
        logging.info('done creating gnomad_dp_simulation matrix table.')

    def path(self, resource):
        if resource is not None:
            raise KeyError(resource)
        return 'gnomad_dp_simulation.mt'


class ManyStringsTable(ResourceGroup):
    def __init__(self):
        super(ManyStringsTable, self).__init__('many_strings_table.tsv.bgz', 'many_strings_table.ht')

    def name(self):
        return 'many_strings_table'

    def _create(self, resource_dir):
        download(resource_dir, 'many_strings_table.tsv.bgz')
        logging.info('importing many_strings_table.tsv.bgz...')
        hl.import_table(os.path.join(resource_dir, 'many_strings_table.tsv.bgz')) \
            .write(os.path.join(resource_dir, 'many_strings_table.ht'), overwrite=True)
        logging.info('done importing many_strings_table.tsv.bgz.')

    def path(self, resource):
        if resource == 'ht':
            return 'many_strings_table.ht'
        elif resource == 'tsv':
            return 'many_strings_table.tsv.bgz'
        raise KeyError(resource)


class ManyIntsTable(ResourceGroup):
    def __init__(self):
        super(ManyIntsTable, self).__init__('many_ints_table.tsv.bgz', 'many_ints_table.ht')

    def name(self):
        return 'many_ints_table'

    def _create(self, resource_dir):
        download(resource_dir, 'many_ints_table.tsv.bgz')
        logging.info('importing many_ints_table.tsv.bgz...')
        hl.import_table(os.path.join(resource_dir, 'many_ints_table.tsv.bgz'),
                        types={'idx': 'int',
                               **{f'i{i}': 'int' for i in range(5)},
                               **{f'array{i}': 'array<int>' for i in range(2)}}) \
            .write(os.path.join(resource_dir, 'many_ints_table.ht'), overwrite=True)
        logging.info('done importing many_ints_table.tsv.bgz.')

    def path(self, resource):
        if resource == 'ht':
            return 'many_ints_table.ht'
        elif resource == 'tsv':
            return 'many_ints_table.tsv.bgz'
        raise KeyError(resource)


class SimUKBB(ResourceGroup):
    def __init__(self):
        super(SimUKBB, self).__init__('sim_ukb.bgen', 'sim_ukb.sample', 'sim_ukb.bgen.idx2')

    def name(self):
        return 'sim_ukbb'

    def _create(self, resource_dir):
        bgen = 'sim_ukb.bgen'
        sample = 'sim_ukb.sample'
        download(resource_dir, bgen)
        download(resource_dir, sample)
        local_bgen = os.path.join(resource_dir, bgen)
        logging.info(f'indexing {bgen}...')
        hl.index_bgen(local_bgen)
        logging.info(f'done indexing {bgen}.')

    def path(self, resource):
        if resource == 'bgen':
            return 'sim_ukb.bgen'
        elif resource == 'sample':
            return 'sim_ukb.sample'


profile_25 = Profile25()
many_partitions_tables = ManyPartitionsTables()
gnomad_dp_sim = GnomadDPSim()
many_strings_table = ManyStringsTable()
many_ints_table = ManyIntsTable()
sim_ukbb = SimUKBB()

all_resources = profile_25, many_partitions_tables, gnomad_dp_sim, many_strings_table, many_ints_table, sim_ukbb

__all__ = ['profile_25',
           'many_partitions_tables',
           'gnomad_dp_sim',
           'many_strings_table',
           'many_ints_table',
           'sim_ukbb',
           'all_resources']
