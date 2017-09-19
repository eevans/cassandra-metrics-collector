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

import static org.quartz.JobBuilder.newJob;
import static org.quartz.SimpleScheduleBuilder.simpleSchedule;
import static org.quartz.TriggerBuilder.newTrigger;
import static org.quartz.impl.matchers.GroupMatcher.jobGroupEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

import javax.inject.Inject;

import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikimedia.cassandra.metrics.Filter;
import org.wikimedia.cassandra.metrics.FilterConfig;
import org.wikimedia.cassandra.metrics.Utils;
import org.wikimedia.cassandra.metrics.service.WatchFile.EventType;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import com.github.rvesse.airline.Command;
import com.github.rvesse.airline.HelpOption;
import com.github.rvesse.airline.Option;
import com.github.rvesse.airline.SingleCommand;
import com.github.rvesse.airline.parser.ParseException;

@Command(name = "cmcd", description = "cassandra-metrics-collector daemon")
public class Service {

    public static final String PREFIX_PREFIX = "cassandra";

    private static final Logger LOG = LoggerFactory.getLogger(Service.class);
    private static final File STOPFILE;

    static {
        STOPFILE = new File(System.getenv().getOrDefault("STOPFILE", "/etc/cassandra-metrics-collector/disable"));
    }

    @Inject
    private HelpOption help;

    @Option(name = { "-H", "--carbon-host", "--graphite-host" }, description = "Carbon hostname (default: localhost)", title = "HOSTNAME")
    private String carbonHost = "localhost";

    @Option(name = { "-p", "--carbon-port", "--graphite-port" }, description = "Carbon port number (default: 2003)", title = "PORT")
    private int carbonPort = 2003;

    @Option(name = { "-i", "--interval" }, description = "Collection interval in seconds (default: 60 seconds)", title = "INTERVAL")
    private int interval = 60;

    @Option(name = { "-di", "--discovery-interval" }, description = "Interval (in seconds) to perform (re)discovery (default: 300 seconds)", title = "INTERVAL")
    private int discoverInterval = 300;

    @Option(name = {"-f", "--filter-config"}, description = "Metric filter configuration", title = "YAML")
    private String filterConfig = null;

    private InstanceCache state = new InstanceCache();

    Filter getFilter() throws FileNotFoundException, IOException {
        if (this.filterConfig != null) {
            try (InputStream f = new FileInputStream(new File(this.filterConfig))) {
                Yaml yaml = new Yaml(new Constructor(FilterConfig.class));
                FilterConfig config = (FilterConfig)yaml.load(f);
                return new Filter(config);
            }
        }
        return null;
    }

    void execute() throws SchedulerException, IOException {

        // Print a synopsis to STDOUT (if requested), and exit.
        if (help.showHelpIfRequested()) {
            return;
        }

        LOG.info("Starting up...");

        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();

        // Triggers periodic (re)discovery
        Trigger discoveryTrigger = newTrigger()
                .withIdentity("discoveryTrigger", "discoveryGroup")
                .startNow()
                .withSchedule(simpleSchedule().withIntervalInSeconds(discoverInterval).repeatForever())
                .build();

        // Triggers reporting of internal metrics
        Trigger reportTrigger = newTrigger()
                .withIdentity("reportTrigger", "reportGroup")
                .startNow()
                .withSchedule(simpleSchedule().withIntervalInSeconds(interval).repeatForever())
                .build();
        
        // Setup discovery.

        // The discovery job periodically looks for new instances, and schedules
        // recurring collection tasks for any it finds.
        JobDataMap discoverMap = new JobDataMap();
        discoverMap.put("instances", state);
        discoverMap.put("scheduler", scheduler);
        discoverMap.put("interval", interval);
        discoverMap.put("carbonHost", carbonHost);
        discoverMap.put("carbonPort", carbonPort);
        discoverMap.put("filter", getFilter());

        JobDetail discoverJob = newJob(Discover.class)
                .withIdentity("discoveryJob", "discoveryGroup")
                .usingJobData(discoverMap)
                .build();

        scheduler.scheduleJob(discoverJob, discoveryTrigger);

        // The collection listener monitors for task completion in the collection
        // group, and updates the stats counters accordingly.
        Stats stats = new Stats();
        CollectionListener listener = new CollectionListener(scheduler, stats);
        scheduler.getListenerManager().addJobListener(listener, jobGroupEquals("collectionGroup"));

        // The stats job periodically reports internal stats to Graphite.
        JobDataMap statsMap = new JobDataMap();
        statsMap.put("carbonHost", carbonHost);
        statsMap.put("carbonPort", carbonPort);
        statsMap.put("stats", stats);
        statsMap.put("interval", interval);

        JobDetail statsJob = newJob(StatsReporter.class)
                .withIdentity("reporterJob", "reportGroup")
                .usingJobData(statsMap)
                .build();

        scheduler.scheduleJob(statsJob, reportTrigger);

        if (!STOPFILE.exists()) {
            scheduler.start();
        }
        else {
            LOG.warn("{} detected; Leaving scheduler in standby mode", STOPFILE);
        }

        Thread watcher = new Thread(new WatchFile(STOPFILE, new WatchFile.Task() {
            @Override
            public void execute(EventType type) throws SchedulerException {
                switch (type) {
                case CREATE:
                    scheduler.standby();
                    LOG.warn("{} detected; Putting scheduler in standby", STOPFILE);
                    break;
                case DELETE:
                    scheduler.start();
                    LOG.warn("{} was removed; Restarting scheduler", STOPFILE);
                    break;
                }
            }
        }));
        watcher.setDaemon(true);
        watcher.start();

    }

    public static void main(String[] args) {

        // We need tools.jar as shipped with the JDK (to power discovery). The
        // following attempts to locate the jar and add it to the classpath.
        boolean added = false;
        try {
            added = Utils.addToolsJar();
        } catch (Exception e) {
            System.err.println(e.getMessage());
            e.getCause().printStackTrace(System.err);
            System.exit(1);
        }

        if (!added) {
            System.err.println("Unable to locate tools.jar (hint: do you have a JDK installed?");
            System.exit(1);
        }

        SingleCommand<Service> parser = SingleCommand.singleCommand(Service.class);

        try {
            parser.parse(args).execute();

        } catch (ParseException e) {
            System.err.println(e.getLocalizedMessage());
        } catch (Throwable e) {
            System.err.println("Unexpected error: " + e.getLocalizedMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }

    }

}
