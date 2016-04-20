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

import java.io.IOException;
import java.net.MalformedURLException;

import javax.management.remote.JMXServiceURL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JmxCollectorFactory {

    public static enum CassandraVersion {
        V2_1, V2_2;
    }

    public static final CassandraVersion DEFAULT_VERSION = CassandraVersion.V2_1;

    private static final Logger LOG = LoggerFactory.getLogger(JmxCollectorFactory.class);

    private static CassandraVersion versionFromSystemProperty() {
        String property = System.getProperty("cassandra.version");
        try {
            return (property != null) ? CassandraVersion.valueOf(property.toUpperCase()) : DEFAULT_VERSION;
        }
        catch (IllegalArgumentException e) {
            LOG.warn(
                    "cassandra-version value {} unrecognized, using default of {} instead",
                    property,
                    DEFAULT_VERSION.toString());
        }
        return DEFAULT_VERSION;
    }

    public static JmxCollector create() throws IOException {
        return create(versionFromSystemProperty(), Constants.DEFAULT_JMX_HOST);
    }

    public static JmxCollector create(String host, int port) throws IOException {
        return create(versionFromSystemProperty(), host, port);
    }

    public static JmxCollector create(JMXServiceURL url) throws IOException {
        return create(versionFromSystemProperty(), url);
    }

    public static JmxCollector create(CassandraVersion version) throws IOException {
        return create(version, Constants.DEFAULT_JMX_HOST);
    }

    public static JmxCollector create(CassandraVersion version, String host) throws IOException {
        return create(version, host, Constants.DEFAULT_JMX_PORT);
    }

    public static JmxCollector create(CassandraVersion version, String host, int port) throws IOException {
        JMXServiceURL jmxUrl;
        try {
            jmxUrl = new JMXServiceURL(String.format(JmxCollector.FORMAT_URL, host, port));
        }
        catch (MalformedURLException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        return create(version, jmxUrl);
    }

    @SuppressWarnings("deprecation")
    public static JmxCollector create(CassandraVersion version, JMXServiceURL url) throws IOException {
        switch (version) {
        case V2_1:
            return new JmxCollector21(url);
        case V2_2:
            return new JmxCollector22(url);
        default:
            throw new RuntimeException("Unexpected version; A bug!");
        }
    }
}
