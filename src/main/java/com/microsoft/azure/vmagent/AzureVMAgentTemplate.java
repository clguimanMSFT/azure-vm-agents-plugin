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

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.azure.util.AzureCredentials.ServicePrincipal;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.FailureStage;
import hudson.Extension;
import hudson.RelativePath;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class defines the configuration of Azure instance templates
 *
 * @author Suresh Nallamilli
 */
public class AzureVMAgentTemplate implements Describable<AzureVMAgentTemplate> {

    public enum ImageReferenceType {
        UNKNOWN,
        CUSTOM,
        REFERENCE,
    }

    private static final Logger LOGGER = Logger.getLogger(AzureVMAgentTemplate.class.getName());

    // General Configuration
    private final String templateName;

    private final String templateDesc;

    private final String labels;

    private final String location;

    private final String virtualMachineSize;

    private String storageAccountName;

    private final int noOfParallelJobs;

    private Node.Mode usageMode;

    private final boolean shutdownOnIdle;

    // Image Configuration
    private final String imageReferenceType;

    private final String image;

    private final String osType;

    private final String imagePublisher;

    private final String imageOffer;

    private final String imageSku;

    private final String imageVersion;

    private final String agentLaunchMethod;

    private final String initScript;

    private final String credentialsId;

    private final String agentWorkspace;

    private final int retentionTimeInMin;

    private String virtualNetworkName;

    private String subnetName;

    private boolean usePrivateIP;

    private final String jvmOptions;

    // Indicates whether the template is disabled.
    // If disabled, will not attempt to verify or use
    private final boolean templateDisabled;

    private String templateStatusDetails;

    private transient AzureVMCloud azureCloud;

    private transient Set<LabelAtom> labelDataSet;

    private boolean templateVerified;

    private boolean executeInitScriptAsRoot;

    private boolean doNotUseMachineIfInitFails;

    @DataBoundConstructor
    public AzureVMAgentTemplate(
            final String templateName,
            final String templateDesc,
            final String labels,
            final String location,
            final String virtualMachineSize,
            final String storageAccountName,
            final String noOfParallelJobs,
            final String usageMode,
            final String imageReferenceType,
            final String image,
            final String osType,
            final boolean imageReference,
            final String imagePublisher,
            final String imageOffer,
            final String imageSku,
            final String imageVersion,
            final String agentLaunchMethod,
            final String initScript,
            final String credentialsId,
            final String virtualNetworkName,
            final String subnetName,
            final boolean usePrivateIP,
            final String agentWorkspace,
            final String jvmOptions,
            final String retentionTimeInMin,
            final boolean shutdownOnIdle,
            final boolean templateDisabled,
            final String templateStatusDetails,
            final boolean executeInitScriptAsRoot,
            final boolean doNotUseMachineIfInitFails) {
        this.templateName = templateName;
        this.templateDesc = templateDesc;
        this.labels = labels;
        this.location = location;
        this.virtualMachineSize = virtualMachineSize;
        this.storageAccountName = storageAccountName;

        if (StringUtils.isBlank(noOfParallelJobs) || !noOfParallelJobs.matches(Constants.REG_EX_DIGIT)
                || noOfParallelJobs.
                trim().equals("0")) {
            this.noOfParallelJobs = 1;
        } else {
            this.noOfParallelJobs = Integer.parseInt(noOfParallelJobs);
        }
        setUsageMode(usageMode);
        this.imageReferenceType = imageReferenceType;
        this.image = image;
        this.osType = osType;
        this.imagePublisher = imagePublisher;
        this.imageOffer = imageOffer;
        this.imageSku = imageSku;
        this.imageVersion = imageVersion;
        this.shutdownOnIdle = shutdownOnIdle;
        this.initScript = initScript;
        this.agentLaunchMethod = agentLaunchMethod;
        this.credentialsId = credentialsId;
        this.virtualNetworkName = virtualNetworkName;
        this.subnetName = subnetName;
        this.usePrivateIP = usePrivateIP;
        this.agentWorkspace = agentWorkspace;
        this.jvmOptions = jvmOptions;
        this.executeInitScriptAsRoot = executeInitScriptAsRoot;
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
        if (StringUtils.isBlank(retentionTimeInMin) || !retentionTimeInMin.matches(Constants.REG_EX_DIGIT)) {
            this.retentionTimeInMin = Constants.DEFAULT_IDLE_TIME;
        } else {
            this.retentionTimeInMin = Integer.parseInt(retentionTimeInMin);
        }
        this.templateDisabled = templateDisabled;
        this.templateStatusDetails = "";

        // Reset the template verification status.
        this.templateVerified = false;

        // Forms data which is not persisted
        readResolve();
    }

