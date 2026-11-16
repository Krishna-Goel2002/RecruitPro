package com.recruitpro.assistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recruitpro.common.time.UtcTime;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON helpers of the assistant. Blocks and previews are {@link JsonNode} trees built by {@link #toNode}, which keeps
 * {@code null} values (the global NON_NULL inclusion would drop them from maps; the block contract has explicit nulls,
 * e.g. {@code revealedName: null}). Dates become ISO-8601 UTC strings.
 */
@Component
public class AssistantJson {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public AssistantJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** A JSON tree of maps, collections, strings, numbers, booleans, enums, dates and JsonNodes (nulls kept). */
    public JsonNode toNode(Object value) {
        if (value == null) {
            return NODES.nullNode();
        }
        if (value instanceof JsonNode node) {
            return node;
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode object = NODES.objectNode();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.set(String.valueOf(entry.getKey()), toNode(entry.getValue()));
            }
            return object;
        }
        if (value instanceof Collection<?> collection) {
            ArrayNode array = NODES.arrayNode();
            for (Object item : collection) {
                array.add(toNode(item));
            }
            return array;
        }
        if (value instanceof String text) {
            return NODES.textNode(text);
        }
        if (value instanceof Boolean flag) {
            return NODES.booleanNode(flag);
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return NODES.numberNode(((Number) value).longValue());
        }
        if (value instanceof Double || value instanceof Float) {
            return NODES.numberNode(((Number) value).doubleValue());
        }
        if (value instanceof BigDecimal decimal) {
            return NODES.numberNode(decimal);
        }
        if (value instanceof BigInteger integer) {
            return NODES.numberNode(integer);
        }
        if (value instanceof Enum<?> constant) {
            return NODES.textNode(constant.name());
        }
        if (value instanceof LocalDateTime time) {
            return NODES.textNode(UtcTime.format(time));
        }
        if (value instanceof LocalDate date) {
            return NODES.textNode(date.toString());
        }
        return objectMapper.valueToTree(value);
    }

    public ArrayNode array(List<?> items) {
        ArrayNode array = NODES.arrayNode();
        for (Object item : items) {
            array.add(toNode(item));
        }
        return array;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value instanceof JsonNode ? value : toNode(value));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Assistant data could not be serialised", ex);
        }
    }

    /** Parses stored JSON; a missing or unreadable value becomes {@code fallback}. */
    public JsonNode read(String json, JsonNode fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }

    public ArrayNode readArray(String json) {
        JsonNode node = read(json, null);
        return node instanceof ArrayNode array ? array : NODES.arrayNode();
    }

    /** Stored JSON object as a mutable map (empty when missing or not an object). */
    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, MAP);
            return map == null ? new LinkedHashMap<>() : map;
        } catch (JsonProcessingException ex) {
            return new LinkedHashMap<>();
        }
    }

    /** A JsonNode (or anything) as a plain map (empty when it is not an object). */
    public Map<String, Object> toMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> map = objectMapper.convertValue(value, MAP);
            return map == null ? new LinkedHashMap<>() : map;
        } catch (IllegalArgumentException ex) {
            return new LinkedHashMap<>();
        }
    }

    public static ObjectNode object() {
        return NODES.objectNode();
    }

    public static ArrayNode emptyArray() {
        return NODES.arrayNode();
    }
}
