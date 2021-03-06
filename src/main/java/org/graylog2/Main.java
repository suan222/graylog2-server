/**
 * Copyright 2010, 2011, 2012 Lennart Koopmann <lennart@socketfeed.com>
 *
 * This file is part of Graylog2.
 *
 * Graylog2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog2.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.graylog2;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.beust.jcommander.JCommander;
import com.github.joschi.jadconfig.JadConfig;
import com.github.joschi.jadconfig.RepositoryException;
import com.github.joschi.jadconfig.ValidationException;
import com.github.joschi.jadconfig.repositories.PropertiesRepository;
import org.graylog2.initializers.AMQPInitializer;
import org.graylog2.initializers.DroolsInitializer;
import org.graylog2.initializers.GELFInitializer;
import org.graylog2.initializers.HostCounterCacheWriterInitializer;
import org.graylog2.initializers.MessageCounterInitializer;
import org.graylog2.initializers.MessageQueueInitializer;
import org.graylog2.initializers.MessageRetentionInitializer;
import org.graylog2.initializers.ServerValueWriterInitializer;
import org.graylog2.initializers.SyslogServerInitializer;

/**
 * Main class of Graylog2.
 *
 * @author Lennart Koopmann <lennart@socketfeed.com>
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class);

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {

        final CommandLineArguments commandLineArguments = new CommandLineArguments();
        final JCommander jCommander = new JCommander(commandLineArguments, args);
        jCommander.setProgramName("graylog2");

        if (commandLineArguments.isShowHelp()) {
            jCommander.usage();
            System.exit(0);
        }

        if (commandLineArguments.isShowVersion()) {
            System.out.println("Graylog2 Server " + GraylogServer.GRAYLOG2_VERSION);
            System.out.println("JRE: " + Tools.getSystemInformation());
            System.exit(0);
        }

        // Are we in debug mode?
        if (commandLineArguments.isDebug()) {
            LOG.info("Running in Debug mode");
            Logger.getRootLogger().setLevel(Level.ALL);
            Logger.getLogger(Main.class.getPackage().getName()).setLevel(Level.ALL);
        }

        LOG.info("Graylog2 " + GraylogServer.GRAYLOG2_VERSION + " starting up. (JRE: " + Tools.getSystemInformation() + ")");

        String configFile = commandLineArguments.getConfigFile();
        LOG.info("Using config file: " + configFile);

        final Configuration configuration = new Configuration();
        JadConfig jadConfig = new JadConfig(new PropertiesRepository(configFile), configuration);

        LOG.info("Loading configuration");
        try {
            jadConfig.process();
        } catch (RepositoryException e) {
            LOG.fatal("Couldn't load configuration file " + configFile, e);
            System.exit(1);
        } catch (ValidationException e) {
            LOG.fatal("Invalid configuration", e);
            System.exit(1);
        }

        // If we only want to check our configuration, we can gracefully exit here
        if (commandLineArguments.isConfigTest()) {
            System.exit(0);
        }

        savePidFile(commandLineArguments.getPidFile());

        // Le server object. This is where all the magic happens.
        GraylogServer server = new GraylogServer(configuration);

        // Register initializers.
        server.registerInitializer(new ServerValueWriterInitializer(server, configuration));
        server.registerInitializer(new MessageQueueInitializer(server, configuration));
        server.registerInitializer(new DroolsInitializer(server, configuration));
        server.registerInitializer(new HostCounterCacheWriterInitializer(server));
        server.registerInitializer(new MessageCounterInitializer(server));
        server.registerInitializer(new SyslogServerInitializer(server, configuration));
        
        // Moar initializers. Conditional for great fun and profit.
        if (configuration.isUseGELF())        { server.registerInitializer(new GELFInitializer(server, configuration)); }
        if (configuration.isAmqpEnabled())    { server.registerInitializer(new AMQPInitializer(server, configuration)); }
        if (configuration.performRetention()) { server.registerInitializer(new MessageRetentionInitializer(server));    }

        // Blocks until we shut down.
        server.run();

        LOG.info("Graylog2 " + GraylogServer.GRAYLOG2_VERSION + " exiting.");
    }

    private static void savePidFile(String pidFile) {

        String pid = Tools.getPID();
        Writer pidFileWriter = null;

        try {
            if (pid == null || pid.isEmpty() || pid.equals("unknown")) {
                throw new Exception("Could not determine PID.");
            }

            pidFileWriter = new FileWriter(pidFile);
            IOUtils.write(pid, pidFileWriter);
        } catch (Exception e) {
            LOG.fatal("Could not write PID file: " + e.getMessage(), e);
            System.exit(1);
        } finally {
            IOUtils.closeQuietly(pidFileWriter);
            // make sure to remove our pid when we exit
            new File(pidFile).deleteOnExit();
        }
    }

}
