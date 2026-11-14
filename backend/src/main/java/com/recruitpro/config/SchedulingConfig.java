package com.recruitpro.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduled background job worker (D7) and notification sender (D13). The scheduler itself is
 * Spring Boot's auto-configured {@code taskScheduler}, sized by {@code spring.task.scheduling.pool.size}
 * (4 threads) so that a slow AI call never delays e-mail delivery.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
