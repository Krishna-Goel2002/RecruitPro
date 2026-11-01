package com.recruitpro.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Configuration enabling automatic entity auditing (@CreatedDate, @LastModifiedDate).
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
