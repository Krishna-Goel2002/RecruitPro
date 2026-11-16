package com.recruitpro.assistant.tool;

/** Argument types of the tool catalogue; {@link #label} is how the type is described to the planner. */
public enum ArgType {
    INTEGER("integer"),
    NUMBER("number"),
    STRING("string"),
    BOOLEAN("boolean"),
    /** One of {@link ArgSpec#values()} (case-insensitive; stored upper case). */
    ENUM("string"),
    /** ISO-8601 date-time (with offset or UTC) or a plain date (= 23:59:59 UTC that day); stored as UTC ISO-8601. */
    DATE_TIME("ISO-8601 date-time"),
    /** An application code such as "A-3014" (a bare number is accepted); stored as "A-3014". */
    APPLICATION_CODE("application code like A-3014"),
    /** A list of application codes. */
    APPLICATION_CODES("list of application codes"),
    /** A string or a list of strings; stored as a list. */
    STRING_LIST("string or list of strings"),
    /** Job requirements: list of {type, description, mandatory, knockout, minimumYears, alternatives, proficiency}. */
    REQUIREMENTS("list of {type, description, mandatory, knockout, minimumYears, alternatives, proficiency}");

    private final String label;

    ArgType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
