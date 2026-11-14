package com.recruitpro.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Small helpers for JSON stored in TEXT columns (plan 3.1: no JSONB). Never throw on bad stored data. */
public final class JsonText {

    private JsonText() {
    }

    /** Parses stored JSON; null for null, blank or unreadable text. */
    public static JsonNode read(ObjectMapper mapper, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    /** Serialises a value; null for null values. */
    public static String write(ObjectMapper mapper, Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialise a stored JSON value", ex);
        }
    }
}
