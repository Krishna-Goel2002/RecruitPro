package com.recruitpro.common.time;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;

/**
 * D15: every timestamp in RecruitPro is UTC. Entities keep {@link LocalDateTime} values that are UTC by
 * convention; this helper produces, formats and parses them consistently.
 */
public final class UtcTime {

    private UtcTime() {
    }

    /**
     * Current UTC wall-clock time, independent of the JVM default zone, truncated to microseconds.
     *
     * <p>PostgreSQL and H2 store TIMESTAMP with microsecond precision and <em>round</em> finer values. Without the
     * truncation a value written with 100 ns digits (Windows clocks) could be stored rounded up, so an immediate
     * {@code run_after <= now} comparison with an un-rounded parameter from the same clock tick would miss it (seen as
     * background jobs that were never claimed on Windows).</p>
     */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** ISO-8601 with a trailing {@code Z}, truncated to milliseconds, e.g. {@code 2026-11-08T10:15:30Z}. */
    public static String format(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.truncatedTo(ChronoUnit.MILLIS).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * Parses an ISO-8601 value with or without an offset. Values with an offset (or zone) are converted to
     * UTC; values without one are taken as UTC already. A plain date means the start of that UTC day.
     *
     * @throws DateTimeParseException when the text is not an ISO-8601 date or date-time
     */
    public static LocalDateTime parse(String text) {
        if (text == null) {
            throw new DateTimeParseException("Missing date-time value", "", 0);
        }
        String value = text.trim();
        try {
            TemporalAccessor parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(value, ZonedDateTime::from, LocalDateTime::from);
            if (parsed instanceof ZonedDateTime zoned) {
                return zoned.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            }
            return (LocalDateTime) parsed;
        } catch (DateTimeParseException notADateTime) {
            return LocalDate.parse(value).atStartOfDay();
        }
    }
}
