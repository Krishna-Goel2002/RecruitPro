package com.recruitpro.verification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Seeds the verification demo data after the demo organisation and accounts ({@code DevSeedRunner}, order 20) when
 * {@code app.seed.demo=true}; never in the prod profile. A failure is logged, never fatal.
 */
@Component
@Order(30)
public class VerificationDemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VerificationDemoSeedRunner.class);

    private final VerificationDemoSeeder seeder;
    private final Environment environment;
    private final boolean enabled;

    public VerificationDemoSeedRunner(VerificationDemoSeeder seeder, Environment environment,
                                      @Value("${app.seed.demo:false}") boolean enabled) {
        this.seeder = seeder;
        this.environment = environment;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        try {
            seeder.seed();
        } catch (RuntimeException ex) {
            log.warn("Verification demo data could not be seeded: {}", ex.getClass().getSimpleName(), ex);
        }
    }
}
