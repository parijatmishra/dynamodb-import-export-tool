/*
 * Copyright 2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Amazon Software License (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/asl/
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.dynamodb.bootstrap;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.dynamodb.bootstrap.constants.BootstrapConstants;
import com.amazonaws.dynamodb.bootstrap.exception.NullReadCapacityException;
import com.amazonaws.dynamodb.bootstrap.exception.SectionOutOfRangeException;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

/**
 * The interface that parses the arguments, and begins to transfer data from one
 * DynamoDB table to another
 */
public class CommandLineInterface {

    /**
     * Logger for the DynamoDBBootstrapWorker.
     */
    private static final Logger LOGGER = LogManager
            .getLogger(CommandLineInterface.class);

    /**
     * Main class to begin transferring data from one DynamoDB table to another
     * DynamoDB table.
     * 
     * @param args
     */
    public static void main(String[] args) {
        CommandLineArgs params = new CommandLineArgs();
        JCommander cmd = new JCommander(params);

        try {
            // parse given arguments
            cmd.parse(args);
        } catch (ParameterException e) {
            LOGGER.error(e);
            JCommander.getConsole().println(e.getMessage());
            cmd.usage();
            System.exit(1);
        }

        // show usage information if help flag exists
        if (params.getHelp()) {
            cmd.usage();
            return;
        }
        final String sourceEndpoint = params.getSourceEndpoint();
        final String destinationEndpoint = params.getDestinationEndpoint();
        final String destinationTable = params.getDestinationTable();
        final String sourceTable = params.getSourceTable();
        final String sourceRole = params.getSourceRole();
        final String destinationRole = params.getDestinationRole();
        final double readThroughputRatio = params.getReadThroughputRatio();
        final double writeThroughputRatio = params.getWriteThroughputRatio();
        final int maxWriteThreads = params.getMaxWriteThreads();
        final boolean consistentScan = params.getConsistentScan();

        final AmazonDynamoDB sourceClient = createDynamoDBClient(sourceEndpoint, sourceRole);
        final AmazonDynamoDB destinationClient = createDynamoDBClient(destinationEndpoint, destinationRole);

        TableDescription readTableDescription = sourceClient.describeTable(
                sourceTable).getTable();
        TableDescription writeTableDescription = destinationClient
                .describeTable(destinationTable).getTable();
        int numSegments = 10;
        try {
            numSegments = DynamoDBBootstrapWorker
                    .getNumberOfSegments(readTableDescription);
        } catch (NullReadCapacityException e) {
            LOGGER.warn("Number of segments not specified - defaulting to "
                    + numSegments, e);
        }

        final double readThroughput = calculateThroughput(readTableDescription,
                readThroughputRatio, true);
        final double writeThroughput = calculateThroughput(
                writeTableDescription, writeThroughputRatio, false);

        try {
            ExecutorService sourceExec = getSourceThreadPool(numSegments);
            ExecutorService destinationExec = getDestinationThreadPool(maxWriteThreads);
            DynamoDBConsumer consumer = new DynamoDBConsumer(destinationClient,
                    destinationTable, writeThroughput, destinationExec);

            final DynamoDBBootstrapWorker worker = new DynamoDBBootstrapWorker(
                    sourceClient, readThroughput, sourceTable, sourceExec,
                    params.getSection(), params.getTotalSections(), numSegments, consistentScan);

            LOGGER.info("Starting transfer...");
            worker.pipe(consumer);
            LOGGER.info("Finished Copying Table.");
        } catch (ExecutionException e) {
            LOGGER.error("Encountered exception when executing transfer.", e);
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted when executing transfer.", e);
            System.exit(1);
        } catch (SectionOutOfRangeException e) {
            LOGGER.error("Invalid section parameter", e);
        }
    }

