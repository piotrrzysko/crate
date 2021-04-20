===============
Test cheatsheet
===============

Run tests in a single module using multiple forks::

    $ ./gradlew --parallel -PtestForks=2 :sql:test

Run the all of `doctests`_::

    $ ./gradlew itest

For running itest on Windows, `WSL needs to be installed`_. If downloaded Linux
distro comes without Java, install it by running the following commands::

    $ sudo add-apt-repository ppa:openjdk-r/ppa
    $ sudo apt-get update
    $ sudo apt install openjdk-11-jdk.

Then install python virtual environment by running::

    $ sudo apt-get install python3-venv

To support symlinks, `enable Developer Mode`_ and run::

    $ git config --global core.symlinks true

After all configuration is done, launch WSL from the project directory and run::

    $ ./gradlew itest

Run the doctests for a specific file (e.g., ``filename.rst``):

    $ ITEST_FILE_NAME_FILTER=filename.rst ./gradlew itest

You can also ``export`` ``ITEST_FILE_NAME_FILTER`` to your shell environment
(e.g., export ITEST_FILE_NAME_FILTER=filename.rst``) if you want to set the
value for the remainder of your terminal session.

Filter tests::

    $ ./gradlew test -Dtest.single='YourTestClass'

    $ ./gradlew test --tests '*ClassName.testMethodName'

Extra options::

    $ ./gradlew :server:test -Dtests.seed=8352BE0120F826A9

    $ ./gradlew :server:test -Dtests.iters=20

    $ ./gradlew :server:test -Dtests.nightly=true # defaults to "false"

    $ ./gradlew :server:test -Dtests.verbose=true # log result of all invoked tests

More logging::

    $ ./gradlew -PtestLogging -Dtests.loggers.levels=io.crate:DEBUG,io.crate.planner.consumer.NestedLoopConsumer:TRACE :server:test

More logging by changing code:

Use ``@TestLogging(["<packageName1>:<logLevel1>", ...])`` on your test class or
test method to enable more detailed logging. For example::

    @TestLogging("io.crate:DEBUG,io.crate.planner.consumer.NestedLoopConsumer:TRACE")

.. _doctests: https://github.com/crate/crate/blob/master/blackbox/test_docs.py
.. _WSL needs to be installed: https://www.howtogeek.com/249966/how-to-install-and-use-the-linux-bash-shell-on-windows-10/
.. _enable Developer Mode: https://www.howtogeek.com/292914/what-is-developer-mode-in-windows-10/
