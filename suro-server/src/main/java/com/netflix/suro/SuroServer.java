/*
 * Copyright 2013 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.suro;

import com.google.common.io.Closeables;
import com.google.inject.Injector;
import com.netflix.governator.configuration.PropertiesConfigurationProvider;
import com.netflix.governator.guice.BootstrapBinder;
import com.netflix.governator.guice.BootstrapModule;
import com.netflix.governator.guice.LifecycleInjector;
import com.netflix.governator.lifecycle.LifecycleManager;
import com.netflix.suro.routing.FastPropertyRoutingMapConfigurator;
import com.netflix.suro.routing.RoutingPlugin;
import com.netflix.suro.server.StatusServer;
import com.netflix.suro.sink.FastPropertySinkConfigurator;
import com.netflix.suro.sink.SinkPlugin;
import org.apache.commons.cli.*;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Command line driver for Suro
 * 
 * @author jbae
 * @author elandau
 */
public class SuroServer {
    private static final String PROP_PREFIX = "SuroServer.";
    private static final int DEFAULT_CONTROL_PORT = 9090;
    public static final String OPT_CONTROL_PORT = "controlPort";

    public static void main(String[] args) throws IOException {
        LifecycleManager manager = null;

        try {
            // Parse the command line
            Options           options = createOptions();
            final CommandLine line    = new BasicParser().parse(options, args);
            
            // Load the properties file
            final Properties properties = new Properties();
            if (line.hasOption('p')) {
                properties.load(new FileInputStream(line.getOptionValue('p')));
            }

            // Bind all command line options to the properties with prefix "SuroServer."
            for (Option opt : line.getOptions()) {
                String name     = opt.getOpt();
                String value    = line.getOptionValue(name);
                String propName = PROP_PREFIX + opt.getArgName();
                if (propName.equals(FastPropertyRoutingMapConfigurator.ROUTING_MAP_PROPERTY)) {
                    properties.setProperty(FastPropertyRoutingMapConfigurator.ROUTING_MAP_PROPERTY,
                            FileUtils.readFileToString(new File(value)));
                } else if (propName.equals(FastPropertySinkConfigurator.SINK_PROPERTY)) {
                    properties.setProperty(FastPropertySinkConfigurator.SINK_PROPERTY,
                            FileUtils.readFileToString(new File(value)));
                } else {
                    properties.setProperty(propName, value);
                }
            }

            // Create the injector
            Injector injector = LifecycleInjector.builder()
                    .withBootstrapModule(
                            new BootstrapModule() {
                                @Override
                                public void configure(BootstrapBinder binder) {
                                    binder.bindConfigurationProvider().toInstance(
                                            new PropertiesConfigurationProvider(properties));
                                }
                            }
                    )
                    .withModules(
                            new RoutingPlugin(),
                            new SinkPlugin(),
                            new SuroFastPropertyModule(),
                            new SuroModule(properties),
                            StatusServer.createJerseyServletModule()
                    )
                    .createInjector();

            manager = injector.getInstance(LifecycleManager.class);
            manager.start();

            waitForShutdown(getControlPort(options));
        } catch (Exception e) {
            System.err.println("SuroServer startup failed: " + e.getMessage());
            System.exit(-1);
        } finally {
            Closeables.close(manager, true);
        }
    }

    private static void waitForShutdown(int port) throws IOException {
       new SuroControl().start(port);
    }

    private static int getControlPort(Options options) {
        Option opt = options.getOption("controlPort");
        String value = opt.getValue();
        if(value == null) {
            return DEFAULT_CONTROL_PORT;
        }

        return Integer.parseInt(value);
    }

    @SuppressWarnings("static-access")
    private static Options createOptions() {
        Option propertyFile = OptionBuilder.withArgName("serverProperty")
                .hasArg()
                .withDescription("server property file path")
                .create('p');

        Option mapFile = OptionBuilder.withArgName("routingMap")
                .hasArg()
                .isRequired(true)
                .withDescription("message routing map file path")
                .create('m');

        Option sinkFile = OptionBuilder.withArgName("sinkConfig" )
                .hasArg()
                .isRequired(true)
                .withDescription("sink")
                .create('s');

        Option accessKey = OptionBuilder.withArgName("AWSAccessKey" )
                .hasArg()
                .isRequired(false)
                .withDescription("AWSAccessKey")
                .create('a');

        Option secretKey = OptionBuilder.withArgName("AWSSecretKey" )
                .hasArg()
                .isRequired(false)
                .withDescription("AWSSecretKey")
                .create('k');

        Option controlPort = OptionBuilder.withArgName(OPT_CONTROL_PORT)
            .hasArg()
            .isRequired(false)
            .withDescription("The port used to send command to this server")
            .create('c');

        Options options = new Options();
        options.addOption(propertyFile);
        options.addOption(mapFile);
        options.addOption(sinkFile);
        options.addOption(accessKey);
        options.addOption(secretKey);
        options.addOption(controlPort);

        return options;
    }
}
