package com.recruitpro.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** X-Request-Id: safe incoming ids are reused, others replaced by a UUID; MDC set during the request; propagated. */
class RequestIdFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static String[] run(String incoming) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        if (incoming != null) {
            request.addHeader(RequestIds.HEADER, incoming);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(MDC.get(RequestIds.MDC_KEY));
        new RequestIdFilter().doFilter(request, response, chain);
        return new String[] {seen.get(), response.getHeader(RequestIds.HEADER)};
    }

    @Test
    void reusesASafeIncomingId() throws Exception {
        String[] result = run("abc-123_req.42");
        assertEquals("abc-123_req.42", result[0], "MDC requestId during the request");
        assertEquals("abc-123_req.42", result[1], "echoed in the response");
        assertNull(MDC.get(RequestIds.MDC_KEY), "MDC cleared afterwards");
    }

    @Test
    void generatesAUuidWhenMissingOrUnsafe() throws Exception {
        for (String incoming : new String[] {null, "", "short", "has space in it", "x".repeat(65), "evil\r\nheader",
                "<script>alert(1)</script>"}) {
            String[] result = run(incoming);
            assertNotEquals(incoming, result[1]);
            assertEquals(result[0], result[1]);
            UUID.fromString(result[1]);
        }
        assertTrue(RequestIds.isSafe("12345678"));
        assertTrue(RequestIds.isSafe("x".repeat(64)));
    }

    @Test
    void clientInterceptorSendsTheCurrentIdToDownstreamServices() {
        RestClient.Builder builder = RestClient.builder().requestInterceptor(new RequestIdClientInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://ai.test/api/v1/match"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(RequestIds.HEADER, "req-12345678"))
                .andRespond(withSuccess());
        MDC.put(RequestIds.MDC_KEY, "req-12345678");
        client.get().uri("http://ai.test/api/v1/match").retrieve().toBodilessEntity();
        server.verify();
    }
}
