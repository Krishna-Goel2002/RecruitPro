package com.recruitpro.user.provisioning;

public interface AdminProvisioningPrompt {
    ProvisioningCredentials collect();
    void completed(String email);
}
