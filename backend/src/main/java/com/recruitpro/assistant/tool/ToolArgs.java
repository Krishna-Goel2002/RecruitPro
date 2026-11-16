package com.recruitpro.assistant.tool;

import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.entity.Proficiency;
import com.recruitpro.hr.entity.RequirementType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tool arguments validated against the tool's {@link ArgSpec}s (ASSISTANT_PLAN 1 "every argument is validated on the
 * server"). Unknown keys are dropped; values are converted to their normalised JSON form ({@link #values()}), which is
 * also what a proposed action stores and re-validates on confirm.
 */
public final class ToolArgs {

    static final int DEFAULT_MAX_STRING = 2000;
    static final int DEFAULT_MAX_ITEMS = 20;
    static final int MAX_REQUIREMENTS = 100;
    static final int MAX_LIST_ITEM = 1000;
    private static final List<String> REQUIREMENT_TYPES = names(RequirementType.values());
    private static final List<String> PROFICIENCIES = names(Proficiency.values());

    private final Map<String, Object> values;

    private ToolArgs(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    public static ToolArgs empty() {
        return new ToolArgs(new LinkedHashMap<>());
    }

    /** Validates and normalises {@code raw}; throws {@link AssistantToolException} naming the first bad argument. */
    public static ToolArgs validate(List<ArgSpec> specs, Map<String, Object> raw) {
        Map<String, Object> input = raw == null ? Map.of() : raw;
        Map<String, Object> result = new LinkedHashMap<>();
        for (ArgSpec spec : specs) {
            Object value = input.get(spec.name());
            if (isMissing(value)) {
                if (spec.required()) {
                    throw new AssistantToolException("Missing argument '" + spec.name() + "' (" + spec.describe() + ")");
                }
                continue;
            }
            result.put(spec.name(), convert(spec, value));
        }
        return new ToolArgs(result);
    }

    /** Normalised values (JSON types only: strings, numbers, booleans, lists, maps). */
    public Map<String, Object> values() {
        return values;
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public Long getLong(String name) {
        Object value = values.get(name);
        return value instanceof Number number ? number.longValue() : null;
    }

    public Integer getInt(String name) {
        Long value = getLong(name);
        return value == null ? null : Math.toIntExact(value);
    }

    public String getString(String name) {
        Object value = values.get(name);
        return value == null ? null : value.toString();
    }

    public boolean getBoolean(String name, boolean fallback) {
        Object value = values.get(name);
        return value instanceof Boolean flag ? flag : fallback;
    }

    public BigDecimal getDecimal(String name) {
        Object value = values.get(name);
        return value instanceof BigDecimal decimal ? decimal : value instanceof Number number ? new BigDecimal(number.toString()) : null;
    }

    public LocalDateTime getDateTime(String name) {
        String value = getString(name);
        return value == null ? null : UtcTime.parse(value);
    }

    /** Application id of an APPLICATION_CODE argument. */
    public Long getApplicationId(String name) {
        return ApplicationCodes.parse(values.get(name));
    }

    /** Application ids of an APPLICATION_CODES argument. */
    public List<Long> getApplicationIds(String name) {
        List<Long> ids = new ArrayList<>();
        if (values.get(name) instanceof List<?> list) {
            for (Object item : list) {
                Long id = ApplicationCodes.parse(item);
                if (id != null) ids.add(id);
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    public List<String> getStrings(String name) {
        Object value = values.get(name);
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getObjects(String name) {
        Object value = values.get(name);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    // ---- conversion ----

    private static boolean isMissing(Object value) {
        return value == null || value instanceof String text && text.isBlank()
                || value instanceof Collection<?> collection && collection.isEmpty();
    }

    private static Object convert(ArgSpec spec, Object value) {
        String name = spec.name();
        return switch (spec.type()) {
            case INTEGER -> {
                long number = integer(name, value);
                if (number < spec.min() || number > spec.max()) {
                    throw new AssistantToolException("Argument '" + name + "' must be between " + spec.min() + " and " + spec.max());
                }
                yield number;
            }
            case NUMBER -> {
                BigDecimal number = decimal(name, value);
                if (spec.min() != Long.MIN_VALUE && number.compareTo(BigDecimal.valueOf(spec.min())) < 0
                        || spec.max() != Long.MAX_VALUE && number.compareTo(BigDecimal.valueOf(spec.max())) > 0) {
                    throw new AssistantToolException("Argument '" + name + "' is out of range");
                }
                yield number;
            }
            case STRING -> string(name, value, spec.max() == Long.MAX_VALUE ? DEFAULT_MAX_STRING : spec.max());
            case BOOLEAN -> bool(name, value);
            case ENUM -> enumValue(name, value, spec.values());
            case DATE_TIME -> {
                if (!(value instanceof String text)) throw invalid(name, "an ISO-8601 date-time");
                try {
                    yield UtcTime.format(UtcTime.parse(text));
                } catch (DateTimeParseException ex) {
                    throw invalid(name, "an ISO-8601 date-time such as 2026-12-01T10:00:00Z");
                }
            }
            case APPLICATION_CODE -> {
                Long id = ApplicationCodes.parse(value);
                if (id == null) throw invalid(name, "an application code like A-3014");
                yield ApplicationCodes.code(id);
            }
            case APPLICATION_CODES -> {
                List<?> items = value instanceof List<?> list ? list
                        : value instanceof String text ? List.of(text.split("[,;\\s]+")) : null;
                if (items == null) throw invalid(name, "a list of application codes");
                Set<String> codes = new LinkedHashSet<>();
                for (Object item : items) {
                    Long id = ApplicationCodes.parse(item);
                    if (id == null) throw invalid(name, "a list of application codes like [\"A-3001\", \"A-3014\"]");
                    codes.add(ApplicationCodes.code(id));
                }
                long max = spec.max() == Long.MAX_VALUE ? DEFAULT_MAX_ITEMS : spec.max();
                if (codes.size() > max) throw new AssistantToolException("Argument '" + name + "' takes at most " + max + " codes");
                yield new ArrayList<>(codes);
            }
            case STRING_LIST -> {
                List<?> items = value instanceof List<?> list ? list : List.of(value);
                List<String> strings = new ArrayList<>();
                for (Object item : items) {
                    if (item == null) continue;
                    if (item instanceof Map<?, ?> || item instanceof Collection<?>) throw invalid(name, "a string or a list of strings");
                    String text = item.toString().trim();
                    if (text.isEmpty()) continue;
                    strings.add(text.length() > MAX_LIST_ITEM ? text.substring(0, MAX_LIST_ITEM) : text);
                }
                long max = spec.max() == Long.MAX_VALUE ? DEFAULT_MAX_ITEMS : spec.max();
                if (strings.size() > max) throw new AssistantToolException("Argument '" + name + "' takes at most " + max + " items");
                yield strings;
            }
            case REQUIREMENTS -> requirements(name, value);
        };
    }

    private static List<Map<String, Object>> requirements(String name, Object value) {
        if (!(value instanceof List<?> list)) throw invalid(name, "a list of requirements");
        if (list.size() > MAX_REQUIREMENTS) {
            throw new AssistantToolException("A job can have at most " + MAX_REQUIREMENTS + " requirements");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw invalid(name, "a list of requirement objects");
            Map<String, Object> requirement = new LinkedHashMap<>();
            requirement.put("type", map.get("type") == null ? "SKILL"
                    : enumValue(name + ".type", map.get("type"), REQUIREMENT_TYPES));
            Object description = map.get("description");
            if (isMissing(description)) throw new AssistantToolException("Every requirement needs a description");
            requirement.put("description", string(name + ".description", description, 500));
            boolean mandatory = map.get("mandatory") == null || bool(name + ".mandatory", map.get("mandatory"));
            requirement.put("mandatory", mandatory);
            Boolean knockout = map.get("knockout") == null ? null : bool(name + ".knockout", map.get("knockout"));
            if (Boolean.TRUE.equals(knockout) && !mandatory) {
                throw new AssistantToolException("A knockout requirement must be mandatory");
            }
            requirement.put("knockout", knockout);
            Object years = map.get("minimumYears");
            if (!isMissing(years)) {
                long minimumYears = integer(name + ".minimumYears", years);
                if (minimumYears < 0 || minimumYears > 50) {
                    throw new AssistantToolException("Minimum years must be between 0 and 50");
                }
                requirement.put("minimumYears", minimumYears);
            } else {
                requirement.put("minimumYears", null);
            }
            List<String> alternatives = new ArrayList<>();
            if (map.get("alternatives") instanceof List<?> options) {
                for (Object option : options) {
                    if (option != null && !option.toString().isBlank() && alternatives.size() < 10) {
                        String text = option.toString().trim();
                        alternatives.add(text.length() > 200 ? text.substring(0, 200) : text);
                    }
                }
            }
            requirement.put("alternatives", alternatives);
            Object proficiency = map.get("proficiency");
            requirement.put("proficiency", isMissing(proficiency) ? null : enumValue(name + ".proficiency", proficiency, PROFICIENCIES));
            result.add(requirement);
        }
        return result;
    }

    private static long integer(String name, Object value) {
        if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            if (asDouble != Math.rint(asDouble)) throw invalid(name, "a whole number");
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ex) {
                throw invalid(name, "a whole number");
            }
        }
        throw invalid(name, "a whole number");
    }

    private static BigDecimal decimal(String name, Object value) {
        try {
            if (value instanceof Number || value instanceof String) {
                return new BigDecimal(value.toString().trim().replace(",", ""));
            }
        } catch (NumberFormatException ex) {
            // fall through
        }
        throw invalid(name, "a number");
    }

    private static String string(String name, Object value, long max) {
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) throw invalid(name, "text");
        String text = value.toString().trim();
        if (text.length() > max) {
            throw new AssistantToolException("Argument '" + name + "' must be at most " + max + " characters");
        }
        return text;
    }

    private static boolean bool(String name, Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof String text) {
            String normalised = text.trim().toLowerCase(Locale.ROOT);
            if (normalised.equals("true") || normalised.equals("yes")) return true;
            if (normalised.equals("false") || normalised.equals("no")) return false;
        }
        throw invalid(name, "true or false");
    }

    private static String enumValue(String name, Object value, List<String> allowed) {
        if (!(value instanceof String text)) throw invalid(name, "one of " + String.join("|", allowed));
        String normalised = text.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (!allowed.contains(normalised)) throw invalid(name, "one of " + String.join("|", allowed));
        return normalised;
    }

    private static AssistantToolException invalid(String name, String expected) {
        return new AssistantToolException("Argument '" + name + "' must be " + expected);
    }

    private static List<String> names(Enum<?>[] constants) {
        List<String> names = new ArrayList<>();
        for (Enum<?> constant : constants) names.add(constant.name());
        return List.copyOf(names);
    }
}
