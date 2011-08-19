/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.launcher;

import org.gradle.BuildExceptionReporter;
import org.gradle.StartParameter;
import org.gradle.api.internal.project.ServiceRegistry;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.initialization.DefaultCommandLineConverter;
import org.gradle.initialization.DefaultGradleLauncherFactory;
import org.gradle.initialization.GradleLauncherFactory;
import org.gradle.launcher.protocol.*;
import org.gradle.logging.LoggingServiceRegistry;
import org.gradle.logging.StyledTextOutputFactory;
import org.gradle.logging.internal.LoggingOutputInternal;
import org.gradle.logging.internal.OutputEvent;
import org.gradle.logging.internal.OutputEventListener;
import org.gradle.messaging.concurrent.DefaultExecutorFactory;
import org.gradle.messaging.concurrent.Stoppable;
import org.gradle.messaging.concurrent.StoppableExecutor;
import org.gradle.messaging.remote.internal.Connection;
import org.gradle.util.UncheckedException;

import java.io.*;
import java.util.Arrays;
import java.util.Properties;

/**
 * The server portion of the build daemon. See {@link DaemonClient} for a description of the protocol.
 */
public class DaemonMain implements Runnable {
    private static final Logger LOGGER = Logging.getLogger(Main.class);
    private final ServiceRegistry loggingServices;
    private final DaemonConnector connector;
    private final GradleLauncherFactory launcherFactory;

    private final DefaultExecutorFactory executorFactory = new DefaultExecutorFactory();

    public DaemonMain(ServiceRegistry loggingServices, DaemonConnector connector) {
        this(loggingServices, connector, new DefaultGradleLauncherFactory(loggingServices));
    }

    public DaemonMain(ServiceRegistry loggingServices, DaemonConnector connector, GradleLauncherFactory launcherFactory) {
        this.loggingServices = loggingServices;
        this.connector = connector;
        this.launcherFactory = launcherFactory;
    }

    public static void main(String[] args) throws IOException {
        StartParameter startParameter = new DefaultCommandLineConverter().convert(Arrays.asList(args));
        DaemonConnector connector = new DaemonConnector(startParameter.getGradleUserHomeDir());
        redirectOutputsAndInput(startParameter);
        LoggingServiceRegistry loggingServices = LoggingServiceRegistry.newChildProcessLogging();
        new DaemonMain(loggingServices, connector).run();
    }

    private static void redirectOutputsAndInput(StartParameter startParameter) throws IOException {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
//        InputStream originalIn = System.in;
        DaemonDir daemonDir = new DaemonDir(startParameter.getGradleUserHomeDir());
        File logOutputFile = daemonDir.getLog(); //TODO SF each daemon needs his own log file (or potentially his own folder)
        logOutputFile.getParentFile().mkdirs();
        PrintStream printStream = new PrintStream(new FileOutputStream(logOutputFile), true);
        System.setOut(printStream);
        System.setErr(printStream);
        System.setIn(new ByteArrayInputStream(new byte[0]));
        originalOut.close();
        originalErr.close();
        // TODO - make this work on windows
//        originalIn.close();
    }

    public void run() {
        connector.accept(new IncomingConnectionHandler() {
            public void handle(final Connection<Object> connection, final DaemonConnector.CompletionHandler serverControl) {
                //we're spinning a thread to do work to avoid blocking the connection
                //This means that the Daemon potentially can have multiple build jobs running.
                // We're avoiding that situation on a different level but even if things go awry and daemon runs multiple jobs nothing serious happens
                //When the daemon is busy running a build and the Stop command arrives, the stop command gets its own thread, is executed,
                // stops the main server thread that eventually make the build thread gone as well.
                StoppableExecutor executor = executorFactory.create("DaemonMain worker");
                executor.execute(new Runnable() {
                    public void run() {
                        try {
                            serverControl.onStartActivity();
                            doRun(connection, serverControl);
                        } finally {
                            serverControl.onActivityComplete();
                            connection.stop();
                        }
                    }
                });
            }
        });
        executorFactory.stop();
    }

    private void doRun(final Connection<Object> connection, Stoppable serverControl) {
        CommandComplete result = null;
        Throwable failure = null;
        try {
            LoggingOutputInternal loggingOutput = loggingServices.get(LoggingOutputInternal.class);
            OutputEventListener listener = new OutputEventListener() {
                public void onOutput(OutputEvent event) {
                    connection.dispatch(event);
                }
            };

            // Perform as much as possible of the interaction while the logging is routed to the client
            loggingOutput.addOutputEventListener(listener);
            try {
                result = doRunWithLogging(connection, serverControl);
            } finally {
                loggingOutput.removeOutputEventListener(listener);
            }
        } catch (ReportedException e) {
            failure = e;
        } catch (Throwable throwable) {
            LOGGER.error("Could not execute build.", throwable);
            failure = throwable;
        }
        if (failure != null) {
            result = new CommandComplete(UncheckedException.asUncheckedException(failure));
        }
        assert result != null;
        connection.dispatch(result);
    }

    private CommandComplete doRunWithLogging(Connection<Object> connection, Stoppable serverControl) {
        Command command = (Command) connection.receive();
        try {
            return doRunWithExceptionHandling(command, serverControl);
        } catch (ReportedException e) {
            throw e;
        } catch (Throwable throwable) {
            BuildExceptionReporter exceptionReporter = new BuildExceptionReporter(loggingServices.get(StyledTextOutputFactory.class), new StartParameter(), command.getClientMetaData());
            exceptionReporter.reportException(throwable);
            throw new ReportedException(throwable);
        }
    }

    private CommandComplete doRunWithExceptionHandling(Command command, Stoppable serverControl) {
        LOGGER.info("Executing {}", command);
        if (command instanceof Stop) {
            LOGGER.lifecycle("Stopping");
            serverControl.stop();
            return new CommandComplete(null);
        }

        return build((Build) command);
    }

    private Result build(Build build) {
        Properties originalSystemProperties = new Properties();
        originalSystemProperties.putAll(System.getProperties());
        Properties clientSystemProperties = new Properties();
        clientSystemProperties.putAll(build.getParameters().getSystemProperties());
        System.setProperties(clientSystemProperties);
        try {
            DefaultGradleLauncherActionExecuter executer = new DefaultGradleLauncherActionExecuter(launcherFactory, loggingServices);
            Object result = executer.execute(build.getAction(), build.getParameters());
            return new Result(result);
        } finally {
            System.setProperties(originalSystemProperties);
        }
    }
}
