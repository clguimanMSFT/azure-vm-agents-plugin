/*
 * Copyright 2016 mmitche.
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
package com.microsoft.azure.vmagent;

/**
 * Simple class with info from a new Azure deployment
 *
 * @author mmitche
 */
public class AzureVMDeploymentInfo {

    private final String deploymentName;
    private final String vmBaseName;
    private final int vmCount;

    public AzureVMDeploymentInfo(final String deploymentName, final String vmBaseName, final int vmCount) {
        this.deploymentName = deploymentName;
        this.vmBaseName = vmBaseName;
        this.vmCount = vmCount;
    }

    public final String getDeploymentName() {
        return deploymentName;
    }

    public final String getVmBaseName() {
        return vmBaseName;
    }

    public final int getVmCount() {
        return vmCount;
    }

}
