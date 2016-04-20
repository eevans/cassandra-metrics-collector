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

import java.io.IOException;

public class Command {
    public static void main(String... args) throws IOException {
        if (args.length != 5) {
            System.err.printf("Usage: %s <jmx host> <jmx port> <graphite host> <graphite port> <prefix>%n", Command.class.getSimpleName());
            System.exit(1);
        }

        Integer jmxPort = null, graphitePort = null;

        try {
            jmxPort = Integer.parseInt(args[1]);
        }
        catch (NumberFormatException e) {
            System.err.println("Not a valid port number: " + args[1]);
            System.exit(1);
        }

        try {
            graphitePort = Integer.parseInt(args[3]);
        }
        catch (NumberFormatException e) {
            System.err.println("Not a valid port number: " + args[3]);
            System.exit(1);
        }

        try (JmxCollector collector = JmxCollectorFactory.create(args[0], jmxPort)) {
            if (Boolean.parseBoolean(System.getenv().get("DRY_RUN"))) {
                collector.getSamples(new TsvVisitor(System.out, args[4]));
            }
            else {
                try (CarbonVisitor visitor = new CarbonVisitor(args[2], graphitePort, args[4])) {
                    collector.getSamples(visitor);
                }
            }
        }

    }
}
