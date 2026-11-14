package com.recruitpro.common.time;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Reads ISO-8601 strings with or without an offset into UTC {@link LocalDateTime} values
 * ({@code 2026-11-08T12:15:30+02:00} becomes {@code 2026-11-08T10:15:30}).
 */
public class UtcLocalDateTimeDeserializer extends StdDeserializer<LocalDateTime> {

    private static final long serialVersionUID = 1L;

    public UtcLocalDateTimeDeserializer() {
        super(LocalDateTime.class);
    }

    @Override
    public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String text = parser.getValueAsString();
        if (text == null) {
            return (LocalDateTime) context.handleUnexpectedToken(LocalDateTime.class, parser);
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return UtcTime.parse(trimmed);
        } catch (DateTimeParseException ex) {
            throw context.weirdStringException(trimmed, LocalDateTime.class, "expected an ISO-8601 date-time");
        }
    }
}