    public final String isType(final String type) {
        if (this.imageReferenceType == null && type.equals("reference")) {
            return "true";
        }
        if (type != null && type.equalsIgnoreCase(this.imageReferenceType)) {
            return "true";
        } else {
            return "false";
        }
    }

    private Object readResolve() {
        labelDataSet = Label.parse(labels);
        return this;
    }

    public final String getLabels() {
        return labels;
    }

    public final String getLocation() {
        return location;
    }

    public final String getVirtualMachineSize() {
        return virtualMachineSize;
    }

    public final String getStorageAccountName() {
        return storageAccountName;
    }

    public final void setStorageAccountName(final String storageAccountName) {
        this.storageAccountName = storageAccountName;
    }

    public final Node.Mode getUseAgentAlwaysIfAvail() {
        if (usageMode == null) {
            return Node.Mode.NORMAL;
        } else {
            return usageMode;
        }
    }

    public final String getUsageMode() {
        return getUseAgentAlwaysIfAvail().getDescription();
    }

    public final void setUsageMode(final String mode) {
        Node.Mode val = Node.Mode.NORMAL;
        for (Node.Mode m : hudson.Functions.getNodeModes()) {
            if (mode.equalsIgnoreCase(m.getDescription())) {
                val = m;
                break;
            }
        }
        this.usageMode = val;
    }

    public final boolean isShutdownOnIdle() {
        return shutdownOnIdle;
    }

    public final String getImageReferenceType() {
        return imageReferenceType;
    }

    public final String getImage() {
        return image;
    }

    public final String getOsType() {
        return osType;
    }

    public final String getImagePublisher() {
        return imagePublisher;
    }

    public final String getImageOffer() {
        return imageOffer;
    }

    public final String getImageSku() {
        return imageSku;
    }

    public final String getImageVersion() {
        return imageVersion;
    }

    public final String getInitScript() {
        return initScript;
    }

    public final String getCredentialsId() {
        return credentialsId;
    }

    public final StandardUsernamePasswordCredentials getVMCredentials() throws AzureCloudException {
        return AzureUtil.getCredentials(credentialsId);
    }

    public final String getVirtualNetworkName() {
        return virtualNetworkName;
    }

    public final void setVirtualNetworkName(final String virtualNetworkName) {
        this.virtualNetworkName = virtualNetworkName;
    }

    public final getSubnetName() {
        return subnetName;
    }

    public final void setSubnetName(final String subnetName) {
        this.subnetName = subnetName;
    }

    public final boolean getUsePrivateIP() {
        return usePrivateIP;
    }

    public final String getAgentWorkspace() {
        return agentWorkspace;
    }

    public final int getRetentionTimeInMin() {
        return retentionTimeInMin;
    }

    public final String getJvmOptions() {
        return jvmOptions;
    }

    public final AzureVMCloud getAzureCloud() {
        return azureCloud;
    }

    public final void setAzureCloud(final AzureVMCloud cloud) {
        azureCloud = cloud;
        if (StringUtils.isBlank(storageAccountName)) {
            storageAccountName = AzureVMAgentTemplate.generateUniqueStorageAccountName(
                    azureCloud.getResourceGroupName(),
                    azureCloud.getServicePrincipal());
        }
    }

    public final String getTemplateName() {
        return templateName;
    }

    public final String getTemplateDesc() {
        return templateDesc;
    }

