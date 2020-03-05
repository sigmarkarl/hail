.. _sec-docker-resources:

================
Docker Resources
================

What is Docker?
---------------
Docker is a tool for packaging up operating systems, scripts, and environments in order to
be able to run the same code regardless of what machine the code is executing on. This packaged
code is called an image. There are three parts to Docker: a mechanism for building images,
an image repository called DockerHub, and a way to execute code in an image
called a container. For using Pipeline effectively, we're only going to focus on building images.

Installation
------------

You can install Docker by following the instructions for either `Macs <https://docs.docker.com/docker-for-mac/install/>`_
or for `Linux <https://docs.docker.com/install/linux/docker-ce/ubuntu/>`_.


Creating a Dockerfile
---------------------

A Dockerfile contains the instructions for creating an image and is typically called `Dockerfile`.
The first directive at the top of each Dockerfile is `FROM` which states what image to create this
image on top of. For example, we can build off of `ubuntu:18.04` which contains a complete Ubuntu
operating system, but does not have Python installed by default. You can use any image that already
exists to base your image on. An image that has Python preinstalled is `python:3.6-slim-stretch` and
one that has gsutil installed is `google/cloud-sdk:slim`. Be careful when choosing images from unknown
sources!

In the example below, we create a Dockerfile that is based on `ubuntu:18.04`. In this file, we show an
example of installing PLINK in the image with the `RUN` directive, which is an arbitrary bash command.
First, we download a bunch of utilities that do not come with Ubuntu using `apt-get`. Next, we
download and install PLINK from source. Finally, we can copy files from your local computer to the
docker image using the `COPY` directive.


.. code-block:: text

    FROM 'ubuntu:18.04'

    RUN apt-get update && apt-get install -y \
        python3 \
        python3-pip \
        tar \
        wget \
        unzip \
        && \
        rm -rf /var/lib/apt/lists/*

    RUN mkdir plink && \
        (cd plink && \
         wget http://s3.amazonaws.com/plink1-assets/plink_linux_x86_64_20200217.zip && \
         unzip plink_linux_x86_64_20200217.zip && \
         rm -rf plink_linux_x86_64_20200217.zip)

    # copy single script
    COPY my_script.py /scripts/

    # copy entire directory recursively
    COPY . /scripts/

For more information about Dockerfiles and directives that can be used see the following sources:

- https://docs.docker.com/develop/develop-images/dockerfile_best-practices/
- https://docs.docker.com/engine/reference/builder/


Building Images
---------------

To create a Docker image, we use a series of commands to build the image from a Dockerfile by specifying
the context directory (in this case the current directory `.`). The `-f` option
specifies what Dockerfile to read from. The `-t` option is the name of the image.
More in depth information can be found `here <https://docs.docker.com/engine/reference/commandline/build/>`_.

.. code-block:: sh

    docker build -t <my-image> -f Dockerfile .


Pushing Images
--------------

To use an image with Pipeline, you need to upload your image to a place where Pipeline can access it.
You can store images inside the `Google Container Registry <https://cloud.google.com/container-registry/docs/>`_ in
addition to Dockerhub. Below is an example of pushing the image to the Google Container Registry.
It's good practice to specify a tag that is unique for your image. If you don't tag your image, the default is
`latest`.

.. code-block:: sh

    docker tag <my-image> <tag>
    docker push gcr.io/<my-project>/<my-image>:<tag>


Now you can use your Docker image with Pipeline to run your code with the method :meth:`.Task.image`
specifying the image as `gcr.io/<my-project>/<my-image>:<tag>`!
