cassandra-metrics-collector [![Build Status](https://travis-ci.org/wikimedia/cassandra-metrics-collector.svg?branch=master)](https://travis-ci.org/wikimedia/cassandra-metrics-collector)
===========================

Discovers running instances of Cassandra on the local machine, collects
performance metrics (via JMX), and writes them to
[Graphite](https://github.com/graphite-project/graphite-web)/[Carbon](https://github.com/graphite-project/carbon)
in a format compatible with the [Dropwizard metrics](http://metrics.dropwizard.io)
GraphiteReporter.

This tool is capable of discovering and collecting from an arbitrary number
of local Cassandra instances, and so it is required that you disambiguate
instances by passing a `-Dcassandra.instance-id=<name>` system property to
Cassandra at startup.  This instance name will be used to derive the metric
prefix string unique to each instance, (in the form of `cassandra.<name>.`).

Build
-----
    $ mvn package

Run
---
    $ java -jar target/cassandra-metrics-collector-<version>-jar-with-dependencies.jar --help
    
    NAME
                cmcd - cassandra-metrics-collector daemon
    
    SYNOPSIS
            cmcd [ {-h | --help} ] [ {-H | --carbon-host} <HOSTNAME> ]
                            [ {-i | --interval} <INTERVAL> ] [ {-p | --carbon-port} <PORT> ]
    
    OPTIONS
            -h, --help
                        Display help information
    
            -H <HOSTNAME>, --carbon-host <HOSTNAME>
                        Carbon hostname (default: localhost)
    
            -i <INTERVAL>, --interval <INTERVAL>
                        Collection interval in seconds (default: 60 seconds)
    
            -p <PORT>, --carbon-port <PORT>
                        Carbon port number (default: 2003)

For example:
    
    $ java -jar cassandra-metrics-collector-<version>-jar-with-dependencies.jar \
            --interval 60 \
            --carbon-host carbon-1.example.com \
            --carbon-port 2003 \


Simple invocation
-----------------
It is also possible to invoke a single collection cycle against a specific
instance over TCP.

    $ java -cp target/cassandra-metrics-collector-<version>-jar-with-dependencies.jar org.wikimedia.cassandra.metrics.Command
    Usage: Command <jmx host> <jmx port> <graphite host> <graphite port> <prefix>

For example:

    $ java -cp target/cassandra-metrics-collector-<version>-jar-with-dependencies.jar org.wikimedia.cassandra.metrics.Command \
          db-1.example.com \
          7199 \
          carbon-1.example.com \
          2003 \
          cassandra.db-1


Testing locally
---------------
To test locally, use `nc` (netcat).

Open a socket and listen on port 2003:

    $ nc -lk 2003

Collect Cassandra metrics and write to netcat:

    $ java -jar cassandra-metrics-collector-<version>-jar-with-dependencies.jar \
          --interval 15 \
          --carbon-host localhost \
          --carbon-port 2003 \

Cassandra >= 2.2
----------------
Cassandra 2.2 broke backward compatibility by wrapping the
[Dropwizard metrics](http://metrics.dropwizard.io) mbeans in delegators,
(thus changing the name).  This service continues to default to Cassandra
2.1 for the time-being (mostly because that is what the Wikimedia
Foundation has standardized on).

To enable support for Cassandra 2.2 you must start the service with the
`-Dcassandra.version=v2_2` system property.  Additionally, since the uber
jar does not currently contain the needed Cassandra class files, you cannot
launch it as an executable jar, as described above.  To start the service
and collect metrics for a Cassandra 2.2 instance, invoke Java using a
classpath that contains your Cassandra 2.2.x jar file, and specify the
main class as an argument.  For example:

    $ export CLASSPATH=/path/to/apache-cassandra.jar:/path/to/cassandra-metrics-collector-<version>-jar-with-dependencies.jar
    $ java -Dcassandra.version=v2_2 org.wikimedia.cassandra.metrics.service.Service \
        --interval 15 \
        --carbon-host localhost \
        --carbon-port 2003

*Note: You must disable `-XX:+PerfDisableSharedMem` in `cassandra-env.sh` for JVM auto-discovery to work (this will be fixed in a future release).*
