package com.recruitpro.user.provisioning;

import org.springframework.stereotype.Component;

import java.io.Console;
import java.util.Arrays;

@Component
public class ConsoleHrManagerProvisioningPrompt implements HrManagerProvisioningPrompt {

    @Override
    public ProvisioningCredentials collect() {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("HR provisioning requires an interactive local console");
        }

        String email = console.readLine("HR manager email: ");
        String firstName = console.readLine("First name: ");
        String lastName = console.readLine("Last name: ");
        char[] password = null;
        char[] confirmation = null;
        try {
            password = console.readPassword("Password (8 to 72 characters): ");
            confirmation = console.readPassword("Confirm password: ");
            if (password == null || confirmation == null || !Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("Password confirmation did not match");
            }
            return new ProvisioningCredentials(email, firstName, lastName, password);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }

    @Override
    public void completed(String email) {
        Console console = System.console();
        if (console != null) console.printf("Provisioned local HR manager account for %s%n", email);
    }
}
