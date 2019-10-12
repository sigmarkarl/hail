For Software Developers
-----------------------

Hail is an open-source project. We welcome contributions to the repository.

Requirements
~~~~~~~~~~~~

See the requirements for non-pip installations in `Getting Started
<getting_started.html>`_

Building Hail
~~~~~~~~~~~~~

The Hail source code is hosted `on GitHub <https://github.com/hail-is/hail>`_::

    git clone https://github.com/hail-is/hail.git
    cd hail/hail

By default, Hail uses pre-compiled native libraries that are compatible with
recent Mac OS X and Debian releases. If you're not using one of these OSes, set
the environment (or Make) variable `HAIL_COMPILE_NATIVES` to any value. This
variable tells GNU Make to build the native libraries from source.

Build and install a wheel file from source with local-mode ``pyspark``::

    make install HAIL_COMPILE_NATIVES=1

As above, but explicitly specifying the Spark version::

    make install HAIL_COMPILE_NATIVES=1 SPARK_VERSION=2.4.1

Building the Docs and Website
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Build without testing the documentation examples::

    make docs-no-test

Build while testing the documentation examples (significantly slower)::

    make docs

Serve the built website on http://localhost:8000/ ::

    (cd build/www && python3 -m http.server)


Running the tests
~~~~~~~~~~~~~~~~~

A couple Hail tests compare to PLINK 1.9 (not PLINK 2.0 [ignore the confusing
URL]):

 - `PLINK 1.9 <http://www.cog-genomics.org/plink2>`_

Execute every Hail test using at most 8 parallel threads::

    make -j8 test

Contributing
~~~~~~~~~~~~

Chat with the dev team on our `Zulip chatroom <https://hail.zulipchat.com>`_ if
you have an idea for a contribution. We can help you determine if your
project is a good candidate for merging.

Keep in mind the following principles when submitting a pull request:

- A PR should focus on a single feature. Multiple features should be split into multiple PRs.
- Before submitting your PR, you should rebase onto the latest master.
- PRs must pass all tests before being merged. See the section above on `Running the tests`_ locally.
- PRs require a review before being merged. We will assign someone from our dev team to review your PR.
- When you make a PR, include a short message that describes the purpose of the
  PR and any necessary context for the changes you are making.
