package com.recruitpro.interview.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Minimal RFC 5545 / RFC 5546 (iTIP) calendar object with one VEVENT, as attached to interview e-mails (WP-PLAT):
 * {@code METHOD:REQUEST} for a new or changed interview, {@code METHOD:CANCEL} for a cancellation. Times are UTC
 * ({@code ...Z}); text values are escaped (backslash, semicolon, comma, newline); parameter values are quoted when
 * needed and never contain double quotes; lines end with CRLF and are folded at 75 octets (UTF-8, never inside a
 * multi-byte character) with a single leading space on continuation lines.
 */
public final class IcsCalendar {

    public static final String PRODUCT_ID = "-//RecruitPro//Interviews//EN";
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final int MAX_OCTETS = 75;

    public enum Method { REQUEST, CANCEL }

    /** A participant: display name (may be null) and e-mail address. */
    public record Person(String name, String email) {
    }

    /** Everything one VEVENT needs; {@code start} is a UTC LocalDateTime like every timestamp in the backend. */
    public record Event(Method method, String uid, int sequence, LocalDateTime start, int durationMinutes, String summary,
                        String description, String location, Person organizer, List<Person> attendees, Instant stamp) {
    }

    private IcsCalendar() {
    }

    public static String build(Event event) {
        StringBuilder out = new StringBuilder(1024);
        line(out, "BEGIN:VCALENDAR");
        line(out, "PRODID:" + PRODUCT_ID);
        line(out, "VERSION:2.0");
        line(out, "CALSCALE:GREGORIAN");
        line(out, "METHOD:" + event.method().name());
        line(out, "BEGIN:VEVENT");
        line(out, "UID:" + escapeText(event.uid()));
        line(out, "SEQUENCE:" + Math.max(0, event.sequence()));
        line(out, "DTSTAMP:" + UTC.format((event.stamp() == null ? Instant.now() : event.stamp()).atOffset(ZoneOffset.UTC)));
        line(out, "DTSTART:" + UTC.format(event.start().atOffset(ZoneOffset.UTC)));
        line(out, "DTEND:" + UTC.format(event.start().plusMinutes(Math.max(1, event.durationMinutes())).atOffset(ZoneOffset.UTC)));
        line(out, "SUMMARY:" + escapeText(event.summary()));
        if (event.description() != null && !event.description().isBlank()) {
            line(out, "DESCRIPTION:" + escapeText(event.description()));
        }
        if (event.location() != null && !event.location().isBlank()) {
            line(out, "LOCATION:" + escapeText(event.location()));
        }
        if (event.organizer() != null && event.organizer().email() != null) {
            line(out, "ORGANIZER" + cn(event.organizer().name()) + ":mailto:" + event.organizer().email());
        }
        if (event.attendees() != null) {
            for (Person attendee : event.attendees()) {
                if (attendee == null || attendee.email() == null || attendee.email().isBlank()) {
                    continue;
                }
                line(out, "ATTENDEE" + cn(attendee.name()) + ";CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT"
                        + (event.method() == Method.REQUEST ? ";PARTSTAT=NEEDS-ACTION;RSVP=TRUE" : "")
                        + ":mailto:" + attendee.email().trim());
            }
        }
        line(out, "STATUS:" + (event.method() == Method.CANCEL ? "CANCELLED" : "CONFIRMED"));
        line(out, "TRANSP:OPAQUE");
        line(out, "END:VEVENT");
        line(out, "END:VCALENDAR");
        return out.toString();
    }

    /** {@code text/calendar; charset=UTF-8; method=REQUEST|CANCEL}. */
    public static String contentType(Method method) {
        return "text/calendar; charset=UTF-8; method=" + method.name();
    }

    /** RFC 5545 TEXT escaping: backslash, semicolon, comma and line breaks (as {@code \n}). */
    static String escapeText(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case ';' -> out.append("\\;");
                case ',' -> out.append("\\,");
                case '\r' -> {
                    if (i + 1 < value.length() && value.charAt(i + 1) == '\n') {
                        i++;
                    }
                    out.append("\\n");
                }
                case '\n' -> out.append("\\n");
                default -> {
                    if (c >= 0x20 || c == '\t') {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** {@code ;CN=...} parameter (quoted; double quotes and control characters removed), or empty. */
    static String cn(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        StringBuilder clean = new StringBuilder(name.length());
        for (char c : name.trim().toCharArray()) {
            if (c != '"' && c >= 0x20) {
                clean.append(c);
            }
        }
        return clean.length() == 0 ? "" : ";CN=\"" + clean + "\"";
    }

    /** Appends one content line, folded at 75 octets, ending with CRLF. */
    static void line(StringBuilder out, String content) {
        out.append(fold(content)).append("\r\n");
    }

    static String fold(String content) {
        StringBuilder result = new StringBuilder(content.length() + 16);
        int octets = 0;
        int limit = MAX_OCTETS;
        for (int i = 0; i < content.length(); ) {
            int codePoint = content.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (octets + size > limit) {
                result.append("\r\n ");
                octets = 1;
                limit = MAX_OCTETS;
            }
            result.appendCodePoint(codePoint);
            octets += size;
            i += charCount;
        }
        return result.toString();
    }
}
