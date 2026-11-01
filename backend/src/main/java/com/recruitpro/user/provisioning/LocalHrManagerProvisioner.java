package com.recruitpro.user.provisioning;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LocalHrManagerProvisioner implements ApplicationRunner {
    private final Environment environment;
    private final HrManagerProvisioningService provisioningService;
    private final HrManagerProvisioningPrompt prompt;

    @Value("${app.dev-provisioning.hr-manager.enabled:false}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (!environment.acceptsProfiles(Profiles.of("dev"))
                || environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("HR manager provisioning is allowed only with the dev profile and never with prod");
        }

        ProvisioningCredentials credentials = prompt.collect();
        try {
            char[] password = credentials.password();
            try {
                provisioningService.provision(credentials.email(), credentials.firstName(), credentials.lastName(), password);
            } finally {
                if (password != null) java.util.Arrays.fill(password, '\0');
            }
            prompt.completed(credentials.email());
        } finally {
            credentials.clearPassword();
        }
    }
}