    public final int getNoOfParallelJobs() {
        return noOfParallelJobs;
    }

    public final String getAgentLaunchMethod() {
        return agentLaunchMethod;
    }

    /**
     * Returns true if this template is disabled and cannot be used, false otherwise.
     *
     * @return True/false
     */
    public final boolean isTemplateDisabled() {
        return this.templateDisabled;
    }

    /**
     * Is the template set up and verified?
     *
     * @return True if the template is set up and verified, false otherwise.
     */
    public final boolean isTemplateVerified() {
        return templateVerified;
    }

    /**
     * Set the template verification status
     *
     * @param isValid True for verified + valid, false otherwise.
     */
    public final void setTemplateVerified(final boolean isValid) {
        templateVerified = isValid;
    }

    public final String getTemplateStatusDetails() {
        return templateStatusDetails;
    }

    public final void setTemplateStatusDetails(final String templateStatusDetails) {
        this.templateStatusDetails = templateStatusDetails;
    }

    public final String getResourceGroupName() {
        // Allow overriding?
        return getAzureCloud().getResourceGroupName();
    }

    public final boolean getExecuteInitScriptAsRoot() {
        return executeInitScriptAsRoot;
    }

    public final void setExecuteInitScriptAsRoot(final boolean executeAsRoot) {
        executeInitScriptAsRoot = executeAsRoot;
    }

    public final boolean getDoNotUseMachineIfInitFails() {
        return doNotUseMachineIfInitFails;
    }

    public final void setDoNotUseMachineIfInitFails(final boolean doNotUseMachineIfInitFails) {
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
    }

    @SuppressWarnings("unchecked")
    public final Descriptor<AzureVMAgentTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    public final Set<LabelAtom> getLabelDataSet() {
        return labelDataSet;
    }

    /**
     * Provision new agents using this template.
     *
     * @param listener
     * @param numberOfAgents Number of agents to provision
     * @return New deployment info if the provisioning was successful.
     * @throws Exception May throw if provisioning was not successful.
     */
    public final AzureVMDeploymentInfo provisionAgents(
            final TaskListener listener,
            final int numberOfAgents) throws Exception {
        return AzureVMManagementServiceDelegate.createDeployment(this, numberOfAgents);
    }

    /**
     * If provisioning failed, handle the status and queue the template for verification.
     *
     * @param message     Failure message
     * @param failureStep Stage that failure occurred
     */
    public final void handleTemplateProvisioningFailure(final String message, final FailureStage failureStep) {
        // The template is bad.  It should have already been verified, but
        // perhaps something changed (VHD gone, etc.).  Queue for verification.
        setTemplateVerified(false);
        AzureVMCloudVerificationTask.registerTemplate(this);
        // Set the details so that it's easier to see what's going on from the configuration UI.
        setTemplateStatusDetails(message);
    }

    private ImageReferenceType getImgReferenceType() {
        if (imageReferenceType == null) {
            return ImageReferenceType.UNKNOWN;
        } else {
            if (imageReferenceType.equals("custom")) {
                return ImageReferenceType.CUSTOM;
            } else {
                return ImageReferenceType.REFERENCE;
            }
        }
    }

