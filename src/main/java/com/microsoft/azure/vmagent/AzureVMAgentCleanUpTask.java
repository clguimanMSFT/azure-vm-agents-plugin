/*
 Copyright 2016 Microsoft, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.microsoft.azure.vmagent;

import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.resources.Deployment;
import com.microsoft.azure.management.resources.GenericResource;
import com.microsoft.azure.util.AzureCredentials.ServicePrincipal;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.retry.DefaultRetryStrategy;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.CleanUpAction;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.ExecutionEngine;
import com.microsoft.azure.vmagent.util.TokenCache;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class AzureVMAgentCleanUpTask extends AsyncPeriodicWork {

    private static class DeploymentInfo {

        DeploymentInfo(
                final String cloudName,
                final String resourceGroupName,
                final String deploymentName,
                final int deleteAttempts) {
            this.cloudName = cloudName;
            this.deploymentName = deploymentName;
            this.resourceGroupName = resourceGroupName;
            this.attemptsRemaining = deleteAttempts;
        }

        public String getCloudName() {
            return cloudName;
        }

        public String getDeploymentName() {
            return deploymentName;
        }

        public String getResourceGroupName() {
            return resourceGroupName;
        }

        public boolean hasAttemptsRemaining() {
            return attemptsRemaining > 0;
        }

        public void decrementAttemptsRemaining() {
            attemptsRemaining--;
        }

        private String cloudName;
        private String deploymentName;
        private String resourceGroupName;
        private int attemptsRemaining;
    }

    private static final long SUCCESSFUL_DEPLOYMENT_TIMEOUT_IN_MINUTES = 60 * 1;
    private static final long FAILING_DEPLOYMENT_TIMEOUT_IN_MINUTES = 60 * 8;
    private static final int MAX_DELETE_ATTEMPTS = 3;
    private static final long CLEANUP_PERIOD_IN_MINUTES = 15;
    private static final Logger LOGGER = Logger.getLogger(AzureVMAgentCleanUpTask.class.getName());
    private static final ConcurrentLinkedQueue<DeploymentInfo> DEPLOYMENTS_TO_CLEAN
            = new ConcurrentLinkedQueue<DeploymentInfo>();

    public AzureVMAgentCleanUpTask() {
        super("Azure VM Agents Clean Task");
    }

    public static class DeploymentRegistrar {

        public final void registerDeployment(
                final String cloudName,
                final String resourceGroupName,
                final String deploymentName) {
            LOGGER.log(Level.INFO,
                    "AzureVMAgentCleanUpTask: Registering deployment {0} in {1}",
                    new Object[]{deploymentName, resourceGroupName});

            DeploymentInfo newDeploymentToClean = new DeploymentInfo(
                    cloudName,
                    resourceGroupName,
                    deploymentName,
                    MAX_DELETE_ATTEMPTS);
            DEPLOYMENTS_TO_CLEAN.add(newDeploymentToClean);
        }

        public final AzureUtil.DeploymentTag getDeploymentTag() {
            return new AzureUtil.DeploymentTag();
        }
    }

    public final void cleanDeployments() {
        cleanDeployments(
                SUCCESSFUL_DEPLOYMENT_TIMEOUT_IN_MINUTES,
                FAILING_DEPLOYMENT_TIMEOUT_IN_MINUTES);
    }

    public final void cleanDeployments(
            final long successTimeoutInMinutes,
            final long failTimeoutInMinutes) {
        LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Cleaning deployments");
        // Walk the queue, popping and pushing until we reach an item that we've already
        // dealt with or the queue is empty.
        DeploymentInfo firstBackInQueue = null;
        while (!DEPLOYMENTS_TO_CLEAN.isEmpty() && firstBackInQueue != DEPLOYMENTS_TO_CLEAN.peek()) {
            DeploymentInfo info = DEPLOYMENTS_TO_CLEAN.remove();

            LOGGER.log(Level.INFO,
                    "AzureVMAgentCleanUpTask: cleanDeployments: Checking deployment {0}",
                    info.getDeploymentName());

            AzureVMCloud cloud = getCloud(info.getCloudName());

            if (cloud == null) {
                // Cloud could have been deleted, skip
                continue;
            }

            try {
                final Azure azureClient = TokenCache
                        .getInstance(cloud.getServicePrincipal())
                        .getAzureClient();

                // This will throw if the deployment can't be found.
                //      This could happen in a couple instances
                // 1) The deployment has already been deleted
                // 2) The deployment doesn't exist yet (race between creating the deployment and it
                //    being accepted by Azure.
                // To avoid this, we implement a retry.
                // If we hit an exception, we will decrement the number
                // of retries.  If we hit 0, we remove the deployment from our list.
                Deployment deployment = azureClient.deployments()
                        .getByGroup(info.getResourceGroupName(), info.getDeploymentName());
                if (deployment == null) {
                    LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: "
                            + "Deployment not found, skipping");
                    continue;
                }

                DateTime deploymentTime = deployment.timestamp();

                LOGGER.log(Level.INFO,
                        "AzureVMAgentCleanUpTask: cleanDeployments: Deployment created on {0}",
                        deploymentTime.toDate());
                long deploymentTimeInMillis = deploymentTime.getMillis();

                // Compare to now
                Calendar nowTime = Calendar.getInstance(deploymentTime.getZone().toTimeZone());
                long nowTimeInMillis = nowTime.getTimeInMillis();

                long diffTime = nowTimeInMillis - deploymentTimeInMillis;
                long diffTimeInMinutes = diffTime / DateTimeConstants.MILLIS_PER_MINUTE;

                String state = deployment.provisioningState();

                if (!state.equalsIgnoreCase("succeeded")
                        && diffTimeInMinutes > failTimeoutInMinutes) {
                    LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: "
                                    + "Failed deployment older than {0} minutes, deleting",
                            failTimeoutInMinutes);
                    // Delete the deployment
                    azureClient.deployments()
                            .deleteByGroup(info.getResourceGroupName(), info.getDeploymentName());
                } else if (state.equalsIgnoreCase("succeeded")
                        && diffTimeInMinutes > successTimeoutInMinutes) {
                    LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: "
                                    + "Succesfull deployment older than {0} minutes, deleting",
                            successTimeoutInMinutes);
                    // Delete the deployment
                    azureClient.deployments()
                            .deleteByGroup(info.getResourceGroupName(), info.getDeploymentName());
                } else {
                    LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: "
                            + "Deployment newer than timeout, keeping");

                    if (firstBackInQueue == null) {
                        firstBackInQueue = info;
                    }
                    // Put it back
                    DEPLOYMENTS_TO_CLEAN.add(info);
                }
            } catch (Exception e) {
                LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: "
                        + "Failed to get/delete deployment: {0}", e.toString());
                // Check the number of attempts remaining. If greater than 0, decrement
                // and add back into the queue.
                if (info.hasAttemptsRemaining()) {
                    info.decrementAttemptsRemaining();

                    if (firstBackInQueue == null) {
                        firstBackInQueue = info;
                    }

                    // Put it back in the queue for another attempt
                    DEPLOYMENTS_TO_CLEAN.add(info);
                }
            }
        }
        LOGGER.log(Level.INFO,
                "AzureVMAgentCleanUpTask: cleanDeployments: Done cleaning deployments");
    }

    /* There are some edge-cases where we might loose track of the provisioned resources:
        1. the process stops right after we start provisioning
        2. some Azure error blocks us from deleting the resource
       This method will look into the resource group and remove all resources
        that have our tag and are not accounted for.
     */
    public final void cleanLeakedResources() {
        Jenkins instance = Jenkins.getInstance();
        if (instance == null) {
            return;
        }
        for (AzureVMCloud cloud : instance.clouds.getAll(AzureVMCloud.class)) {
            cleanLeakedResources(
                    cloud.getResourceGroupName(),
                    cloud.getServicePrincipal(),
                    cloud.name,
                    new DeploymentRegistrar());
        }
    }

    public final List<String> getValidVMs(final String cloudName) {
        List<String> vms = new ArrayList<>();
        Jenkins instance = Jenkins.getInstance();
        if (instance != null) {
            for (Computer computer : instance.getComputers()) {
                if (computer instanceof AzureVMComputer) {
                    AzureVMComputer azureComputer = (AzureVMComputer) computer;
                    AzureVMAgent agent = azureComputer.getNode();
                    if (agent != null && agent.getCloudName().equals(cloudName)) {
                        final String vmName = computer.getName();
                        vms.add(vmName);
                    }
                }
            }
        }
        return vms;
    }

    public final void cleanLeakedResources(
            final String resourceGroup,
            final ServicePrincipal servicePrincipal,
            final String cloudName,
            final DeploymentRegistrar deploymentRegistrar) {
        try {
            final List<String> validVMs = getValidVMs(cloudName);
            final Azure azureClient = TokenCache.getInstance(servicePrincipal).getAzureClient();
            //can't use listByTag because for some reason that method strips all the tags from the
            // outputted resources (https://github.com/Azure/azure-sdk-for-java/issues/1436)
            final PagedList<GenericResource> resources = azureClient
                    .genericResources()
                    .listByGroup(resourceGroup);

            if (resources == null || resources.isEmpty()) {
                return;
            }

            final PriorityQueue<GenericResource> resourcesMarkedForDeletion
                    = new PriorityQueue<>(resources.size(), new Comparator<GenericResource>() {
                @Override
                public int compare(final GenericResource o1, final GenericResource o2) {
                    final int o1Priority = getPriority(o1);
                    final int o2Priority = getPriority(o2);
                    if (o1Priority == o2Priority) {
                        return 0;
                    }
                    if (o1Priority < o2Priority) {
                        return -1;
                    } else {
                        return 1;
                    }
                }

                private static final int VM_PRIORITY = 1;
                private static final int NIC_PRIORITY = 2;
                private static final int IP_PRIORITY = 3;
                private static final int DEFAULT_PRIORITY = 4;

                private int getPriority(final GenericResource resource) {
                    final String type = resource.type();
                    if (StringUtils.containsIgnoreCase(type, "virtualMachine")) {
                        return VM_PRIORITY;
                    }
                    if (StringUtils.containsIgnoreCase(type, "networkInterface")) {
                        return NIC_PRIORITY;
                    }
                    if (StringUtils.containsIgnoreCase(type, "IPAddress")) {
                        return IP_PRIORITY;
                    }
                    return DEFAULT_PRIORITY;
                }
            });

            for (GenericResource resource : resources) {
                final Map<String, String> tags = resource.tags();
                if (!tags.containsKey(Constants.AZURE_RESOURCES_TAG_NAME)
                        || !deploymentRegistrar
                        .getDeploymentTag()
                        .matches(new AzureUtil.DeploymentTag(
                                tags.get(Constants.AZURE_RESOURCES_TAG_NAME)))) {
                    continue;
                }
                boolean shouldSkipDeletion = false;
                for (String validVM : validVMs) {
                    if (resource.name().contains(validVM)) {
                        shouldSkipDeletion = true;
                        break;
                    }
                }
                // we're not removing storage accounts of networks
                //    someone else might be using them
                if (shouldSkipDeletion
                        || StringUtils.containsIgnoreCase(resource.type(), "StorageAccounts")
                        || StringUtils.containsIgnoreCase(resource.type(), "virtualNetworks")) {
                    continue;
                }
                resourcesMarkedForDeletion.add(resource);
            }

            while (!resourcesMarkedForDeletion.isEmpty()) {
                try {
                    final GenericResource resource = resourcesMarkedForDeletion.poll();
                    if (resource == null) {
                        continue;
                    }

                    URI osDiskURI = null;
                    if (StringUtils.containsIgnoreCase(resource.type(), "virtualMachine")) {
                        osDiskURI = new URI(azureClient.virtualMachines()
                                .getById(resource.id())
                                .osUnmanagedDiskVhdUri());
                    }

                    LOGGER.log(Level.INFO,
                            "cleanLeakedResources: deleting {0} from resource group {1}",
                            new Object[]{resource.name(), resourceGroup});
                    azureClient.genericResources().deleteById(resource.id());
                    if (osDiskURI != null) {
                        AzureVMManagementServiceDelegate
                                .removeStorageBlob(azureClient, osDiskURI, resourceGroup);
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanLeakedResources: "
                            + "failed to clean resource ", e);
                }
            }
        } catch (Exception e) {
            // No need to throw exception back, just log and move on.
            LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanLeakedResources: "
                    + "failed to clean leaked resources ", e);
        }
    }

    private void cleanVMs() {
        cleanVMs(new ExecutionEngine());
    }

    private static final int CLEAN_VM_MAX_RETRIES = 3;
    private static final int CLEAN_VM_BACKOFF_IN_SECONDS = 10;
    private static final int CLEAN_VM_TIMEOUT_IN_SECONDS = 30 * 60;

    private void cleanVMs(final ExecutionEngine executionEngine) {
        for (Computer computer : Jenkins.getInstance().getComputers()) {
            if (computer instanceof AzureVMComputer) {
                AzureVMComputer azureComputer = (AzureVMComputer) computer;
                final AzureVMAgent agentNode = azureComputer.getNode();

                // If the machine is not offline, then don't do anything.
                if (!azureComputer.isOffline()) {
                    continue;
                }

                // If the machine is not idle, don't do anything.
                // Could have been taken offline by the plugin while still running
                // builds.
                if (!azureComputer.isIdle()) {
                    continue;
                }

                // Even if offline, a machine that has been temporarily marked offline
                // should stay (this could be for investigation).
                if (azureComputer.isSetOfflineByUser()) {
                    LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanVMs: "
                                    + "node {0} was set offline by user, skipping",
                            agentNode.getDisplayName());
                    continue;
                }

                // If the machine is in "keep" state, skip
                if (agentNode.isCleanUpBlocked()) {
                    LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanVMs: node {0} blocked to cleanup",
                            agentNode.getDisplayName());
                    continue;
                }

                // Check if the virtual machine exists.  If not, it could have been
                // deleted in the background.  Remove from Jenkins if that is the case.
                if (!AzureVMManagementServiceDelegate.virtualMachineExists(agentNode)) {
                    LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanVMs: node {0} doesn't exist, removing",
                            agentNode.getDisplayName());
                    try {
                        Jenkins.getInstance().removeNode(agentNode);
                    } catch (IOException e) {
                        LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanVMs: "
                                        + "node {0} could not be removed: {1}",
                                new Object[]{agentNode.getDisplayName(), e.getMessage()});
                    }
                    continue;
                }

                // Machine exists but is in either DELETE or SHUTDOWN state.
                // Execute that action.
                Callable<Void> task = new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        // Depending on the cleanup action, run the appropriate
                        if (agentNode.getCleanUpAction() == CleanUpAction.DELETE) {
                            LOGGER.log(Level.INFO,
                                    "AzureVMAgentCleanUpTask: cleanVMs: deleting {0}",
                                    agentNode.getDisplayName());
                            agentNode.deprovision(agentNode.getCleanUpReason());
                        } else if (agentNode.getCleanUpAction() == CleanUpAction.SHUTDOWN) {
                            LOGGER.log(Level.INFO,
                                    "AzureVMAgentCleanUpTask: cleanVMs: shutting down {0}",
                                    agentNode.getDisplayName());
                            agentNode.shutdown(agentNode.getCleanUpReason());
                            // We shut down the agent properly.  Mark the agent
                            // as "KEEP" so that it doesn't get deleted.
                            agentNode.blockCleanUpAction();
                        } else {
                            throw new IllegalStateException("Unknown cleanup action");
                        }
                        return null;
                    }
                };

                try {
                    executionEngine.executeAsync(task, new DefaultRetryStrategy(
                            CLEAN_VM_MAX_RETRIES,
                            CLEAN_VM_BACKOFF_IN_SECONDS,
                            CLEAN_VM_TIMEOUT_IN_SECONDS
                    ));
                } catch (AzureCloudException exception) {
                    // No need to throw exception back, just log and move on.
                    LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanVMs: failed to shutdown/delete "
                                    + agentNode.getDisplayName(),
                            exception);
                    // In case the node had a non-delete cleanup action before,
                    // set the cleanup action to delete
                    agentNode.setCleanUpAction(
                            CleanUpAction.DELETE,
                            Messages._Failed_Initial_Shutdown_Or_Delete());
                }
            }
        }
    }

    public final AzureVMCloud getCloud(final String cloudName) {
        if (Jenkins.getInstance() == null) {
            return null;
        } else {
            return (AzureVMCloud) Jenkins.getInstance().getCloud(cloudName);
        }
    }

    @Override
    public final void execute(final TaskListener arg0) throws InterruptedException {
        cleanVMs();
        // Clean up the deployments
        cleanDeployments();

        cleanLeakedResources();
    }

    @Override
    public final long getRecurrencePeriod() {
        return CLEANUP_PERIOD_IN_MINUTES * DateTimeConstants.MILLIS_PER_MINUTE;
    }
}
