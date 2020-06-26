import re

from .backend import ServiceBackend
from .resource import ResourceFile, ResourceGroup
from .utils import BatchException


def _add_resource_to_set(resource_set, resource, include_rg=True):
    if isinstance(resource, ResourceGroup):
        rg = resource
        if include_rg:
            resource_set.add(resource)
    else:
        resource_set.add(resource)
        if isinstance(resource, ResourceFile) and resource._has_resource_group():
            rg = resource._get_resource_group()
        else:
            rg = None

    if rg is not None:
        for _, resource_file in rg._resources.items():
            resource_set.add(resource_file)


class Job:
    """
    Object representing a single job to execute.

    Examples
    --------

    Create a batch object:

    >>> b = Batch()

    Create a new job that prints hello to a temporary file `t.ofile`:

    >>> j = b.new_job()
    >>> j.command(f'echo "hello" > {j.ofile}')

    Write the temporary file `t.ofile` to a permanent location

    >>> b.write_output(j.ofile, 'hello.txt')

    Execute the DAG:

    >>> b.run()

    Notes
    -----
    This class should never be created directly by the user. Use `Batch.new_job` instead.
    """

    _counter = 0
    _uid_prefix = "__JOB__"
    _regex_pattern = r"(?P<JOB>{}\d+)".format(_uid_prefix)

    @classmethod
    def _new_uid(cls):
        uid = "{}{}".format(cls._uid_prefix, cls._counter)
        cls._counter += 1
        return uid

    def __init__(self, batch, name=None, attributes=None):
        self._batch = batch
        self.name = name
        self.attributes = attributes
        self._cpu = None
        self._memory = None
        self._storage = None
        self._image = None
        self._always_run = False
        self._timeout = None
        self._command = []

        self._resources = {}  # dict of name to resource
        self._resources_inverse = {}  # dict of resource to name
        self._uid = Job._new_uid()

        self._inputs = set()
        self._internal_outputs = set()
        self._external_outputs = set()
        self._mentioned = set()  # resources used in the command
        self._valid = set()  # resources declared in the appropriate place
        self._dependencies = set()

    def _get_resource(self, item):
        if item not in self._resources:
            r = self._batch._new_job_resource_file(self)
            self._resources[item] = r
            self._resources_inverse[r] = item

        return self._resources[item]

    def __getitem__(self, item):
        return self._get_resource(item)

    def __getattr__(self, item):
        return self._get_resource(item)

    def _add_internal_outputs(self, resource):
        _add_resource_to_set(self._internal_outputs, resource, include_rg=False)

    def _add_inputs(self, resource):
        _add_resource_to_set(self._inputs, resource, include_rg=False)

    def declare_resource_group(self, **mappings):
        """
        Declare a resource group for a job.

        Examples
        --------

        Declare a resource group:

        >>> b = Batch()
        >>> input = b.read_input_group(bed='data/example.bed',
        ...                            bim='data/example.bim',
        ...                            fam='data/example.fam')
        >>> j = b.new_job()
        >>> j.declare_resource_group(tmp1={'bed': '{root}.bed',
        ...                                'bim': '{root}.bim',
        ...                                'fam': '{root}.fam',
        ...                                'log': '{root}.log'})
        >>> j.command(f'plink --bfile {input} --make-bed --out {j.tmp1}')
        >>> b.run()  # doctest: +SKIP

        Warning
        -------
        Be careful when specifying the expressions for each file as this is Python
        code that is executed with `eval`!

        Parameters
        ----------
        mappings: :obj:`dict` of :obj:`str` to :obj:`dict` of :obj:`str` to :obj:`str`
            Keywords are the name(s) of the resource group(s). The value is a dict
            mapping the individual file identifier to a string expression representing
            how to transform the resource group root name into a file. Use `{root}`
            for the file root.

        Returns
        -------
        :class:`.Job`
            Same job object with resource groups set.
        """

        for name, d in mappings.items():
            assert name not in self._resources
            if not isinstance(d, dict):
                raise BatchException(f"value for name '{name}' is not a dict. Found '{type(d)}' instead.")
            rg = self._batch._new_resource_group(self, d)
            self._resources[name] = rg
            _add_resource_to_set(self._valid, rg)
        return self

    def depends_on(self, *jobs):
        """
        Explicitly set dependencies on other jobs.

        Examples
        --------

        Initialize the batch:

        >>> b = Batch()

        Create the first job:

        >>> j1 = b.new_job()
        >>> j1.command(f'echo "hello"')

        Create the second job `j2` that depends on `j1`:

        >>> j2 = b.new_job()
        >>> j2.depends_on(j1)
        >>> j2.command(f'echo "world"')

        Execute the batch:

        >>> b.run()

        Notes
        -----
        Dependencies between jobs are automatically created when resources from
        one job are used in a subsequent job. This method is only needed when
        no intermediate resource exists and the dependency needs to be explicitly
        set.

        Parameters
        ----------
        jobs: :class:`.Job`, varargs
            Sequence of jobs to depend on.

        Returns
        -------
        :class:`.Job`
            Same job object with dependencies set.
        """

        for j in jobs:
            self._dependencies.add(j)
        return self

    def command(self, command):
        """
        Set the job's command to execute.

        Examples
        --------

        Simple job with no output files:

        >>> b = Batch()
        >>> j = b.new_job()
        >>> j.command(f'echo "hello"')
        >>> b.run()

        Simple job with one temporary file `j.ofile` that is written to a
        permanent location:

        >>> b = Batch()
        >>> j = b.new_job()
        >>> j.command(f'echo "hello world" > {j.ofile}')
        >>> b.write_output(j.ofile, 'output/hello.txt')
        >>> b.run()

        Two jobs with a file interdependency:

        >>> b = Batch()
        >>> j1 = b.new_job()
        >>> j1.command(f'echo "hello" > {j1.ofile}')
        >>> j2 = b.new_job()
        >>> j2.command(f'cat {j1.ofile} > {j2.ofile}')
        >>> b.write_output(j2.ofile, 'output/cat_output.txt')
        >>> b.run()

        Specify multiple commands in the same job:

        >>> b = Batch()
        >>> t = b.new_job()
        >>> j.command(f'echo "hello" > {j.tmp1}')
        >>> j.command(f'echo "world" > {j.tmp2}')
        >>> j.command(f'echo "!" > {j.tmp3}')
        >>> j.command(f'cat {j.tmp1} {j.tmp2} {j.tmp3} > {j.ofile}')
        >>> b.write_output(j.ofile, 'output/concatenated.txt')
        >>> b.run()

        Notes
        -----
        This method can be called more than once. It's behavior is to
        append commands to run to the set of previously defined commands
        rather than overriding an existing command.

        To declare a resource file of type :class:`.JobResourceFile`, use either
        the get attribute syntax of `job.{identifier}` or the get item syntax of
        `job['identifier']`. If an object for that identifier doesn't exist,
        then one will be created automatically (only allowed in the :meth:`.command`
        method). The identifier name can be any valid Python identifier
        such as `ofile5000`.

        All :class:`.JobResourceFile` are temporary files and must be written
        to a permanent location using :func:`.Batch.write_output` if the output needs
        to be saved.

        Only resources can be referred to in commands. Referencing a :class:`.Batch`
        or :class:`.Job` will result in an error.

        Parameters
        ----------
        command: :obj:`str`

        Returns
        -------
        :class:`.Job`
            Same job object with command appended.
        """

        def handler(match_obj):
            groups = match_obj.groupdict()
            if groups['JOB']:
                raise BatchException(f"found a reference to a Job object in command '{command}'.")
            if groups['BATCH']:
                raise BatchException(f"found a reference to a Batch object in command '{command}'.")

            assert groups['RESOURCE_FILE'] or groups['RESOURCE_GROUP']
            r_uid = match_obj.group()
            r = self._batch._resource_map.get(r_uid)
            if r is None:
                raise BatchException(f"undefined resource '{r_uid}' in command '{command}'.\n"
                                     f"Hint: resources must be from the same batch as the current job.")
            if r._source != self:
                self._add_inputs(r)
                if r._source is not None:
                    if r not in r._source._valid:
                        name = r._source._resources_inverse[r]
                        raise BatchException(f"undefined resource '{name}'\n"
                                             f"Hint: resources must be defined within "
                                             f"the job methods 'command' or 'declare_resource_group'")
                    self._dependencies.add(r._source)
                    r._source._add_internal_outputs(r)
            else:
                _add_resource_to_set(self._valid, r)
            self._mentioned.add(r)
            return f"${{{r_uid}}}"

        from .batch import Batch  # pylint: disable=cyclic-import

        subst_command = re.sub(f"({ResourceFile._regex_pattern})|({ResourceGroup._regex_pattern})"
                               f"|({Job._regex_pattern})|({Batch._regex_pattern})",
                               handler,
                               command)
        self._command.append(subst_command)
        return self

    def storage(self, storage):
        """
        Set the job's storage size.

        Examples
        --------

        Set the job's disk requirements to 1 Gi:

        >>> b = Batch()
        >>> j = b.new_job()
        >>> (j.storage('1Gi')
        ...   .command(f'echo "hello"'))
        >>> b.run()

        Notes
        -----

        The storage expression must be of the form {number}{suffix}
        where valid optional suffixes are *K*, *Ki*, *M*, *Mi*,
        *G*, *Gi*, *T*, *Ti*, *P*, and *Pi*. Omitting a suffix means
        the value is in bytes.

        Parameters
        ----------
        storage: :obj:`str` or :obj:`int`
            Units are in bytes if `storage` is an :obj:`int`.

        Returns
        -------
        :class:`.Job`
            Same job object with storage set.
        """

        self._storage = str(storage)
        return self

    def memory(self, memory):
        """
        Set the job's memory requirements.

        Examples
        --------

        Set the job's memory requirement to be 3Gi:

        >>> b = Batch()
        >>> j = b.new_job()
        >>> (j.memory('3Gi')
        ...   .command(f'echo "hello"'))
        >>> b.run()

        Notes
        -----

        The memory expression must be of the form {number}{suffix}
        where valid optional suffixes are *K*, *Ki*, *M*, *Mi*,
        *G*, *Gi*, *T*, *Ti*, *P*, and *Pi*. Omitting a suffix means
        the value is in bytes.

        Parameters
        ----------
        memory: :obj:`str` or :obj:`int`
            Units are in bytes if `memory` is an :obj:`int`.

        Returns
        -------
        :class:`.Job`
            Same job object with memory requirements set.
        """

        self._memory = str(memory)
        return self

    def cpu(self, cores):
        """
        Set the job's CPU requirements.

        Notes
        -----

        The string expression must be of the form {number}{suffix}
        where the optional suffix is *m* representing millicpu.
        Omitting a suffix means the value is in cpu.

        Examples
        --------

        Set the job's CPU requirement to 250 millicpu:

        >>> b = Batch()
        >>> j = b.new_job()
        >>> (j.cpu('250m')
        ...   .command(f'echo "hello"'))
        >>> b.run()

        Parameters
        ----------
        cores: :obj:`str` or :obj:`int` or :obj:`float`
            Units are in cpu if `cores` is numeric.

        Returns
        -------
        :class:`.Job`
            Same job object with CPU requirements set.
        """

        self._cpu = str(cores)
        return self

    def image(self, image):
        """
        Set the job's docker image.

        Examples
        --------

        Set the job's docker image to `ubuntu:18.04`:

        >>> b = Batch()
        >>> j = b.new_job()
        >>> (j.image('ubuntu:18.04')
        ...   .command(f'echo "hello"'))
        >>> b.run()  # doctest: +SKIP

        Parameters
        ----------
        image: :obj:`str`
            Docker image to use.

        Returns
        -------
        :class:`.Job`
            Same job object with docker image set.
        """

        self._image = image
        return self

    def always_run(self, always_run=True):
        """
        Set the job to always run, even if dependencies fail.

        Notes
        -----
        Can only be used with the :class:`.ServiceBackend`.

        Warning
        -------
        Jobs set to always run are not cancellable!

        Examples
        --------

        >>> b = Batch(backend=ServiceBackend('test'))
        >>> j = b.new_job()
        >>> (j.always_run()
        ...   .command(f'echo "hello"'))

        Parameters
        ----------
        always_run: :obj:`bool`
            If True, set job to always run.

        Returns
        -------
        :class:`.Job`
            Same job object set to always run.
        """

        if not isinstance(self._batch._backend, ServiceBackend):
            raise NotImplementedError("A ServiceBackend is required to use the 'always_run' option")

        self._always_run = always_run
        return self

    def timeout(self, timeout):
        """
        Set the maximum amount of time this job can run for.

        Notes
        -----
        Can only be used with the :class:`.ServiceBackend`.

        Examples
        --------

        >>> b = Batch(backend=ServiceBackend('test'))
        >>> j = b.new_job()
        >>> (j.timeout(10)
        ...   .command(f'echo "hello"'))

        Parameters
        ----------
        timeout: :obj:`float` or :obj:`int`
            Maximum amount of time for a job to run before being killed.

        Returns
        -------
        :class:`.Job`
            Same job object set with a timeout.
        """

        if not isinstance(self._batch._backend, ServiceBackend):
            raise NotImplementedError("A ServiceBackend is required to use the 'timeout' option")

        self._timeout = timeout
        return self

    def _pretty(self):
        s = f"Job '{self._uid}'" \
            f"\tName:\t'{self.name}'" \
            f"\tAttributes:\t'{self.attributes}'" \
            f"\tImage:\t'{self._image}'" \
            f"\tCPU:\t'{self._cpu}'" \
            f"\tMemory:\t'{self._memory}'" \
            f"\tStorage:\t'{self._storage}'" \
            f"\tCommand:\t'{self._command}'"
        return s

    def __str__(self):
        return self._uid