    /**
     * Verify that this template is correct and can be allocated.
     *
     * @return Empty list if this template is valid, list of errors otherwise
     * @throws Exception
     */
    public final List<String> verifyTemplate() throws Exception {
        return AzureVMManagementServiceDelegate.verifyTemplate(azureCloud.getServicePrincipal(),
                templateName,
                labels,
                location,
                virtualMachineSize,
                storageAccountName,
                noOfParallelJobs + "",
                getImgReferenceType(),
                image,
                osType,
                imagePublisher,
                imageOffer,
                imageSku,
                imageVersion,
                agentLaunchMethod,
                initScript,
                credentialsId,
                virtualNetworkName,
                subnetName,
                retentionTimeInMin + "",
                jvmOptions,
                getResourceGroupName(),
                true,
                usePrivateIP);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<AzureVMAgentTemplate> {

        @Override
        public String getDisplayName() {
            return "";
        }

        public ListBoxModel doFillVirtualMachineSizeItems(
                @RelativePath("..") @QueryParameter final String azureCredentialsId,
                @QueryParameter final String location)
                throws IOException, ServletException {

            AzureCredentials.ServicePrincipal servicePrincipal
                    = AzureCredentials.getServicePrincipal(azureCredentialsId);
            ListBoxModel model = new ListBoxModel();
            List<String> vmSizes = AzureVMManagementServiceDelegate.getVMSizes(servicePrincipal, location);

            if (vmSizes != null) {
                for (String vmSize : vmSizes) {
                    model.add(vmSize);
                }
            }
            return model;
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath final Item owner) {
            // when configuring the job, you only want those credentials that are available to ACL.SYSTEM selectable
            // as we cannot select from a user's credentials unless they are the only user submitting the build
            // (which we cannot assume) thus ACL.SYSTEM is correct here.
            return new StandardListBoxModel()
                    .withAll(
                            CredentialsProvider
                                    .lookupCredentials(
                                            StandardUsernamePasswordCredentials.class,
                                            owner,
                                            ACL.SYSTEM,
                                            Collections.<DomainRequirement>emptyList()
                                    )
                    );
        }

        public ListBoxModel doFillOsTypeItems() throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.OS_TYPE_LINUX);
            model.add(Constants.OS_TYPE_WINDOWS);
            return model;
        }

        public ListBoxModel doFillLocationItems(
                @RelativePath("..") @QueryParameter final String azureCredentialsId)
                throws IOException, ServletException {
            AzureCredentials.ServicePrincipal servicePrincipal
                    = AzureCredentials.getServicePrincipal(azureCredentialsId);

            ListBoxModel model = new ListBoxModel();

            Set<String> locations = AzureVMManagementServiceDelegate.getVirtualMachineLocations(servicePrincipal);

            for (String location : locations) {
                model.add(location);
            }

            return model;
        }

