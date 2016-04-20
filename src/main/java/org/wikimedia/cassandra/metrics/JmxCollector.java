/* Copyright 2016 Eric Evans <eevans@wikimedia.org>
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

import org.wikimedia.cassandra.metrics.JmxSample.Type;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;


public abstract class JmxCollector implements AutoCloseable {

    static final String FORMAT_URL = "service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi";

    protected static Map<String, Class<?>> mbeanClasses = Maps.newHashMap();
    protected static Set<ObjectName> blacklist = Sets.newHashSet();
    protected final ObjectName metricsObjectName = newObjectName("org.apache.cassandra.metrics:*");

    private JMXConnector jmxc;
    private MBeanServerConnection mbeanServerConn;

    protected JmxCollector() throws IOException {
        this(DEFAULT_JMX_HOST);
    }

    protected JmxCollector(String host) throws IOException {
        this(host, DEFAULT_JMX_PORT);
    }

    protected JmxCollector(String host, int port) throws IOException {
        JMXServiceURL jmxUrl;
        try {
            jmxUrl = new JMXServiceURL(String.format(FORMAT_URL, host, port));
        }
        catch (MalformedURLException e) {
            throw new IllegalArgumentException(e.getMessage());
        }

        connect(jmxUrl);
    }

    protected JmxCollector(JMXServiceURL jmxUrl) throws IOException {
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

    public abstract void getCassandraSamples(SampleVisitor visitor) throws IOException;

    @Override
    public void close() throws IOException {
        this.jmxc.close();
    }

    @Override
    public String toString() {
        return "JmxCollector [metricsObjectName=" + metricsObjectName + ", jmxc=" + jmxc + ", mbeanServerConn="
                + mbeanServerConn + "]";
    }

    MBeanServerConnection getConnection() {
        return this.mbeanServerConn;
    }

    Object getMBeanProxy(ObjectInstance instance) {
        return JMX.newMBeanProxy(getConnection(), instance.getObjectName(), mbeanClasses.get(instance.getClassName()));
    }

    /* TODO: Ideally, the "interesting" criteria should be configurable. */
    protected static Set<String> interestingTypes = Sets.newHashSet(
            "Cache",
            "ClientRequest",
            "ColumnFamily",
            "Connection",
            "CQL",
            "DroppedMessage",
            "FileCache",
            "IndexColumnFamily",
            "Storage",
            "ThreadPools",
            "Compaction",
            "ReadRepair",
            "CommitLog");

    /* XXX: This is a hot mess. */
    protected boolean interesting(ObjectName objName) {
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

    protected <T> T newPlatformMXBeanProxy(String domainType, String key, String val, Class<T> cls) throws IOException {
        return ManagementFactory.newPlatformMXBeanProxy(getConnection(), String.format("%s,%s=%s", domainType, key, val), cls); 
    }

    /**
     * An {@link ObjectName} factory that throws unchecked exceptions for a malformed name.  This is a convenience method
     * to avoid exception handling for {@link ObjectName} instantiation with constants.
     * 
     * @param name and object name
     * @return the ObjectName instance corresponding to name
     */
    protected static ObjectName newObjectName(String name) {
        try {
            return new ObjectName(name);
        }
        catch (MalformedObjectNameException e) {
            throw new RuntimeException("a bug!", e);
        }
    }

    // Copy-pasta from o.a.cassandra.tools.NodeProbe
    protected double[] metricPercentilesAsArray(long[] counts)
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

}
