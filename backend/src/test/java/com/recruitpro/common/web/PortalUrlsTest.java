package com.recruitpro.common.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Links into the portals keep a base path (single-origin gateway) and never get a double slash. */
class PortalUrlsTest {

    @Test
    void gatewayBasesKeepTheirPortalPath() {
        assertEquals("http://localhost:8088/candidate/reference/abcdefghijklmnopqrst", PortalUrls.join("http://localhost:8088/candidate", "/reference/abcdefghijklmnopqrst"));
        assertEquals("http://localhost:8088/candidate/verify-email?token=t1", PortalUrls.join("http://localhost:8088/candidate/", "/verify-email?token=t1"));
        assertEquals("http://localhost:8088/hr/accept-invite?token=t2", PortalUrls.join("http://localhost:8088/hr//", "accept-invite?token=t2"));
        assertEquals("http://localhost:8088/hr", PortalUrls.base(" http://localhost:8088/hr/ "));
    }

    @Test
    void perPortBasesStillWork() {
        assertEquals("http://localhost:5173/reset-password?token=t3", PortalUrls.join("http://localhost:5173", "/reset-password?token=t3"));
        assertEquals("http://localhost:8080/api/v1/auth/sso/google/callback", PortalUrls.join("http://localhost:8080/", "/api/v1/auth/sso/google/callback"));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertEquals("", PortalUrls.base(null));
        assertEquals("", PortalUrls.base("///"));
        assertEquals("http://localhost:8088/hr", PortalUrls.join("http://localhost:8088/hr", null));
        assertEquals("http://localhost:8088/hr", PortalUrls.join("http://localhost:8088/hr", ""));
    }
}
