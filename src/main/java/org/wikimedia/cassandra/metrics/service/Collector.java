/* Copyright 2015 Eric Evans <eevans@wikimedia.org> and Wikimedia Foundation
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
package org.wikimedia.cassandra.metrics.service;

import static org.wikimedia.cassandra.metrics.service.Collector.Status.ERROR;
import static org.wikimedia.cassandra.metrics.service.Collector.Status.FAILURE;
import static org.wikimedia.cassandra.metrics.service.Collector.Status.SUCCESS;

import java.io.IOException;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikimedia.cassandra.metrics.CarbonException;
import org.wikimedia.cassandra.metrics.CarbonVisitor;
import org.wikimedia.cassandra.metrics.Discovery;
import org.wikimedia.cassandra.metrics.Filter;
import org.wikimedia.cassandra.metrics.JmxCollector;
import org.wikimedia.cassandra.metrics.JmxCollectorFactory;

import com.google.common.base.Optional;

public class Collector implements Job {
    public static enum Status {
        SUCCESS, FAILURE, ERROR;
    }

    private static final Logger LOG = LoggerFactory.getLogger(Collector.class);

    private Discovery.Jvm jvm;
    private String carbonHost;
    private int carbonPort;
    private String instanceName;
    private Optional<Filter> filter;
    private Status status = FAILURE;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        LOG.debug("Connection to {}", this.jvm.getJmxUrl());

        try (JmxCollector c = JmxCollectorFactory.create(this.jvm.getJmxUrl())) {
            LOG.debug("Connected to {}", this.jvm.getJmxUrl());
            LOG.debug("Connecting to {}:{}", this.carbonHost, this.carbonPort);

            try (CarbonVisitor v = new CarbonVisitor(this.carbonHost, this.carbonPort, prefix(this.instanceName), filter)) {
                LOG.debug("Collecting...");
                c.getSamples(v);
            }
            catch (CarbonException e) {
                LOG.error("Carbon Error", e);
                this.status = FAILURE;
                return;
            }
        }
        catch (IOException e) {
            LOG.error("JMX collection error", e);
            this.status = ERROR;
            return;
        }

        LOG.info("Collection of {} complete; Samples written to {}:{}", this.instanceName, this.carbonHost, this.carbonPort);
        this.status = SUCCESS;
        return;
    }

    public Status getStatus() {
        return this.status;
    }

    public String getInstanceName() {
        return this.instanceName;
    }

    public Discovery.Jvm getJvm() {
        return this.jvm;
    }
    
    public void setJvm(Discovery.Jvm jvm) {
        this.jvm = jvm;
    }
    
    public void setCarbonHost(String carbonHost) {
        this.carbonHost = carbonHost;
    }

    public void setCarbonPort(int carbonPort) {
        this.carbonPort = carbonPort;
    }

    public void setInstanceName(String instanceName) {
        this.instanceName = instanceName;
    }

    public void setFilter(Object filter) {
        this.filter = (filter != null) ? Optional.of((Filter) filter) : Optional.<Filter> absent();
    }

    @Override
    public String toString() {
        return "Collector [jvm=" + jvm + ", carbonHost=" + carbonHost + ", carbonPort=" + carbonPort + ", instanceName="
                + instanceName + ", filter=" + filter + ", status=" + status + "]";
    }

    private static String prefix(String id) {
        return String.format("%s.%s", Service.PREFIX_PREFIX, id);
    }

}