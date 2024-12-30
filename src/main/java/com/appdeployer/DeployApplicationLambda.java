package com.appdeployer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.regions.Region;

public class DeployApplicationLambda implements RequestHandler<Map<String, String>, String> {

    private static final Logger logger = Logger.getLogger(DeployApplicationLambda.class.getName());

    private static final String REPO_URL = "https://github.com/kapilCSL/TestRepo.git";
    private static final String LOCAL_REPO_PATH = "/tmp/webapps"; // Lambda tmp storage
    private static final String EC2_HOST = "16.171.129.206"; // EC2 Public IP
    private static final String EC2_USER = "ubuntu"; // EC2 username
    private static final String PRIVATE_KEY = "KapilCSLkey.pem";

    @Override
    public String handleRequest(Map<String, String> input, Context context) {
        String appName = input.get("appName");

        if (appName == null || appName.isEmpty()) {
            return "Error: 'appName' is required!";
        }

        try {

            // Step 3: Deploy Docker container on EC2
            deployDockerContainerOnEC2(appName, PRIVATE_KEY);

            return "App '" + appName + "' successfully deployed on EC2!";
        } catch (Exception e) {
            logger.severe("Error during deployment: " + e.getMessage());
            e.printStackTrace();
            return "Error during deployment: " + e.getMessage();
        }
    }

    // Method to deploy the Docker container on EC2
    private void deployDockerContainerOnEC2(String appName, String pemKeyContent) throws Exception {
        JSch jsch = new JSch();
        Session session = null;
        try {
            jsch.addIdentity(PRIVATE_KEY);
            session = jsch.getSession(EC2_USER, EC2_HOST, 22);
            session.setConfig("StrictHostKeyChecking", "no");
            session.connect();

            logger.info("Successfully connected to EC2: " + EC2_HOST);

            // Clone the repository on EC2
            String cloneCommand = "git clone " + REPO_URL + " " + LOCAL_REPO_PATH;
            executeRemoteCommand(session, cloneCommand);

            // Build and run Docker container
            String dockerBuildCommand = "docker build -t " + appName + " " + LOCAL_REPO_PATH;
            executeRemoteCommand(session, dockerBuildCommand);

            String dockerRunCommand = "docker run -d -p 8080:8080 --name " + appName + " " + appName;
            executeRemoteCommand(session, dockerRunCommand);

            // Remove the repository folder after success
            String removeRepoCommand = "rm -rf " + LOCAL_REPO_PATH;
            executeRemoteCommand(session, removeRepoCommand);

        } catch (Exception e) {
            logger.severe("Failed to deploy Docker container: " + e.getMessage());
            throw e;
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
                logger.info("Disconnected from EC2: " + EC2_HOST);
            }
        }
    }

    // Helper method to execute remote SSH commands
    private void executeRemoteCommand(Session session, String command) throws Exception {
        logger.info("Executing remote command: " + command);

        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        channel.setErrStream(System.err);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(channel.getInputStream()))) {
            channel.connect();

            String line;
            while ((line = reader.readLine()) != null) {
                logger.info(line);
            }

            int exitStatus = channel.getExitStatus();
            if (exitStatus != 0) {
                throw new RuntimeException("Command failed with exit status: " + exitStatus);
            }
        } finally {
            channel.disconnect();
        }
    }
}
