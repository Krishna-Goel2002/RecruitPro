package com.recruitpro.user.provisioning;

public interface HrManagerProvisioningPrompt {
    ProvisioningCredentials collect();
    void completed(String email);
}
