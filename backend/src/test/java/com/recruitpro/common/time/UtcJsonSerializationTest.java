package com.recruitpro.common.time;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.common.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

/** D15: LocalDateTime values are UTC on the wire, written with a trailing Z and read with any offset. */
@SpringBootTest
@ActiveProfiles("test")
class UtcJsonSerializationTest {

    @Autowired ObjectMapper objectMapper;

    public record Holder(LocalDateTime at) {
    }

    @Test
    void writesLocalDateTimeAsUtcWithTrailingZ() throws Exception {
        assertEquals("{\"at\":\"2026-11-08T10:15:30Z\"}",
                objectMapper.writeValueAsString(new Holder(LocalDateTime.of(2026, 11, 8, 10, 15, 30))));
        assertEquals("{\"at\":\"2026-11-08T10:15:30.123Z\"}",
                objectMapper.writeValueAsString(new Holder(LocalDateTime.of(2026, 11, 8, 10, 15, 30, 123_456_789))));
    }

    @Test
    void readsValuesWithOrWithoutAnOffsetAndConvertsToUtc() throws Exception {
        LocalDateTime expected = LocalDateTime.of(2026, 11, 8, 10, 15, 30);
        assertEquals(expected, objectMapper.readValue("{\"at\":\"2026-11-08T12:15:30+02:00\"}", Holder.class).at());
        assertEquals(expected, objectMapper.readValue("{\"at\":\"2026-11-08T15:45:30+05:30\"}", Holder.class).at());
        assertEquals(expected, objectMapper.readValue("{\"at\":\"2026-11-08T10:15:30Z\"}", Holder.class).at());
        assertEquals(expected, objectMapper.readValue("{\"at\":\"2026-11-08T10:15:30.000Z\"}", Holder.class).at());
        assertEquals(expected, objectMapper.readValue("{\"at\":\"2026-11-08T10:15:30\"}", Holder.class).at());
        assertNull(objectMapper.readValue("{\"at\":null}", Holder.class).at());
    }

    @Test
    void rejectsValuesThatAreNotIsoDateTimes() {
        assertThrows(JsonMappingException.class,
                () -> objectMapper.readValue("{\"at\":\"next tuesday\"}", Holder.class));
        assertThrows(JsonMappingException.class,
                () -> objectMapper.readValue("{\"at\":[2026,11,8]}", Holder.class));
    }

    @Test
    void envelopeTimestampIsUtc() throws Exception {
        String json = objectMapper.writeValueAsString(ApiResponse.success("ok", "done"));
        String timestamp = objectMapper.readTree(json).path("timestamp").asText();
        assertTrue(timestamp.endsWith("Z"), timestamp);
    }

    @Test
    void utcTimeHelpers() {
        assertEquals("UTC", TimeZone.getDefault().getID(), "tests run with -Duser.timezone=UTC like the application");
        assertEquals(LocalDateTime.of(2026, 11, 8, 0, 0), UtcTime.parse("2026-11-08"));
        assertEquals(LocalDateTime.of(2026, 11, 7, 23, 0), UtcTime.parse("2026-11-08T00:00:00+01:00"));
        assertEquals("2026-11-08T00:00:00Z", UtcTime.format(LocalDate.of(2026, 11, 8).atStartOfDay()));
        assertNull(UtcTime.format(null));
    }
}
