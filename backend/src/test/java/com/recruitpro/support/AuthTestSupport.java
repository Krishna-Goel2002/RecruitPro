package com.recruitpro.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Collection;

/** Helpers for reading the refresh cookies (rp_refresh and the per-portal ones) and response envelopes in MockMvc tests. */
public final class AuthTestSupport {

    public static final String REFRESH_COOKIE = "rp_refresh";

    private AuthTestSupport() {
    }

    /**
     * Value of the rp_refresh Set-Cookie header: null when the response set no refresh cookie, "" when it
     * cleared the cookie.
     */
    public static String refreshCookie(MvcResult result) {
        return refreshCookie(result, REFRESH_COOKIE);
    }

    /**
     * Value of the named refresh Set-Cookie header (e.g. {@code rp_refresh_candidate}): null when the response set
     * no such cookie, "" when it cleared it.
     */
    public static String refreshCookie(MvcResult result, String cookieName) {
        Collection<String> headers = result.getResponse().getHeaders("Set-Cookie");
        String value = null;
        for (String header : headers) {
            if (header.startsWith(cookieName + "=")) {
                int end = header.indexOf(';');
                value = header.substring(cookieName.length() + 1, end < 0 ? header.length() : end);
            }
        }
        return value;
    }

    public static JsonNode data(ObjectMapper mapper, MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    public static String accessToken(ObjectMapper mapper, MvcResult result) throws Exception {
        return data(mapper, result).path("accessToken").asText();
    }

    public static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
