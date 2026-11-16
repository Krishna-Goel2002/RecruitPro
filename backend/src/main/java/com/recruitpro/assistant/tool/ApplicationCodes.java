package com.recruitpro.assistant.tool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Application codes ("A-3014" = application id 3014): how the assistant and the planner refer to candidates
 * (blind-first; the same code as the portal's "Candidate #A-3014").
 */
public final class ApplicationCodes {

    private static final Pattern CODE = Pattern.compile("^#?\\s*(?:candidate\\s*#?\\s*)?(?:a\\s*-?\\s*)?(\\d{1,18})$",
            Pattern.CASE_INSENSITIVE);

    private ApplicationCodes() {
    }

    public static String code(Long applicationId) {
        return applicationId == null ? null : "A-" + applicationId;
    }

    /** The application id of "A-3014", "a3014", "#A-3014", "Candidate #A-3014" or 3014; null when it is not a code. */
    public static Long parse(Object value) {
        if (value instanceof Number number) {
            long id = number.longValue();
            return id > 0 && number.doubleValue() == id ? id : null;
        }
        if (!(value instanceof String text)) {
            return null;
        }
        Matcher matcher = CODE.matcher(text.trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            long id = Long.parseLong(matcher.group(1));
            return id > 0 ? id : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