        public ListBoxModel doFillUsageModeItems() throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            for (Node.Mode m : hudson.Functions.getNodeModes()) {
                model.add(m.getDescription());
            }
            return model;
        }

        public ListBoxModel doFillAgentLaunchMethodItems() {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.LAUNCH_METHOD_SSH);
            model.add(Constants.LAUNCH_METHOD_JNLP);

            return model;
        }

        public FormValidation doCheckInitScript(
                @QueryParameter final String value,
                @QueryParameter final String agentLaunchMethod) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.warningWithMarkup(Messages.Azure_GC_InitScript_Warn_Msg());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckStorageAccountName(@QueryParameter final String value) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.ok(Messages.SA_Blank_Create_New());
            }
            return FormValidation.ok();
        }

        public FormValidation doAgentLaunchMethod(@QueryParameter final String value) {
            if (Constants.LAUNCH_METHOD_JNLP.equals(value)) {
                return FormValidation.warning(Messages.Azure_GC_LaunchMethod_Warn_Msg());
            }
            return FormValidation.ok();
        }

        /**
         * Check the template's name. Name must conform to restrictions on VM naming
         *
         * @param value            Current name
         * @param templateDisabled Is the template disabled
         * @param osType           OS type
         * @return
         */
        public FormValidation doCheckTemplateName(
                @QueryParameter final String value, @QueryParameter final boolean templateDisabled,
                @QueryParameter final String osType) {
            List<FormValidation> errors = new ArrayList<>();
            // Check whether the template name is valid, and then check
            // whether it would be shortened on VM creation.
            if (!AzureUtil.isValidTemplateName(value)) {
                errors.add(FormValidation.error(Messages.Azure_GC_Template_Name_Not_Valid()));
            }

            if (templateDisabled) {
                errors.add(FormValidation.warning(Messages.Azure_GC_TemplateStatus_Warn_Msg()));
            }

            if (errors.size() > 0) {
                return FormValidation.aggregate(errors);
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckNoOfParallelJobs(@QueryParameter final String value) {
            if (StringUtils.isNotBlank(value)) {
                String result = AzureVMManagementServiceDelegate.verifyNoOfExecutors(value);

                if (result.equalsIgnoreCase(Constants.OP_SUCCESS)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(result);
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckRetentionTimeInMin(@QueryParameter final String value) {
            if (StringUtils.isNotBlank(value)) {
                String result = AzureVMManagementServiceDelegate.verifyRetentionTime(value);

                if (result.equalsIgnoreCase(Constants.OP_SUCCESS)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(result);
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckAdminPassword(@QueryParameter final String value) {
            if (StringUtils.isNotBlank(value)) {
                if (AzureUtil.isValidPassword(value)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(Messages.Azure_GC_Password_Err());
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckJvmOptions(@QueryParameter final String value) {
            if (StringUtils.isNotBlank(value)) {
                if (AzureUtil.isValidJvmOption(value)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(Messages.Azure_GC_JVM_Option_Err());
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doVerifyConfiguration(
                @RelativePath("..") @QueryParameter final String azureCredentialsId,
                @RelativePath("..") @QueryParameter final String resourceGroupName,
                @RelativePath("..") @QueryParameter final String maxVirtualMachinesLimit,
                @RelativePath("..") @QueryParameter final String deploymentTimeout,
                @QueryParameter final String templateName,
                @QueryParameter final String labels,
                @QueryParameter final String location,
                @QueryParameter final String virtualMachineSize,
                @QueryParameter final String storageAccountName,
                @QueryParameter final String noOfParallelJobs,
                @QueryParameter final String image,
                @QueryParameter final String osType,
                @QueryParameter final String imagePublisher,
                @QueryParameter final String imageOffer,
                @QueryParameter final String imageSku,
                @QueryParameter final String imageVersion,
                @QueryParameter final String agentLaunchMethod,
                @QueryParameter final String initScript,
                @QueryParameter final String credentialsId,
                @QueryParameter final String virtualNetworkName,
                @QueryParameter final String subnetName,
                @QueryParameter final boolean usePrivateIP,
                @QueryParameter final String retentionTimeInMin,
                @QueryParameter final String jvmOptions,
                @QueryParameter final String imageReferenceType) {

            /*
            imageReferenceType will not be passed to doVerifyConfiguration unless Jenkins core has
             https://github.com/jenkinsci/jenkins/pull/2734
            The plugin should be able to run in both modes.
             */
            ImageReferenceType referenceType = ImageReferenceType.UNKNOWN;
            if (imageReferenceType != null) {
                if (imageReferenceType.equals("custom")) {
                    referenceType = ImageReferenceType.CUSTOM;
                } else {
                    referenceType = ImageReferenceType.REFERENCE;
                }
            }

            AzureCredentials.ServicePrincipal servicePrincipal
                    = AzureCredentials.getServicePrincipal(azureCredentialsId);
            if (storageAccountName.trim().isEmpty()) {
                storageAccountName = AzureVMAgentTemplate.generateUniqueStorageAccountName(
                        resourceGroupName,
                        servicePrincipal);
            }
            String hiddenClientId = null;
            String hiddenClientSecret = null;
            if (StringUtils.isNotBlank(servicePrincipal.getClientId())) {
                hiddenClientId = "********";
            }
            if (StringUtils.isNotBlank(servicePrincipal.getClientSecret())) {
                hiddenClientSecret = "********";
            }
            LOGGER.log(Level.INFO,
                    "Verify configuration:\n\t"
                            + "subscriptionId: {0};\n\t"
                            + "clientId: {1};\n\t"
                            + "clientSecret: {2};\n\t"
                            + "serviceManagementURL: {3};\n\t"
                            + "resourceGroupName: {4};\n\t."
                            + "templateName: {5};\n\t"
                            + "labels: {6};\n\t"
                            + "location: {7};\n\t"
                            + "virtualMachineSize: {8};\n\t"
                            + "storageAccountName: {9};\n\t"
                            + "noOfParallelJobs: {10};\n\t"
                            + "image: {11};\n\t"
                            + "osType: {12};\n\t"
                            + "imagePublisher: {13};\n\t"
                            + "imageOffer: {14};\n\t"
                            + "imageSku: {15};\n\t"
                            + "imageVersion: {16};\n\t"
                            + "agentLaunchMethod: {17};\n\t"
                            + "initScript: {18};\n\t"
                            + "credentialsId: {19};\n\t"
                            + "virtualNetworkName: {20};\n\t"
                            + "subnetName: {21};\n\t"
                            + "privateIP: {22};\n\t"
                            + "retentionTimeInMin: {23};\n\t"
                            + "jvmOptions: {24};",
                    new Object[]{
                            servicePrincipal.getSubscriptionId(),
                            hiddenClientId,
                            hiddenClientSecret,
                            servicePrincipal.getServiceManagementURL(),
                            resourceGroupName,
                            templateName,
                            labels,
                            location,
                            virtualMachineSize,
                            storageAccountName,
                            noOfParallelJobs,
                            image,
                            osType,
                            imagePublisher,
                            imageOffer,
                            imageSku,
                            imageVersion,
                            agentLaunchMethod,
                            initScript,
                            credentialsId,
                            virtualNetworkName,
                            subnetName,
                            usePrivateIP,
                            retentionTimeInMin,
                            jvmOptions});

            // First validate the subscription info.  If it is not correct,
            // then we can't validate the
            String result = AzureVMManagementServiceDelegate.verifyConfiguration(servicePrincipal, resourceGroupName,
                    maxVirtualMachinesLimit, deploymentTimeout);
            if (!result.equals(Constants.OP_SUCCESS)) {
                return FormValidation.error(result);
            }

            final List<String> errors = AzureVMManagementServiceDelegate.verifyTemplate(
                    servicePrincipal,
                    templateName,
                    labels,
                    location,
                    virtualMachineSize,
                    storageAccountName,
                    noOfParallelJobs,
                    referenceType,
                    image,
                    osType,
                    imagePublisher,
                    imageOffer,
                    imageSku,
                    imageVersion,
                    agentLaunchMethod,
                    initScript,
                    credentialsId,
                    virtualNetworkName,
                    subnetName,
                    retentionTimeInMin,
                    jvmOptions,
                    resourceGroupName,
                    false,
                    usePrivateIP);

            if (errors.size() > 0) {
                StringBuilder errorString = new StringBuilder(Messages.Azure_GC_Template_Error_List()).append("\n");

                for (int i = 0; i < errors.size(); i++) {
                    errorString.append(i + 1).append(": ").append(errors.get(i)).append("\n");
                }

                return FormValidation.error(errorString.toString());

            } else {
                return FormValidation.ok(Messages.Azure_Template_Config_Success());
            }
        }

        public String getDefaultNoOfExecutors() {
            return "1";
        }
    }

    public final void setVirtualMachineDetails(final AzureVMAgent agent) throws Exception {
        AzureVMManagementServiceDelegate.setVirtualMachineDetails(agent, this);
    }

    private static final int STORAGE_ACCOUNT_MAX_NAME_LENGTH = 24;

    public static String generateUniqueStorageAccountName(
            final String resourceGroupName,
            final ServicePrincipal servicePrincipal) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            if (null != servicePrincipal && !StringUtils.isEmpty(servicePrincipal.getSubscriptionId())) {
                md.update(servicePrincipal.getSubscriptionId().getBytes("UTF-8"));
            }
            if (null != resourceGroupName) {
                md.update(resourceGroupName.getBytes("UTF-8"));
            }

            String uid = DatatypeConverter.printBase64Binary(md.digest());
            uid = uid.substring(0, STORAGE_ACCOUNT_MAX_NAME_LENGTH - 2);
            uid = uid.toLowerCase();
            uid = uid.replaceAll("[^a-z0-9]", "a");
            return "jn" + uid;
        } catch (UnsupportedEncodingException | NoSuchAlgorithmException e) {
            LOGGER.log(Level.WARNING, "Could not generate UID from the resource group name. "
                    + "Will fallback on using the resource group name.", e);
            return "";
        }
    }
}
