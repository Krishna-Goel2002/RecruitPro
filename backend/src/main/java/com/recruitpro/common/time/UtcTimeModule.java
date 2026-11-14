package com.recruitpro.common.time;

import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Jackson module (registered automatically by Spring Boot because it is a {@code Module} bean) that
 * serialises every {@link LocalDateTime} as UTC with a trailing {@code Z} and converts incoming values with
 * an offset to UTC. Registered after the JSR-310 module, so these serializers take precedence.
 */
@Component
public class UtcTimeModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public UtcTimeModule() {
        super("recruitpro-utc-time");
        addSerializer(LocalDateTime.class, new UtcLocalDateTimeSerializer());
        addDeserializer(LocalDateTime.class, new UtcLocalDateTimeDeserializer());
    }
}
