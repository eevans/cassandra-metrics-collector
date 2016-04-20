/* Copyright 2015-2016 Eric Evans <eevans@wikimedia.org>
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

import static org.wikimedia.cassandra.metrics.Constants.DEFAULT_JMX_HOST;
import static org.wikimedia.cassandra.metrics.Constants.DEFAULT_JMX_PORT;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;

import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.remote.JMXServiceURL;

import org.wikimedia.cassandra.metrics.JmxSample.Type;

import com.yammer.metrics.reporting.JmxReporter;


@Deprecated
public class JmxCollector21 extends JmxCollector {

    static {
        mbeanClasses = new HashMap<String, Class<?>>();
        mbeanClasses.put("com.yammer.metrics.reporting.JmxReporter$Gauge", JmxReporter.GaugeMBean.class);
        mbeanClasses.put("com.yammer.metrics.reporting.JmxReporter$Timer", JmxReporter.TimerMBean.class);
        mbeanClasses.put("com.yammer.metrics.reporting.JmxReporter$Counter", JmxReporter.CounterMBean.class);
        mbeanClasses.put("com.yammer.metrics.reporting.JmxReporter$Meter", JmxReporter.MeterMBean.class);
        mbeanClasses.put("com.yammer.metrics.reporting.JmxReporter$Histogram", JmxReporter.HistogramMBean.class);
        mbeanClasses.put("com.yammer.metrics.reporting.JmxReporter$Meter", JmxReporter.MeterMBean.class);

        blacklist = new HashSet<ObjectName>();
        blacklist.add(newObjectName("org.apache.cassandra.metrics:type=ColumnFamily,name=SnapshotsSize"));
        blacklist.add(newObjectName("org.apache.cassandra.metrics:type=ColumnFamily,keyspace=system,scope=compactions_in_progress,name=SnapshotsSize"));

    }

    JmxCollector21() throws IOException {
        super(DEFAULT_JMX_HOST);
    }

    JmxCollector21(String host) throws IOException {
        super(host, DEFAULT_JMX_PORT);
    }

    JmxCollector21(String host, int port) throws IOException {
        super(host, port);
    }

    JmxCollector21(JMXServiceURL jmxUrl) throws IOException {
        super(jmxUrl);
    }

    public void getCassandraSamples(SampleVisitor visitor) throws IOException {

        for (ObjectInstance instance : getConnection().queryMBeans(this.metricsObjectName, null)) {
            if (!interesting(instance.getObjectName()))
                continue;

            Object proxy = getMBeanProxy(instance);
            ObjectName oName = instance.getObjectName();

            int timestamp = (int) (System.currentTimeMillis() / 1000);

            // Order matters here (for example: TimerMBean extends MeterMBean)

            if (proxy instanceof JmxReporter.TimerMBean) {
                JmxReporter.TimerMBean timer = (JmxReporter.TimerMBean)proxy;
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

            if (proxy instanceof JmxReporter.MeterMBean) {
                JmxReporter.MeterMBean meter = (JmxReporter.MeterMBean)proxy;
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "15MinuteRate", meter.getFifteenMinuteRate(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "1MinuteRate", meter.getOneMinuteRate(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "5MinuteRate", meter.getFiveMinuteRate(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "count", meter.getCount(), timestamp));
                visitor.visit(new JmxSample(Type.CASSANDRA, oName, "meanRate", meter.getMeanRate(), timestamp));
                continue;
            }

            if (proxy instanceof JmxReporter.HistogramMBean) {
                JmxReporter.HistogramMBean histogram = (JmxReporter.HistogramMBean)proxy;
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

            if (proxy instanceof JmxReporter.GaugeMBean) {
                // EstimatedRowSizeHistogram and EstimatedColumnCountHistogram are allegedly Gauge, but with a value
                // of type of long[], we're left with little choice but to special-case them.  This borrows code from
                // Cassandra to decode the array into a histogram (50p, 75p, 95p, 98p, 99p, min, and max).
                String name = oName.getKeyProperty("name");
                if (name.equals("EstimatedRowSizeHistogram") || name.equals("EstimatedColumnCountHistogram")) {
                    Object value = ((JmxReporter.GaugeMBean) proxy).getValue();
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
                            ((JmxReporter.GaugeMBean) proxy).getValue(),
                            timestamp));
                }
                continue;
            }

            if (proxy instanceof JmxReporter.CounterMBean) {
                visitor.visit(new JmxSample(
                        Type.CASSANDRA,
                        oName,
                        "count",
                        ((JmxReporter.CounterMBean) proxy).getCount(),
                        timestamp));
                continue;
            }
        }

    }

    public static void main(String... args) throws IOException, Exception {

        try (JmxCollector21 collector = new JmxCollector21("localhost", 7100)) {
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