    /**
     * Return a AmazonDynamoDB client implementation customized via the given arguments.
     *
     * @param endpoint The endpoint to connect to. If null, an endpoint in the default region will be used.
     *
     * @return An AmazonDynamoDB client.
     */
    private static AmazonDynamoDB createDynamoDBClient(String endpoint, String assumeRoleArn) {
        final ClientConfiguration clientConfig = new ClientConfiguration().withMaxConnections(BootstrapConstants.MAX_CONN_SIZE);
        final AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();

        AmazonDynamoDBClientBuilder clientBuilder = AmazonDynamoDBClientBuilder.standard();
        clientBuilder.setClientConfiguration(clientConfig);
        clientBuilder.setCredentials(credentialsProvider);

        /* If user specified a custom endpoint, configure it */
        if (endpoint != null) {
            AwsClientBuilder.EndpointConfiguration endpointConfiguration =
                    new AwsClientBuilder.EndpointConfiguration(
                            endpoint, /* The custom endpoint specified on the command line */
                            "us-west-2" /* By default, use this region -- works for dynamodb local */
                    );
            clientBuilder.setEndpointConfiguration(endpointConfiguration);
        }

        /* If user specified an IAM to assume, create the appropriate credentials provider and use it for
           building the client. */
        if (assumeRoleArn != null) {
            /* Create an STS client that uses the default credentials provider */
            AWSSecurityTokenService stsClient =
                    AWSSecurityTokenServiceClientBuilder.standard()
                    .withCredentials(credentialsProvider)
                    .build();
            /* Create a AWSCredentialsProvider from the STS client and the given role */
            STSAssumeRoleSessionCredentialsProvider assumeRoleSessionCredentialsProvider =
                    new STSAssumeRoleSessionCredentialsProvider.Builder(
                            assumeRoleArn,
                            "dynamodb-import-export-tool"
                    )
                    .withStsClient(stsClient)
                    .build();
            /* Override the default credentials provider */
            clientBuilder.setCredentials(assumeRoleSessionCredentialsProvider);
        }

        return clientBuilder.build();
    }

    /**
     * returns the provisioned throughput based on the input ratio and the
     * specified DynamoDB table provisioned throughput.
     */
    private static double calculateThroughput(
            TableDescription tableDescription, double throughputRatio,
            boolean read) {
        if (read) {
            return tableDescription.getProvisionedThroughput()
                    .getReadCapacityUnits() * throughputRatio;
        }
        return tableDescription.getProvisionedThroughput()
                .getWriteCapacityUnits() * throughputRatio;
    }

    /**
     * Returns the thread pool for the destination DynamoDB table.
     */
    private static ExecutorService getDestinationThreadPool(int maxWriteThreads) {
        int corePoolSize = BootstrapConstants.DYNAMODB_CLIENT_EXECUTOR_CORE_POOL_SIZE;
        if (corePoolSize > maxWriteThreads) {
            corePoolSize = maxWriteThreads - 1;
        }
        final long keepAlive = BootstrapConstants.DYNAMODB_CLIENT_EXECUTOR_KEEP_ALIVE;
        ExecutorService exec = new ThreadPoolExecutor(corePoolSize,
                maxWriteThreads, keepAlive, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(maxWriteThreads),
                new ThreadPoolExecutor.CallerRunsPolicy());
        return exec;
    }

    /**
     * Returns the thread pool for the source DynamoDB table.
     */
    private static ExecutorService getSourceThreadPool(int numSegments) {
        int corePoolSize = BootstrapConstants.DYNAMODB_CLIENT_EXECUTOR_CORE_POOL_SIZE;
        if (corePoolSize > numSegments) {
            corePoolSize = numSegments - 1;
        }

        final long keepAlive = BootstrapConstants.DYNAMODB_CLIENT_EXECUTOR_KEEP_ALIVE;
        ExecutorService exec = new ThreadPoolExecutor(corePoolSize,
                numSegments, keepAlive, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(numSegments),
                new ThreadPoolExecutor.CallerRunsPolicy());
        return exec;
    }

}
