package com.recruitpro.user.provisioning;

import com.recruitpro.user.entity.Role;
import com.recruitpro.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
@RequiredArgsConstructor
public class LocalAdminProvisioner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LocalAdminProvisioner.class);

    private final Environment environment;
    private final AdminProvisioningService provisioningService;
    private final AdminProvisioningPrompt prompt;
    private final UserRepository userRepository;

    @Value("${app.dev-provisioning.admin.enabled:false}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (!environment.acceptsProfiles(Profiles.of("dev"))
                || environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("Admin provisioning is allowed only with the dev profile and never with prod");
        }

        if (userRepository.countByRole(Role.ADMIN) > 0) {
            log.info("Administrator account already exists; skipping bootstrap provisioning.");
            return;
        }

        ProvisioningCredentials credentials = prompt.collect();
        try {
            char[] password = credentials.password();
            try {
                provisioningService.provision(credentials.email(), credentials.firstName(), credentials.lastName(), password);
            } finally {
                if (password != null) Arrays.fill(password, '\0');
            }
            prompt.completed(credentials.email());
        } finally {
            credentials.clearPassword();
        }
    }
}
