package com.recruitpro.security.ratelimit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Locale;

/** Chooses the rate-limit store from {@code app.rate-limit.store} ({@code memory}, the default, or {@code database}). */
@Configuration
public class RateLimitStoreConfig {

    @Bean
    public RateLimiterStore rateLimiterStore(@Value("${app.rate-limit.store:memory}") String store,
                                             ObjectProvider<JdbcTemplate> jdbcTemplate,
                                             ObjectProvider<PlatformTransactionManager> transactionManager) {
        String kind = store == null ? "memory" : store.trim().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "", "memory" -> new InMemoryRateLimiterStore();
            case "database" -> new DatabaseRateLimiterStore(jdbcTemplate.getObject(), transactionManager.getObject());
            default -> throw new IllegalStateException("app.rate-limit.store must be 'memory' or 'database', not '" + store + "'");
        };
    }
}
