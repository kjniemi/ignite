/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.yarn;

import org.apache.hadoop.fs.*;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.*;
import org.apache.hadoop.yarn.conf.*;
import org.apache.hadoop.yarn.util.*;
import org.apache.ignite.yarn.utils.*;

import java.io.*;
import java.util.*;
import java.util.logging.*;

import static org.apache.hadoop.yarn.api.ApplicationConstants.*;

/**
 * Ignite yarn client.
 */
public class IgniteYarnClient {
    /** */
    public static final Logger log = Logger.getLogger(IgniteYarnClient.class.getSimpleName());

    /**
     * Main methods has only one optional parameter - path to properties file.
     *
     * @param args Args.
     */
    public static void main(String[] args) throws Exception {
        checkArguments(args);

        // Set path to app master jar.
        String pathAppMasterJar = args[0];

        ClusterProperties props = ClusterProperties.from(args.length == 2 ? args[1] : null);

        YarnConfiguration conf = new YarnConfiguration();
        YarnClient yarnClient = YarnClient.createYarnClient();
        yarnClient.init(conf);
        yarnClient.start();

        // Create application via yarnClient
        YarnClientApplication app = yarnClient.createApplication();

        FileSystem fs = FileSystem.get(conf);

        // Load ignite and jar
        Path ignite = getIgnite(props, fs);

        Path appJar = IgniteYarnUtils.copyLocalToHdfs(fs, pathAppMasterJar,
            props.igniteWorkDir() + File.separator + IgniteYarnUtils.JAR_NAME);

        // Set up the container launch context for the application master
        ContainerLaunchContext amContainer = Records.newRecord(ContainerLaunchContext.class);

        System.out.println(Environment.JAVA_HOME.$() + "/bin/java -Xmx512m " + ApplicationMaster.class.getName()
            + IgniteYarnUtils.SPACE + ignite.toUri());

        amContainer.setCommands(
            Collections.singletonList(
                Environment.JAVA_HOME.$() + "/bin/java -Xmx512m " + ApplicationMaster.class.getName()
                + IgniteYarnUtils.SPACE + ignite.toUri()
                + IgniteYarnUtils.YARN_LOG_OUT
            )
        );

        // Setup jar for ApplicationMaster
        LocalResource appMasterJar = IgniteYarnUtils.setupFile(appJar, fs, LocalResourceType.FILE);

        amContainer.setLocalResources(Collections.singletonMap(IgniteYarnUtils.JAR_NAME, appMasterJar));

        // Setup CLASSPATH for ApplicationMaster
        Map<String, String> appMasterEnv = props.toEnvs();

        setupAppMasterEnv(appMasterEnv, conf);

        amContainer.setEnvironment(appMasterEnv);

        // Set up resource type requirements for ApplicationMaster
        Resource capability = Records.newRecord(Resource.class);
        capability.setMemory(512);
        capability.setVirtualCores(1);

        // Finally, set-up ApplicationSubmissionContext for the application
        ApplicationSubmissionContext appContext = app.getApplicationSubmissionContext();
        appContext.setApplicationName("ignition"); // application name
        appContext.setAMContainerSpec(amContainer);
        appContext.setResource(capability);
        appContext.setQueue("default"); // queue

        // Submit application
        ApplicationId appId = appContext.getApplicationId();
        System.out.println("Submitting application " + appId);
        yarnClient.submitApplication(appContext);

        ApplicationReport appReport = yarnClient.getApplicationReport(appId);
        YarnApplicationState appState = appReport.getYarnApplicationState();

        while (appState != YarnApplicationState.FINISHED &&
                appState != YarnApplicationState.KILLED &&
                appState != YarnApplicationState.FAILED) {
            Thread.sleep(100);

            appReport = yarnClient.getApplicationReport(appId);

            appState = appReport.getYarnApplicationState();
        }

        yarnClient.killApplication(appId);

        System.out.println("Application " + appId + " finished with state " + appState + " at "
            + appReport.getFinishTime());
    }

    /**
     * Check input arguments.
     *
     * @param args Arguments.
     */
    private static void checkArguments(String[] args) {
        if (args.length < 1)
            throw new IllegalArgumentException();
    }

    /**
     * @param props Properties.
     * @param fileSystem Hdfs file system.
     * @return Hdfs path to ignite node.
     * @throws Exception
     */
    private static Path getIgnite(ClusterProperties props, FileSystem fileSystem) throws Exception {
        IgniteProvider provider = new IgniteProvider(props, fileSystem);

        return provider.getIgnite();
    }

    /**
     *
     * @param envs Environment variables.
     * @param conf Yarn configuration.
     */
    private static void setupAppMasterEnv(Map<String, String> envs, YarnConfiguration conf) {
        for (String c : conf.getStrings(YarnConfiguration.YARN_APPLICATION_CLASSPATH,
            YarnConfiguration.DEFAULT_YARN_APPLICATION_CLASSPATH))
            Apps.addToEnvironment(envs, Environment.CLASSPATH.name(),
                    c.trim(), File.pathSeparator);

        Apps.addToEnvironment(envs,
                Environment.CLASSPATH.name(),
                Environment.PWD.$() + File.separator + "*",
                File.pathSeparator);
    }
}