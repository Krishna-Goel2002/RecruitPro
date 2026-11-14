package com.recruitpro.auth.web;

import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.user.entity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalTest {

    @Test
    void parsesTheHeaderCaseInsensitivelyAndTreatsAbsenceAsNoPortal() {
        assertEquals(Portal.CANDIDATE, Portal.parse("candidate"));
        assertEquals(Portal.STAFF, Portal.parse(" Staff "));
        assertNull(Portal.parse(null));
        assertNull(Portal.parse("  "));
        assertThrows(BadRequestException.class, () -> Portal.parse("admin"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        assertNull(Portal.from(request));
        request.addHeader(Portal.HEADER, "staff");
        assertEquals(Portal.STAFF, Portal.from(request));
    }

    @Test
    void candidatePortalAllowsOnlyCandidatesAndStaffPortalEveryOtherRole() {
        assertTrue(Portal.CANDIDATE.allows(Role.CANDIDATE));
        for (Role role : new Role[] {Role.ADMIN, Role.HR_MANAGER, Role.RECRUITER, Role.INTERVIEWER}) {
            assertFalse(Portal.CANDIDATE.allows(role), role.name());
            assertTrue(Portal.STAFF.allows(role), role.name());
        }
        assertFalse(Portal.STAFF.allows(Role.CANDIDATE));
        assertFalse(Portal.STAFF.allows(null));
    }

    @Test
    void cookieNamesAndTheRoleGate() {
        assertEquals("rp_refresh_candidate", Portal.cookieNameFor(Portal.CANDIDATE));
        assertEquals("rp_refresh_staff", Portal.cookieNameFor(Portal.STAFF));
        assertEquals("rp_refresh", Portal.cookieNameFor(null));

        assertDoesNotThrow(() -> Portal.requireAllowed(null, Role.CANDIDATE));
        assertDoesNotThrow(() -> Portal.requireAllowed(Portal.STAFF, Role.RECRUITER));
        ForbiddenException refused = assertThrows(ForbiddenException.class, () -> Portal.requireAllowed(Portal.STAFF, Role.CANDIDATE));
        assertTrue(refused.getMessage().contains("candidate portal"));
        assertThrows(ForbiddenException.class, () -> Portal.requireAllowed(Portal.CANDIDATE, Role.ADMIN));
    }
}
