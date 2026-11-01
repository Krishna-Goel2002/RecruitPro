package com.recruitpro.user.provisioning;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationContextInitializedEvent;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.env.MockPropertySource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class HrManagerProvisioningEnvironmentPostProcessorTest {
    private final HrManagerProvisioningEnvironmentPostProcessor processor =
            new HrManagerProvisioningEnvironmentPostProcessor();

    @Test
    void disabledFlagDoesNotRequireDevProfileOrDatasource() {
        MockEnvironment environment = configuredEnvironment(false, "prod", null);

        assertDoesNotThrow(() -> process(environment));
    }

    @Test
    void acceptsDevProfileWithLoopbackDatasource() {
        for (String host : new String[] {"127.0.0.1", "localhost", "[::1]"}) {
            MockEnvironment environment = configuredEnvironment(true, "dev",
                    "jdbc:mysql://" + host + ":3307/recruitment_platform_db");
            assertDoesNotThrow(() -> process(environment), "Expected loopback host to pass: " + host);
        }
    }

    @Test
    void rejectsProductionProfile() {
        assertRejected(configuredEnvironment(true, "prod",
                "jdbc:mysql://127.0.0.1:3307/recruitment_platform_db"));
    }

    @Test
    void rejectsNonDevProfile() {
        assertRejected(configuredEnvironment(true, "test",
                "jdbc:mysql://127.0.0.1:3307/recruitment_platform_db"));
    }

    @Test
    void rejectsRemoteDatasource() {
        assertRejected(configuredEnvironment(true, "dev",
                "jdbc:mysql://db.example.test:3306/recruitment_platform_db"));
    }

    @Test
    void rejectsMissingAndUnresolvableDatasourceUrl() {
        assertRejected(configuredEnvironment(true, "dev", null));

        MockEnvironment unresolved = configuredEnvironment(true, "dev", "${MISSING_DB_URL}");
        assertRejected(unresolved);
    }

    @Test
    void rejectsBeforeApplicationContextCanInitialize() {
        SpringApplication application = new SpringApplication(GuardProbeConfiguration.class);
        application.setDefaultProperties(Map.of(
                "spring.config.name", "guard-probe-test",
                "spring.profiles.active", "dev",
                "app.dev-provisioning.hr-manager.enabled", "true",
                "spring.datasource.url", "jdbc:mysql://192.0.2.10:3306/recruitment_platform_db"));
        AtomicBoolean contextInitialized = new AtomicBoolean(false);
        application.addListeners(event -> {
            if (event instanceof ApplicationContextInitializedEvent) contextInitialized.set(true);
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class, application::run);

        assertTrue(failure.getMessage().contains("loopback"));
        assertFalse(contextInitialized.get(), "the environment guard must reject before context initialization");
    }

    private MockEnvironment configuredEnvironment(boolean enabled, String profile, String datasourceUrl) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        MockPropertySource properties = new MockPropertySource()
                .withProperty("app.dev-provisioning.hr-manager.enabled", Boolean.toString(enabled));
        if (datasourceUrl != null) properties.withProperty("spring.datasource.url", datasourceUrl);
        environment.getPropertySources().addFirst(properties);
        return environment;
    }

    private void process(MockEnvironment environment) {
        processor.postProcessEnvironment(environment, new SpringApplication(GuardProbeConfiguration.class));
    }

    private void assertRejected(MockEnvironment environment) {
        assertThrows(IllegalStateException.class, () -> process(environment));
    }

    static class GuardProbeConfiguration { }
}
