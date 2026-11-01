package com.recruitpro.user.provisioning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Validates the opt-in HR manager provisioner before an application context is created.
 * This runs after Boot has loaded profile-specific configuration, but before datasource,
 * Flyway, or Hibernate beans can be initialized.
 */
public class HrManagerProvisioningEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String ENABLED_PROPERTY = "app.dev-provisioning.hr-manager.enabled";
    private static final String DATASOURCE_URL_PROPERTY = "spring.datasource.url";

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty(ENABLED_PROPERTY, Boolean.class, false)) {
            return;
        }

        if (!environment.acceptsProfiles(Profiles.of("dev"))
                || environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(
                    "HR manager provisioning requires the dev profile and is forbidden with prod");
        }

        final String configuredUrl;
        try {
            configuredUrl = environment.getProperty(DATASOURCE_URL_PROPERTY);
        } catch (IllegalArgumentException unresolved) {
            throw new IllegalStateException(
                    "HR manager provisioning requires a resolvable local datasource URL", unresolved);
        }
        if (configuredUrl == null || configuredUrl.isBlank()) {
            throw new IllegalStateException("HR manager provisioning requires an explicitly configured local datasource URL");
        }

        final String resolvedUrl;
        try {
            resolvedUrl = environment.resolveRequiredPlaceholders(configuredUrl).trim();
        } catch (IllegalArgumentException unresolved) {
            throw new IllegalStateException(
                    "HR manager provisioning requires a resolvable local datasource URL", unresolved);
        }

        if (!targetsLoopbackMySql(resolvedUrl)) {
            throw new IllegalStateException(
                    "HR manager provisioning requires a MySQL datasource on a local loopback address");
        }
    }

    static boolean targetsLoopbackMySql(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.regionMatches(true, 0, "jdbc:mysql://", 0, "jdbc:mysql://".length())) {
            return false;
        }

        try {
            URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
            String host = uri.getHost();
            return host != null && isLoopbackHost(host);
        } catch (IllegalArgumentException malformedUrl) {
            return false;
        }
    }

    private static boolean isLoopbackHost(String host) {
        String unwrapped = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;

        if ("localhost".equalsIgnoreCase(unwrapped)) {
            try {
                InetAddress[] resolved = InetAddress.getAllByName(unwrapped);
                if (resolved.length == 0) return false;
                for (InetAddress address : resolved) {
                    if (!address.isLoopbackAddress()) return false;
                }
                return true;
            } catch (UnknownHostException unresolvedLocalhost) {
                return false;
            }
        }

        if (unwrapped.matches("[0-9.]+")) {
            String[] octets = unwrapped.split("\\.", -1);
            if (octets.length != 4) return false;
            try {
                int firstOctet = Integer.parseInt(octets[0]);
                for (String octet : octets) {
                    int value = Integer.parseInt(octet);
                    if (value < 0 || value > 255) return false;
                }
                return firstOctet == 127;
            } catch (NumberFormatException invalidIpv4) {
                return false;
            }
        }

        if (!unwrapped.matches("[0-9a-fA-F:.]+") || !unwrapped.contains(":")) return false;
        try {
            return InetAddress.getByName(unwrapped).isLoopbackAddress();
        } catch (UnknownHostException invalidIpv6) {
            return false;
        }
    }
}
