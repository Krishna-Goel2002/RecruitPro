package com.recruitpro.fairness.entity;

import java.util.List;
import java.util.Map;

/**
 * Allowed survey values (plan 5.5), in reporting order. {@link #PREFER_NOT} is a valid answer but is excluded
 * from fairness reports, like non-respondents.
 */
public final class EqualOpportunityValues {

    public static final String PREFER_NOT = "PREFER_NOT";

    public static final String GENDER = "gender";
    public static final String AGE_BAND = "ageBand";
    public static final String DISABILITY = "disability";
    public static final String SOCIAL_CATEGORY = "socialCategory";

    public static final List<String> GENDERS = List.of("WOMAN", "MAN", "NON_BINARY", "SELF_DESCRIBE", PREFER_NOT);
    public static final List<String> AGE_BANDS = List.of("UNDER_25", "25_34", "35_44", "45_54", "55_PLUS", PREFER_NOT);
    public static final List<String> DISABILITIES = List.of("YES", "NO", PREFER_NOT);
    public static final List<String> SOCIAL_CATEGORIES = List.of("GENERAL", "OBC", "SC", "ST", "EWS", "OTHER", PREFER_NOT);

    /** Dimension name (as reported) to its allowed values, in report order. */
    public static final Map<String, List<String>> DIMENSIONS = Map.of(
            GENDER, GENDERS, AGE_BAND, AGE_BANDS, DISABILITY, DISABILITIES, SOCIAL_CATEGORY, SOCIAL_CATEGORIES);

    /** Report order of the dimensions. */
    public static final List<String> DIMENSION_ORDER = List.of(GENDER, AGE_BAND, DISABILITY, SOCIAL_CATEGORY);

    private EqualOpportunityValues() {
    }

    /** The answer of one dimension, or null. */
    public static String valueOf(EeoResponse response, String dimension) {
        if (response == null) {
            return null;
        }
        return switch (dimension) {
            case GENDER -> response.getGender();
            case AGE_BAND -> response.getAgeBand();
            case DISABILITY -> response.getDisability();
            case SOCIAL_CATEGORY -> response.getSocialCategory();
            default -> null;
        };
    }
}
