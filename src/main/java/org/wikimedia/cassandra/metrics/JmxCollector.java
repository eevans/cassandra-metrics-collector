/* Copyright 2015 Eric Evans <eevans@wikimedia.org>
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.wikimedia.cassandra.metrics;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.management.ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE;
import static java.lang.management.ManagementFactory.MEMORY_MXBEAN_NAME;
import static java.lang.management.ManagementFactory.MEMORY_POOL_MXBEAN_DOMAIN_TYPE;
import static java.lang.management.ManagementFactory.RUNTIME_MXBEAN_NAME;
import static org.wikimedia.cassandra.metrics.Constants.DEFAULT_JMX_HOST;
import static org.wikimedia.cassandra.metrics.Constants.DEFAULT_JMX_PORT;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.RuntimeMXBean;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.apache.cassandra.metrics.CassandraMetricsRegistry;
import org.wikimedia.cassandra.metrics.JmxSample.Type;

import com.google.common.collect.Sets;


public class JmxCollector implements AutoCloseable {

    private static final String FORMAT_URL = "service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi";

    private static final Map<String, Class<?>> mbeanClasses;
    private static final Set<ObjectName> blacklist;

    static {
        mbeanClasses = new HashMap<String, Class<?>>();
        mbeanClasses.put("org.apache.cassandra.metrics.CassandraMetricsRegistry$JmxGauge", CassandraMetricsRegistry.JmxGaugeMBean.class);
        mbeanClasses.put("org.apache.cassandra.metrics.CassandraMetricsRegistry$JmxTimer", CassandraMetricsRegistry.JmxTimerMBean.class);
        mbeanClasses.put("org.apache.cassandra.metrics.CassandraMetricsRegistry$JmxCounter", CassandraMetricsRegistry.JmxCounterMBean.class);
        mbeanClasses.put("org.apache.cassandra.metrics.CassandraMetricsRegistry$JmxMeter", CassandraMetricsRegistry.JmxMeterMBean.class);
        mbeanClasses.put("org.apache.cassandra.metrics.CassandraMetricsRegistry$JmxHistogram", CassandraMetricsRegistry.JmxHistogramMBean.class);

        blacklist = new HashSet<ObjectName>();
        blacklist.add(newObjectName("org.apache.cassandra.metrics:type=ColumnFamily,name=SnapshotsSize"));
        blacklist.add(newObjectName("org.apache.cassandra.metrics:type=ColumnFamily,keyspace=system,scope=compactions_in_progress,name=SnapshotsSize"));
        blacklist.add(newObjectName("org.apache.cassandra.metrics:type=Table,name=SnapshotsSize"));
        blacklist.add(newObjectName("org.apache.cassandra.metrics:type=Table,keyspace=system,scope=compactions_in_progress,name=SnapshotsSize"));

    }

    private final String hostname;
    private final int port;
    private final ObjectName metricsObjectName = newObjectName("org.apache.cassandra.metrics:*");

    private JMXConnector jmxc;
    private MBeanServerConnection mbeanServerConn;

    public JmxCollector() throws IOException {
        this(DEFAULT_JMX_HOST);
    }

    public JmxCollector(String host) throws IOException {
        this(host, DEFAULT_JMX_PORT);
    }

    public JmxCollector(String host, int port) throws IOException {
        this.hostname = checkNotNull(host, "host argument");
        this.port = checkNotNull(port, "port argument");

        JMXServiceURL jmxUrl;
        try {
            jmxUrl = new JMXServiceURL(String.format(FORMAT_URL, this.hostname, this.port));
        }
        catch (MalformedURLException e) {
            throw new IllegalArgumentException(e.getMessage());
        }

        connect(jmxUrl);
    }

    public JmxCollector(JMXServiceURL jmxUrl) throws IOException {
        this.hostname = jmxUrl.getHost();
        this.port = jmxUrl.getPort();

        connect(jmxUrl);
    }

    private void connect(JMXServiceURL jmxUrl) throws IOException {
        /* FIXME: add authentication support */
        Map<String, Object> env = new HashMap<String, Object>();
        this.jmxc = JMXConnectorFactory.connect(jmxUrl, env);
        this.mbeanServerConn = jmxc.getMBeanServerConnection();
    }

    public void getSamples(SampleVisitor visitor) throws IOException {
        getJvmSamples(visitor);
        getCassandraSamples(visitor);
    }

    public void getJvmSamples(SampleVisitor visitor) throws IOException {
        int timestamp = (int) (System.currentTimeMillis() / 1000);

        // Runtime
        RuntimeMXBean runtime = ManagementFactory.newPlatformMXBeanProxy(getConnection(), RUNTIME_MXBEAN_NAME, RuntimeMXBean.class);
        visitor.visit(new JmxSample(Type.JVM, newObjectName(RUNTIME_MXBEAN_NAME), "uptime", runtime.getUptime(), timestamp));

        // Memory
        MemoryMXBean memory = ManagementFactory.newPlatformMXBeanProxy(getConnection(), MEMORY_MXBEAN_NAME, MemoryMXBean.class);
        ObjectName oName = newObjectName(MEMORY_MXBEAN_NAME);
        double nonHeapUsed = ((double)memory.getNonHeapMemoryUsage().getUsed() / (double)memory.getNonHeapMemoryUsage().getCommitted());
        double heapUsed = ((double)memory.getHeapMemoryUsage().getUsed() / (double)memory.getHeapMemoryUsage().getCommitted());
        visitor.visit(new JmxSample(Type.JVM, oName, "non_heap_usage", nonHeapUsed, timestamp));
        visitor.visit(new JmxSample(Type.JVM, oName, "non_heap_usage_bytes", (double)memory.getNonHeapMemoryUsage().getUsed(), timestamp));
        visitor.visit(new JmxSample(Type.JVM, oName, "heap_usage", heapUsed, timestamp));

        // Garbage collection
        for (ObjectInstance instance : getConnection().queryMBeans(newObjectName("java.lang:type=GarbageCollector,name=*"), null)) {
            String name = instance.getObjectName().getKeyProperty("name");
            GarbageCollectorMXBean gc = newPlatformMXBeanProxy(GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE, "name", name, GarbageCollectorMXBean.class);
            visitor.visit(new JmxSample(Type.JVM, instance.getObjectName(), "runs", gc.getCollectionCount(), timestamp));
            visitor.visit(new JmxSample(Type.JVM, instance.getObjectName(), "time", gc.getCollectionTime(), timestamp));
        }

        // Memory pool usages
        for (ObjectInstance instance : getConnection().queryMBeans(newObjectName("java.lang:type=MemoryPool,name=*"), null)) {
            String name = instance.getObjectName().getKeyProperty("name");
            MemoryPoolMXBean memPool = newPlatformMXBeanProxy(MEMORY_POOL_MXBEAN_DOMAIN_TYPE, "name", name, MemoryPoolMXBean.class);
            visitor.visit(new JmxSample(Type.JVM, instance.getObjectName(), memPool.getName(), memPool.getUsage().getUsed(), timestamp));
        }

    }


    public void getCassandraSamples(SampleVisitor visitor) throws IOException {

        for (ObjectInstance instance : getConnection().queryMBeans(this.metricsObjectName, null)) {
            if (!interesting(instance.getObjectName()))
                continue;

            Object proxy = getMBeanProxy(instance);
            ObjectName oName = instance.getObjectName();

            int timestamp = (int) (System.currentTimeMillis() / 1000);

            // Order matters here (for example: TimerMBean extends MeterMBean)

            if (proxy instanceof CassandraMetricsRegistry.JmxTimerMBean) {
                CassandraMetricsRegistry.JmxTimerMBean timer = (CassandraMetricsRegistry.JmxTimerMBean)proxy;
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "50percentile", timer.get50thPercentile(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "75percentile", timer.get75thPercentile(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "95percentile", timer.get95thPercentile(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "98percentile", timer.get98thPercentile(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "99percentile", timer.get99thPercentile(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "999percentile", timer.get999thPercentile(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "1MinuteRate", timer.getOneMinuteRate(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "5MinuteRate", timer.getFiveMinuteRate(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "15MinuteRate", timer.getFifteenMinuteRate(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "count", timer.getCount(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "max", timer.getMax(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "mean", timer.getMean(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "meanRate", timer.getMeanRate(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "min", timer.getMin(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "stddev", timer.getStdDev(), timestamp));
                continue;
            }

            if (proxy instanceof CassandraMetricsRegistry.JmxMeterMBean) {
                CassandraMetricsRegistry.JmxMeterMBean meter = (CassandraMetricsRegistry.JmxMeterMBean)proxy;
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "15MinuteRate", meter.getFifteenMinuteRate(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "1MinuteRate", meter.getOneMinuteRate(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "5MinuteRate", meter.getFiveMinuteRate(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "count", meter.getCount(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "meanRate", meter.getMeanRate(), timestamp));
                continue;
            }

            if (proxy instanceof CassandraMetricsRegistry.JmxHistogramMBean) {
                CassandraMetricsRegistry.JmxHistogramMBean histogram = (CassandraMetricsRegistry.JmxHistogramMBean)proxy;
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "50percentile", histogram.get50thPercentile(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "75percentile", histogram.get75thPercentile(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "95percentile", histogram.get95thPercentile(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "98percentile", histogram.get98thPercentile(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "99percentile", histogram.get99thPercentile(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "999percentile", histogram.get999thPercentile(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "max", histogram.getMax(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "mean", histogram.getMean(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "min", histogram.getMin(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "stddev", histogram.getStdDev(), timestamp));
                continue;
            }

            if (proxy instanceof CassandraMetricsRegistry.JmxGaugeMBean) {
                // EstimatedRowSizeHistogram and EstimatedColumnCountHistogram are allegedly Gauge, but with a value
                // of type of long[], we're left with little choice but to special-case them.  This borrows code from
                // Cassandra to decode the array into a histogram (50p, 75p, 95p, 98p, 99p, min, and max).
                String name = oName.getKeyProperty("name");
                if (name.equals("EstimatedRowSizeHistogram") || name.equals("EstimatedColumnCountHistogram")) {
                    Object value = ((CassandraMetricsRegistry.JmxGaugeMBean) proxy).getValue();
                    double[] percentiles = metricPercentilesAsArray((long[])value);
                    visitor.visit(new JmxSample(Type.CASSANDRA, oName, "50percentile", percentiles[0], timestamp));
                    visitor.visit(new JmxSample(Type.CASSANDRA, oName, "75percentile", percentiles[1], timestamp));
                    visitor.visit(new JmxSample(Type.CASSANDRA, oName, "95percentile", percentiles[2], timestamp));
                    visitor.visit(new JmxSample(Type.CASSANDRA, oName, "98percentile", percentiles[3], timestamp));
                    visitor.visit(new JmxSample(Type.CASSANDRA, oName, "99percentile", percentiles[4], timestamp));
                    visitor.visit(new JmxSample(Type.CASSANDRA, oName, "min", percentiles[5], timestamp));
                    visitor.visit(new JmxSample(Type.CASSANDRA, oName, "max", percentiles[6], timestamp));
                }
                else {
                    visitor.visit(new JmxSample(
                            Type.CASSANDRA,
                            oName,
                            "value",
                            ((CassandraMetricsRegistry.JmxGaugeMBean) proxy).getValue(),
                            timestamp));
                }
                continue;
            }

            if (proxy instanceof CassandraMetricsRegistry.JmxCounterMBean) {
                visitor.visit(new JmxSample(
                        Type.CASSANDRA,
                        oName,
                        "count",
                        ((CassandraMetricsRegistry.JmxCounterMBean) proxy).getCount(),
                        timestamp));
                continue;
            }
        }

    }

    @Override
    public void close() throws IOException {
        this.jmxc.close();
    }

    @Override
    public String toString() {
        return "JmxCollector [hostname="
                + hostname
                + ", port="
                + port
                + ", jmxc="
                + jmxc
                + ", mbeanServerConn="
                + mbeanServerConn
                + ", metricsObjectName="
                + metricsObjectName
                + "]";
    }

    MBeanServerConnection getConnection() {
        return this.mbeanServerConn;
    }

    Object getMBeanProxy(ObjectInstance instance) {
        return JMX.newMBeanProxy(getConnection(), instance.getObjectName(), mbeanClasses.get(instance.getClassName()));
    }

    /* TODO: Ideally, the "interesting" criteria should be configurable. */
    private static Set<String> interestingTypes = Sets.newHashSet(
            "Cache",
            "Client",
            "ClientRequest",
            "ColumnFamily",
            "Connection",
            "CQL",
            "DroppedMessage",
            "FileCache",
            "IndexColumnFamily",
            "Storage",
            "Keyspace",
            "ThreadPools",
            "Compaction",
            "ReadRepair",
            "CommitLog",
            "Table");

    /* XXX: This is a hot mess. */
    private boolean interesting(ObjectName objName) {
        if (objName.getKeyProperty("name").equals("ColUpdateTimeDeltaHistogram")) {
            return false;
        }

        if (blacklist.contains(objName))
            return false;

        String type = objName.getKeyProperty("type");
        if (type != null && interestingTypes.contains(type)) {
            String keyspace = objName.getKeyProperty("keyspace");
            if (keyspace == null || !keyspace.startsWith("system"))
                return true;
        }

        return false;
    }

    private <T> T newPlatformMXBeanProxy(String domainType, String key, String val, Class<T> cls) throws IOException {
        return ManagementFactory.newPlatformMXBeanProxy(getConnection(), String.format("%s,%s=%s", domainType, key, val), cls); 
    }

    /**
     * An {@link ObjectName} factory that throws unchecked exceptions for a malformed name.  This is a convenience method
     * to avoid exception handling for {@link ObjectName} instantiation with constants.
     * 
     * @param name and object name
     * @return the ObjectName instance corresponding to name
     */
    private static ObjectName newObjectName(String name) {
        try {
            return new ObjectName(name);
        }
        catch (MalformedObjectNameException e) {
            throw new RuntimeException("a bug!", e);
        }
    }

    // Copy-pasta from o.a.cassandra.tools.NodeProbe
    private double[] metricPercentilesAsArray(long[] counts)
    {
        double[] result = new double[7];

        if (counts == null || counts.length == 0)
        {
            Arrays.fill(result, Double.NaN);
            return result;
        }

        double[] offsetPercentiles = new double[] { 0.5, 0.75, 0.95, 0.98, 0.99 };
        long[] offsets = new EstimatedHistogram(counts.length).getBucketOffsets();
        EstimatedHistogram metric = new EstimatedHistogram(offsets, counts);

        if (metric.isOverflowed())
        {
            System.err.println(String.format("EstimatedHistogram overflowed larger than %s, unable to calculate percentiles",
                                             offsets[offsets.length - 1]));
            for (int i = 0; i < result.length; i++)
                result[i] = Double.NaN;
        }
        else
        {
            for (int i = 0; i < offsetPercentiles.length; i++)
                result[i] = metric.percentile(offsetPercentiles[i]);
        }
        result[5] = metric.min();
        result[6] = metric.max();
        return result;
    }

    public static void main(String... args) throws IOException, Exception {

        try (JmxCollector collector = new JmxCollector("localhost", 7100)) {
            SampleVisitor visitor = new SampleVisitor() {
                @Override
                public void visit(JmxSample jmxSample) {
                    if (jmxSample.getObjectName().getKeyProperty("type").equals("ColumnFamily"))
                        System.err.printf("%s,%s=%s%n", jmxSample.getObjectName(), jmxSample.getMetricName(), jmxSample.getValue());
                }
            };
            collector.getSamples(visitor);
        }

    }

}
