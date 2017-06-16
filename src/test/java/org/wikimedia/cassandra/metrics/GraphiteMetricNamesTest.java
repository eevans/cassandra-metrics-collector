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
 */
package org.wikimedia.cassandra.metrics;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.util.Arrays;
import java.util.Collection;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.wikimedia.cassandra.metrics.JmxSample.Type;

@RunWith(Parameterized.class)
public class GraphiteMetricNamesTest {

    private final String prefix;
    private final JmxSample sample;
    private final String expected;

    public GraphiteMetricNamesTest(String prefix, JmxSample sample, String expected) {
        this.prefix = prefix;
        this.sample = sample;
        this.expected = expected;
    }

    @Test
    public void test() {
        assertThat(CarbonVisitor.metricName(this.sample, this.prefix), is(this.expected));
    }

    /** Test data. */
    @Parameters
    public static Collection<Object[]> data() throws MalformedObjectNameException {
        Object[][] data = new Object[7][2];
        String prefix = "cassandra.host";

        ObjectName oName = new ObjectName("org.apache.cassandra.metrics:type=Table,keyspace=system_traces,scope=events,name=MemtableLiveDataSize");
        data[0] = new Object[] {
                prefix,
                newSample(Type.CASSANDRA, oName, "bytes"),
                String.format("%s.org.apache.cassandra.metrics.Table.system_traces.events.MemtableLiveDataSize.bytes", prefix)
        };

        oName = new ObjectName("org.apache.cassandra.metrics:type=ThreadPools,path=internal,scope=Sampler,name=MaxPoolSize");
        data[1] = new Object[] {
                prefix,
                newSample(Type.CASSANDRA, oName, "pool size"),
                String.format("%s.org.apache.cassandra.metrics.ThreadPools.internal.Sampler.MaxPoolSize.pool-size", prefix)
        };

        oName = new ObjectName("org.apache.cassandra.metrics:type=Connection,scope=127.0.0.1,name=CommandCompletedTasks");
        data[2] = new Object[] {
                prefix,
                newSample(Type.CASSANDRA, oName, "count"),
                String.format("%s.org.apache.cassandra.metrics.Connection.127.0.0.1.CommandCompletedTasks.count", prefix)
        };

        data[3] = new Object[] {
                prefix,
                newSample(Type.JVM, new ObjectName("java.lang:type=Runtime"), "uptime"),
                String.format("%s.jvm.uptime", prefix)
        };

        data[4] = new Object[] {
                prefix,
                newSample(Type.JVM, new ObjectName("java.lang:type=Memory"), "usage"),
                String.format("%s.jvm.memory.usage", prefix)
        };

        data[5] = new Object[] {
                prefix,
                newSample(Type.JVM, new ObjectName("java.lang:type=GarbageCollector,name=G1 New"), "runs"),
                String.format("%s.jvm.gc.G1-New.runs", prefix)
        };

        data[6] = new Object[] {
                prefix,
                newSample(Type.JVM, new ObjectName("java.lang:type=MemoryPool"), "G1 New"),
                String.format("%s.jvm.memory.memory_pool_usages.G1-New", prefix)
        };

        return Arrays.asList(data);
    }

    private static JmxSample newSample(Type type, ObjectName oName, String metricName) {
        return new JmxSample(type, oName, metricName, Double.valueOf(1.0d), 1);
    }

}
