package com.recruitpro.assistant.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes personal data from text that is sent to the planner (ASSISTANT_PLAN 1 "Blind-first"): e-mail addresses,
 * URLs, phone numbers and the names of candidates the backend knows about (the full name case-insensitively, single
 * name parts of three or more letters when capitalised). Candidates are referred to by application code only.
 * One instance per user message (it collects names while tools run). Not thread-safe.
 */
public final class Deidentifier {

    public static final String NAME = "[name]";
    public static final String EMAIL = "[email]";
    public static final String URL = "[link]";
    public static final String PHONE = "[phone]";
    /** Longest quoted candidate-supplied text in an observation. */
    public static final int QUOTE_LIMIT = 300;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\b(?:https?://|ftp://|www\\.)[^\\s<>\"']+");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<![\\w.])\\+?\\(?\\d[\\d ()./-]{6,}\\d(?![\\w.])");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}.*");

    /** Full names (lower case) and single name parts (as written), longest first when applied. */
    private final Set<String> fullNames = new LinkedHashSet<>();
    private final Set<String> nameParts = new LinkedHashSet<>();
    private List<Pattern> compiled;

    /** Registers a candidate whose name or e-mail must never reach the planner. */
    public void addPerson(String firstName, String lastName, String email) {
        String first = clean(firstName);
        String last = clean(lastName);
        String full = (first + " " + last).trim();
        if (full.length() >= 3) {
            fullNames.add(full.toLowerCase(Locale.ROOT));
        }
        for (String part : (first + " " + last).split("[\\s-]+")) {
            if (part.length() >= 3) {
                nameParts.add(part);
            }
        }
        // E-mail addresses (this one included) are removed by the general e-mail pattern in scrub().
        compiled = null;
    }

    /** Registers a full name shown to the user (e.g. a revealed name in an earlier block). */
    public void addName(String fullName) {
        String name = clean(fullName);
        if (name.isEmpty()) {
            return;
        }
        int space = name.lastIndexOf(' ');
        addPerson(space < 0 ? name : name.substring(0, space), space < 0 ? "" : name.substring(space + 1), null);
    }

    public boolean knows(String text) {
        return text != null && !scrub(text).equals(text);
    }

    /** Text with e-mails, URLs, phone numbers and known candidate names replaced by placeholders. */
    public String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // Every e-mail address (known candidates' included) matches the general pattern.
        String result = EMAIL_PATTERN.matcher(text).replaceAll(EMAIL);
        result = URL_PATTERN.matcher(result).replaceAll(URL);
        result = replacePhones(result);
        for (Pattern pattern : patterns()) {
            result = pattern.matcher(result).replaceAll(Matcher.quoteReplacement(NAME));
        }
        return result;
    }

    /** Candidate-supplied text for an observation: scrubbed and cut to {@link #QUOTE_LIMIT} characters. */
    public String quote(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String scrubbed = scrub(text.trim().replaceAll("\\s+", " "));
        return scrubbed.length() > QUOTE_LIMIT ? scrubbed.substring(0, QUOTE_LIMIT - 1) + "…" : scrubbed;
    }

    /** Recursively scrubs every string of a JSON-like structure (maps, lists, strings); other values are kept. */
    public Object scrubTree(Object value) {
        if (value instanceof String text) {
            return scrub(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), scrubTree(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(scrubTree(item));
            }
            return copy;
        }
        return value;
    }

    /**
     * Alternations of at most {@link #CHUNK} names each (longest first, so a full name wins over its parts): full names
     * case-insensitively, then single name parts when capitalised or upper case.
     */
    private List<Pattern> patterns() {
        if (compiled == null) {
            List<Pattern> result = new ArrayList<>();
            List<String> full = new ArrayList<>(fullNames);
            full.sort(Comparator.comparingInt(String::length).reversed());
            List<String> alternatives = new ArrayList<>();
            for (String name : full) {
                alternatives.add(Pattern.quote(name).replace(" ", "\\E\\s+\\Q"));
            }
            addChunks(result, alternatives, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            List<String> parts = new ArrayList<>(nameParts);
            parts.sort(Comparator.comparingInt(String::length).reversed());
            alternatives = new ArrayList<>();
            for (String part : parts) {
                String capitalised = part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1);
                alternatives.add(Pattern.quote(capitalised));
                alternatives.add(Pattern.quote(part.toUpperCase(Locale.ROOT)));
            }
            addChunks(result, alternatives, 0);
            compiled = result;
        }
        return compiled;
    }

    static final int CHUNK = 200;

    private static void addChunks(List<Pattern> result, List<String> alternatives, int flags) {
        for (int from = 0; from < alternatives.size(); from += CHUNK) {
            List<String> chunk = alternatives.subList(from, Math.min(alternatives.size(), from + CHUNK));
            result.add(Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + String.join("|", chunk) + ")(?![\\p{L}\\p{N}])", flags));
        }
    }

    private static String replacePhones(String text) {
        Matcher matcher = PHONE_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String match = matcher.group();
            long digits = match.chars().filter(Character::isDigit).count();
            boolean phone = digits >= 9 && !ISO_DATE.matcher(match.trim()).matches();
            matcher.appendReplacement(out, Matcher.quoteReplacement(phone ? PHONE : match));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
