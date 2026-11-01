package com.recruitpro.user.provisioning;

import java.util.Arrays;

/** Local-only input for the opt-in HR manager provisioner. */
public final class ProvisioningCredentials {
    private final String email;
    private final String firstName;
    private final String lastName;
    private final char[] password;

    public ProvisioningCredentials(String email, String firstName, String lastName, char[] password) {
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.password = password == null ? null : password.clone();
    }

    public String email() { return email; }
    public String firstName() { return firstName; }
    public String lastName() { return lastName; }
    public char[] password() { return password == null ? null : password.clone(); }

    public void clearPassword() {
        if (password != null) Arrays.fill(password, '\0');
    }

    @Override
    public String toString() {
        return "ProvisioningCredentials[email=" + email + ", firstName=" + firstName
                + ", lastName=" + lastName + ", password=<redacted>]";
    }
}
