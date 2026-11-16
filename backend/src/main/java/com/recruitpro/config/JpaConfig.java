package com.recruitpro.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.recruitpro.common.time.UtcTime;

import java.time.temporal.TemporalAccessor;
import java.util.Optional;

/**
 * JPA Configuration enabling automatic entity auditing (@CreatedDate, @LastModifiedDate) in UTC (D15).
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "utcDateTimeProvider")
public class JpaConfig {

    @Bean
    public DateTimeProvider utcDateTimeProvider() {
        return () -> Optional.<TemporalAccessor>of(UtcTime.now());
    }
}
