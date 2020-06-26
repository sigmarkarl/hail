import pkg_resources
import sys

if sys.version_info < (3, 6):
    raise EnvironmentError('Hail requires Python 3.6, found {}.{}'.format(
        sys.version_info.major, sys.version_info.minor))

__pip_version__ = pkg_resources.resource_string(__name__, 'hail_pip_version').decode().strip()
del pkg_resources
del sys

__doc__ = r"""
    __  __     <>__
   / /_/ /__  __/ /
  / __  / _ `/ / /
 /_/ /_/\_,_/_/_/
===================

For API documentation, visit the website: www.hail.is

For help, visit either:
 - the forum (discuss.hail.is)
 - or our Zulip chatroom: https://hail.zulipchat.com

To report a bug, please open an issue: https://github.com/hail-is/hail/issues
"""

from .table import Table, GroupedTable, asc, desc
from .matrixtable import MatrixTable, GroupedMatrixTable
# F403 'from .expr import *' used; unable to detect undefined names
# F401 '.expr.*' imported but unused
from .expr import *  # noqa: F401,F403
from .genetics import *  # noqa: F401,F403
from .methods import *  # noqa: F401,F403
from . import expr
from . import genetics
from . import methods
from . import stats
from . import linalg
from . import plot
from . import experimental
from . import ir
from . import backend
from . import nd as _nd
from hail.expr import aggregators as agg
from hail.utils import Struct, Interval, hadoop_copy, hadoop_open, hadoop_ls, \
    hadoop_stat, hadoop_exists, hadoop_is_file, hadoop_is_dir, copy_log

from .context import init, stop, spark_context, default_reference, \
    get_reference, set_global_seed, _set_flags, _get_flags, \
    current_backend, debug_info, citation, cite_hail, cite_hail_bibtex, \
    version

scan = agg.aggregators.ScanFunctions({name: getattr(agg, name) for name in agg.__all__})

__all__ = [
    'init',
    'stop',
    'spark_context',
    'default_reference',
    'get_reference',
    'set_global_seed',
    '_set_flags',
    '_get_flags',
    'Table',
    'GroupedTable',
    'MatrixTable',
    'GroupedMatrixTable',
    'asc',
    'desc',
    'hadoop_open',
    'hadoop_copy',
    'hadoop_is_dir',
    'hadoop_is_file',
    'hadoop_stat',
    'hadoop_exists',
    'hadoop_ls',
    'copy_log',
    'Struct',
    'Interval',
    'agg',
    'scan',
    'genetics',
    'methods',
    'stats',
    'linalg',
    '_nd',
    'plot',
    'experimental',
    'ir',
    'backend',
    'current_backend',
    'debug_info',
    'citation',
    'cite_hail',
    'cite_hail_bibtex',
    'version'
]

__all__.extend(genetics.__all__)
__all__.extend(methods.__all__)

# don't overwrite builtins in `from hail import *`
import builtins

__all__.extend([x for x in expr.__all__ if not hasattr(builtins, x)])
del builtins

ir.register_functions()
ir.register_aggregators()

__version__ = None  # set in hail.init()

import warnings

warnings.filterwarnings('once', append=True)
del warnings
