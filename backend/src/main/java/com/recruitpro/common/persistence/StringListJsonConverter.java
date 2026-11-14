package com.recruitpro.common.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores a {@code List<String>} as a JSON array in a TEXT column (plan 3.1: no JSONB). An empty list is
 * stored as NULL and NULL (or unreadable JSON) reads back as an empty, mutable list; null elements are dropped.
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_OF_STRINGS = new TypeReference<>() { };

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to store a list value", ex);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> values = MAPPER.readValue(dbData, LIST_OF_STRINGS);
            List<String> result = new ArrayList<>();
            if (values != null) {
                for (String value : values) {
                    if (value != null) {
                        result.add(value);
                    }
                }
            }
            return result;
        } catch (JsonProcessingException ex) {
            return new ArrayList<>();
        }
    }
}
