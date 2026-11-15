package com.recruitpro.interview.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ICS content of interview invitations: structure, UTC times, METHOD, escaping and 75-octet line folding. */
class IcsCalendarTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 12, 1, 9, 30);

    private static IcsCalendar.Event event(IcsCalendar.Method method, int sequence, String summary, String location) {
        return new IcsCalendar.Event(method, "interview-42@recruitpro.local", sequence, START, 45, summary,
                "Line one\nLine two", location, new IcsCalendar.Person("RecruitPro", "no-reply@recruitpro.local"),
                List.of(new IcsCalendar.Person("Casey Candidate", "casey@example.test"),
                        new IcsCalendar.Person("Ivan \"The\" Petrov", "ivan@acme.test")),
                Instant.parse("2026-11-16T08:00:00Z"));
    }

    @Test
    void requestHasTheEventFieldsInUtcWithCrlfLineEnds() {
        String ics = IcsCalendar.build(event(IcsCalendar.Method.REQUEST, 0, "Interview: Backend Engineer", "Room 3B"));
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"));
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"));
        assertFalse(ics.replace("\r\n", "").contains("\n"), "every line ends with CRLF");
        assertTrue(ics.contains("\r\nMETHOD:REQUEST\r\n"));
        assertTrue(ics.contains("\r\nUID:interview-42@recruitpro.local\r\n"));
        assertTrue(ics.contains("\r\nSEQUENCE:0\r\n"));
        assertTrue(ics.contains("\r\nDTSTAMP:20261116T080000Z\r\n"));
        assertTrue(ics.contains("\r\nDTSTART:20261201T093000Z\r\n"));
        assertTrue(ics.contains("\r\nDTEND:20261201T101500Z\r\n"));
        assertTrue(ics.contains("\r\nSTATUS:CONFIRMED\r\n"));
        assertTrue(ics.contains("\r\nORGANIZER;CN=\"RecruitPro\":mailto:no-reply@recruitpro.local\r\n"));
        String unfolded = ics.replace("\r\n ", "");
        assertTrue(unfolded.contains("ATTENDEE;CN=\"Casey Candidate\";CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;"
                + "PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:casey@example.test"));
        assertTrue(unfolded.contains("ATTENDEE;CN=\"Ivan The Petrov\";"), "double quotes are removed from parameters");
        assertTrue(unfolded.contains("DESCRIPTION:Line one\\nLine two"));
        assertTrue(unfolded.contains("LOCATION:Room 3B"));
    }

    @Test
    void cancelUsesMethodCancelStatusCancelledAndTheGivenSequence() {
        String ics = IcsCalendar.build(event(IcsCalendar.Method.CANCEL, 2, "Interview", null));
        assertTrue(ics.contains("\r\nMETHOD:CANCEL\r\n"));
        assertTrue(ics.contains("\r\nSTATUS:CANCELLED\r\n"));
        assertTrue(ics.contains("\r\nSEQUENCE:2\r\n"));
        assertFalse(ics.contains("RSVP=TRUE"));
        assertFalse(ics.contains("LOCATION:"), "no location line without a location");
        assertEquals("text/calendar; charset=UTF-8; method=CANCEL", IcsCalendar.contentType(IcsCalendar.Method.CANCEL));
    }

    @Test
    void textValuesAreEscaped() {
        assertEquals("a\\,b\\;c\\\\d\\ne\\nf", IcsCalendar.escapeText("a,b;c\\d\r\ne\nf"));
        String ics = IcsCalendar.build(event(IcsCalendar.Method.REQUEST, 1, "Java, Spring; SQL", "Office\\Room"));
        String unfolded = ics.replace("\r\n ", "");
        assertTrue(unfolded.contains("SUMMARY:Java\\, Spring\\; SQL\r\n"));
        assertTrue(unfolded.contains("LOCATION:Office\\\\Room\r\n"));
    }

    @Test
    void longLinesAreFoldedAt75OctetsWithoutSplittingCharacters() {
        String summary = "Interview: " + "Ünïcödé engineer ".repeat(12);
        String ics = IcsCalendar.build(event(IcsCalendar.Method.REQUEST, 0, summary, "x"));
        for (String physical : ics.split("\r\n")) {
            assertTrue(physical.getBytes(StandardCharsets.UTF_8).length <= 75, "line too long: " + physical);
        }
        assertTrue(ics.contains("\r\n "), "a continuation line starts with one space");
        String unfolded = ics.replace("\r\n ", "");
        assertTrue(unfolded.contains("SUMMARY:" + summary + "\r\n"), "unfolding restores the value exactly");
    }

    @Test
    void foldingKeepsShortLinesUntouched() {
        assertEquals("SEQUENCE:3", IcsCalendar.fold("SEQUENCE:3"));
        String exactly75 = "X".repeat(75);
        assertEquals(exactly75, IcsCalendar.fold(exactly75));
        assertEquals("X".repeat(75) + "\r\n X", IcsCalendar.fold("X".repeat(76)));
    }

    @Test
    void organizerIsParsedFromTheMailFromSetting() {
        IcsCalendar.Person person = InterviewCalendarService.parseAddress("RecruitPro <no-reply@acme.test>");
        assertEquals("RecruitPro", person.name());
        assertEquals("no-reply@acme.test", person.email());
        assertEquals("plain@acme.test", InterviewCalendarService.parseAddress("plain@acme.test").email());
    }
}
