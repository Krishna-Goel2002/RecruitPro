package com.recruitpro.organization.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Seeds the demo organisation when {@code app.seed.demo=true}; never in the prod profile. */
@Component
@Order(20)
public class DevSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeedRunner.class);

    private final DemoDataSeeder seeder;
    private final Environment environment;
    private final boolean enabled;
    private final String password;

    public DevSeedRunner(DemoDataSeeder seeder, Environment environment,
                         @Value("${app.seed.demo:false}") boolean enabled,
                         @Value("${app.seed.password:ChangeMe-123!}") String password) {
        this.seeder = seeder;
        this.environment = environment;
        this.enabled = enabled;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            log.warn("app.seed.demo is ignored in the prod profile");
            return;
        }
        seeder.seed(password);
        try {
            seeder.seedAssessments();
        } catch (RuntimeException ex) {
            log.warn("Demo assessments (kit, work sample, interviews) could not be seeded: {}", ex.getClass().getSimpleName(), ex);
        }
    }
}
