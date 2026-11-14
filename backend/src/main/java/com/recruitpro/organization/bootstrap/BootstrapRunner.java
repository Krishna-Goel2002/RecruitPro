package com.recruitpro.organization.bootstrap;

import com.recruitpro.auth.validation.PasswordPolicy;
import com.recruitpro.organization.service.OrganizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * First-account path (b): when no organisation exists and BOOTSTRAP_ORG_NAME, BOOTSTRAP_ADMIN_EMAIL and
 * BOOTSTRAP_ADMIN_PASSWORD are all set, creates the organisation and its administrator (any profile).
 * Replaces the former interactive console provisioners.
 */
@Component
@Order(10)
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final OrganizationService organizationService;
    private final String organizationName;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminFirstName;
    private final String adminLastName;

    public BootstrapRunner(OrganizationService organizationService,
                           @Value("${app.bootstrap.org-name:}") String organizationName,
                           @Value("${app.bootstrap.admin-email:}") String adminEmail,
                           @Value("${app.bootstrap.admin-password:}") String adminPassword,
                           @Value("${app.bootstrap.admin-first-name:Organisation}") String adminFirstName,
                           @Value("${app.bootstrap.admin-last-name:Admin}") String adminLastName) {
        this.organizationService = organizationService;
        this.organizationName = organizationName;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminFirstName = adminFirstName;
        this.adminLastName = adminLastName;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean anySet = hasText(organizationName) || hasText(adminEmail) || hasText(adminPassword);
        if (!anySet) {
            return;
        }
        if (!hasText(organizationName) || !hasText(adminEmail) || !hasText(adminPassword)) {
            log.warn("Bootstrap skipped: BOOTSTRAP_ORG_NAME, BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD must all be set");
            return;
        }
        if (!PasswordPolicy.isValid(adminPassword)) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD does not meet the password policy: " + PasswordPolicy.MESSAGE);
        }
        boolean created = organizationService.bootstrapFirstOrganization(organizationName.trim(), adminEmail.trim(),
                adminPassword, adminFirstName, adminLastName);
        if (!created) {
            log.info("Bootstrap not needed: an organisation already exists");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
