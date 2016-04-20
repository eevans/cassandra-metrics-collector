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

import static org.quartz.SimpleScheduleBuilder.simpleSchedule;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;

import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikimedia.cassandra.metrics.Discovery;
import org.wikimedia.cassandra.metrics.JmxCollector;
import org.wikimedia.cassandra.metrics.JmxCollectorFactory;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;

public class Discover implements Job {

    private static final Logger LOG = LoggerFactory.getLogger(Discover.class);

    private InstanceCache instances;
    private Scheduler scheduler;
    private int interval;
    private String carbonHost;
    private int carbonPort;
    private Object filter;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            LOG.info("Initiating new discovery cycle", this);
            Collection<String> jobs = getCollectionJobs();

            for (Discovery.Jvm jvm : this.instances.discover()) {
                LOG.info("Found instance {}", jvm.getCassandraInstance());
                LOG.debug("Verifying JMX connectivity...");

                try (JmxCollector c = JmxCollectorFactory.create(jvm.getJmxUrl())) {
                    // I don't know how this would happen, so it probably will.
                    if (jobs.contains(jvm.getCassandraInstance())) {
                        LOG.warn("Discovered instance with a matching job ({}); What gives?", jvm.getCassandraInstance());
                        return;
                    }

                    JobDataMap dataMap = new JobDataMap();
                    dataMap.put("jvm", jvm);
                    dataMap.put("carbonHost", carbonHost);
                    dataMap.put("carbonPort", carbonPort);
                    dataMap.put("instanceName", jvm.getCassandraInstance());
                    dataMap.put("filter", filter);

                    JobDetail job = JobBuilder.newJob(Collector.class)
                            .withIdentity(jvm.getCassandraInstance(), "collectionGroup")
                            .usingJobData(dataMap)
                            .build();

                    LOG.debug("Scheduling recurring metrics collection for {}", jvm.getCassandraInstance());

                    this.scheduler.scheduleJob(job, newTrigger(jvm.getCassandraInstance(), this.interval));
                    this.instances.add(jvm.getCassandraInstance(), jvm);
                }
                catch (IOException e) {
                    LOG.error("Unable to verify JMX connectivity; Skipping instance");
                }
            }
        }
        catch (Throwable e) {
            LOG.error("Unexpected exception during discovery", e);
        }

    }

    /** Return a collection of job names from the collection group. */
    private Collection<String> getCollectionJobs() throws SchedulerException {
        Set<JobKey> jobKeys = this.scheduler.getJobKeys(GroupMatcher.jobGroupEquals("collectionGroup"));

        return Collections2.transform(jobKeys, new Function<JobKey, String>() {
            @Override
            public String apply(JobKey input) {
                return input.getName();
            }
        });
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void setInstances(InstanceCache instances) {
        this.instances = instances;
    }

    public void setInterval(Integer interval) {
        this.interval = interval;
    }

    public void setCarbonHost(String carbonHost) {
        this.carbonHost = carbonHost;
    }

    public void setCarbonPort(int carbonPort) {
        this.carbonPort = carbonPort;
    }

    public void setFilter(Object filter) {
        this.filter = filter;
    }

    @Override
    public String toString() {
        return "Discover [instances=" + instances + ", scheduler=" + scheduler + ", interval=" + interval + ", carbonHost="
                + carbonHost + ", carbonPort=" + carbonPort + ", filter=" + filter + "]";
    }

    private static Trigger newTrigger(String instance, int interval) {
        return TriggerBuilder.newTrigger()
                .withIdentity(triggerName(instance), "collectionGroup")
                .startNow()
                .withSchedule(simpleSchedule().withIntervalInSeconds(interval).repeatForever())
                .build();
    }

    private static String triggerName(String instance) {
        return String.format("%s_Trigger", instance);
    }

}
