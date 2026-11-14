package com.recruitpro.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.auth.repository.RefreshTokenRepository;
import com.recruitpro.auth.web.Portal;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.support.TestDatabase;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static com.recruitpro.support.AuthTestSupport.accessToken;
import static com.recruitpro.support.AuthTestSupport.bearer;
import static com.recruitpro.support.AuthTestSupport.data;
import static com.recruitpro.support.AuthTestSupport.refreshCookie;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-INT: separate sessions per portal. The {@code X-RecruitPro-Portal} header selects the refresh cookie
 * ({@code rp_refresh_candidate} / {@code rp_refresh_staff}; {@code rp_refresh} without the header), so signing in on
 * one portal no longer replaces the other portal's session, and an account of the other portal is refused with 403
 * before any session is created.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalSessionIntegrationTest {

    private static final String PASSWORD = "PortalPassword123";
    private static final String CANDIDATE_COOKIE = "rp_refresh_candidate";
    private static final String STAFF_COOKIE = "rp_refresh_staff";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TestDataFactory factory;
    @Autowired JdbcTemplate jdbcTemplate;

    private Organization organization;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
        organization = factory.organization("Portal Org");
        factory.staff(organization, "manager@portal.test", Role.HR_MANAGER, passwordEncoder.encode(PASSWORD), true);
        users.save(User.builder().email("candidate@portal.test").passwordHash(passwordEncoder.encode(PASSWORD))
                .firstName("Cand").lastName("Idate").role(Role.CANDIDATE).enabled(true).build());
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void eachPortalKeepsItsOwnRefreshCookieAndBothSessionsSurvive() throws Exception {
        MvcResult candidateLogin = login("candidate@portal.test", "candidate").andExpect(status().isOk()).andReturn();
        String candidateCookie = refreshCookie(candidateLogin, CANDIDATE_COOKIE);
        assertNotNull(candidateCookie);
        assertFalse(candidateCookie.isBlank());
        assertNull(refreshCookie(candidateLogin, STAFF_COOKIE), "the staff cookie is not touched");
        assertNull(refreshCookie(candidateLogin, "rp_refresh"), "the legacy cookie is not touched");
        String setCookie = candidateLogin.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict") && setCookie.contains("Path=/api/v1/auth"));

        MvcResult staffLogin = login("manager@portal.test", "staff").andExpect(status().isOk()).andReturn();
        String staffCookie = refreshCookie(staffLogin, STAFF_COOKIE);
        assertNotNull(staffCookie);
        assertNull(refreshCookie(staffLogin, CANDIDATE_COOKIE), "signing in on the HR portal leaves the candidate cookie alone");

        // The browser sends both cookies to /api/v1/auth; each portal reads only its own.
        MvcResult candidateRefresh = mvc.perform(post("/api/v1/auth/refresh").header(Portal.HEADER, "candidate")
                        .cookie(new Cookie(CANDIDATE_COOKIE, candidateCookie), new Cookie(STAFF_COOKIE, staffCookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("CANDIDATE"))
                .andReturn();
        assertNotNull(refreshCookie(candidateRefresh, CANDIDATE_COOKIE));
        assertNull(refreshCookie(candidateRefresh, STAFF_COOKIE));

        MvcResult staffRefresh = mvc.perform(post("/api/v1/auth/refresh").header(Portal.HEADER, "staff")
                        .cookie(new Cookie(CANDIDATE_COOKIE, refreshCookie(candidateRefresh, CANDIDATE_COOKIE)),
                                new Cookie(STAFF_COOKIE, staffCookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("HR_MANAGER"))
                .andReturn();
        assertNotNull(refreshCookie(staffRefresh, STAFF_COOKIE));
        assertNull(refreshCookie(staffRefresh, CANDIDATE_COOKIE));

        // Logging out of the HR portal clears only the staff cookie; the candidate session keeps working.
        MvcResult staffLogout = mvc.perform(post("/api/v1/auth/logout").header(Portal.HEADER, "staff")
                        .cookie(new Cookie(STAFF_COOKIE, refreshCookie(staffRefresh, STAFF_COOKIE))))
                .andExpect(status().isOk()).andReturn();
        assertEquals("", refreshCookie(staffLogout, STAFF_COOKIE));
        assertNull(refreshCookie(staffLogout, CANDIDATE_COOKIE));
        mvc.perform(post("/api/v1/auth/refresh").header(Portal.HEADER, "candidate")
                        .cookie(new Cookie(CANDIDATE_COOKIE, refreshCookie(candidateRefresh, CANDIDATE_COOKIE))))
                .andExpect(status().isOk());
    }

    @Test
    void aPortalNeverReadsTheOtherPortalsCookie() throws Exception {
        String staffCookie = refreshCookie(login("manager@portal.test", "staff").andExpect(status().isOk()).andReturn(), STAFF_COOKIE);
        // Only the staff cookie and no body token: the candidate portal finds no session of its own.
        mvc.perform(post("/api/v1/auth/refresh").header(Portal.HEADER, "candidate").cookie(new Cookie(STAFF_COOKIE, staffCookie)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").header(Portal.HEADER, "staff").cookie(new Cookie(STAFF_COOKIE, staffCookie)))
                .andExpect(status().isOk());
    }

    @Test
    void signInFromTheWrongPortalIsRefusedWith403AndCreatesNoSession() throws Exception {
        long tokensBefore = refreshTokens.count();
        MvcResult staffOnCandidatePortal = login("manager@portal.test", "candidate")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("candidate portal")))
                .andReturn();
        assertNull(staffOnCandidatePortal.getResponse().getHeader(HttpHeaders.SET_COOKIE), "no cookie is set");

        MvcResult candidateOnStaffPortal = login("candidate@portal.test", "staff")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("HR portal")))
                .andReturn();
        assertNull(candidateOnStaffPortal.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertEquals(tokensBefore, refreshTokens.count(), "no refresh token was issued");

        // A wrong password from the wrong portal is still a plain 401 (no hint that the account exists).
        mvc.perform(post("/api/v1/auth/login").header(Portal.HEADER, "staff").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("candidate@portal.test", "WrongPassword123")))
                .andExpect(status().isUnauthorized());
        User manager = users.findByEmail("manager@portal.test").orElseThrow();
        assertEquals(0, manager.getFailedLoginCount(), "a refused portal does not count as a failed login");
    }

    @Test
    void aTokenPresentedToTheWrongPortalIsRefusedUnrotatedAndItsCookieCleared() throws Exception {
        String staffCookie = refreshCookie(login("manager@portal.test", "staff").andExpect(status().isOk()).andReturn(), STAFF_COOKIE);
        // A staff token smuggled into the candidate cookie (e.g. copied by hand) is refused before rotation.
        MvcResult refused = mvc.perform(post("/api/v1/auth/refresh").header(Portal.HEADER, "candidate")
                        .cookie(new Cookie(CANDIDATE_COOKIE, staffCookie)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertEquals("", refreshCookie(refused, CANDIDATE_COOKIE), "the candidate cookie is cleared");
        // Not rotated or revoked: the staff portal can still use it.
        mvc.perform(post("/api/v1/auth/refresh").header(Portal.HEADER, "staff").cookie(new Cookie(STAFF_COOKIE, staffCookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("HR_MANAGER"));
    }

    @Test
    void registrationSignupAndInvitationsAreRefusedOnTheOtherPortal() throws Exception {
        long usersBefore = users.count();
        mvc.perform(post("/api/v1/auth/register").header(Portal.HEADER, "staff").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"New\",\"lastName\":\"Person\",\"email\":\"new@portal.test\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/organizations/signup").header(Portal.HEADER, "candidate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationName\":\"Wrong Portal Ltd\",\"firstName\":\"Org\",\"lastName\":\"Owner\","
                                + "\"email\":\"owner@wrong.test\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden());
        assertEquals(usersBefore, users.count(), "nothing was created");

        // Organisation sign-up on the staff portal sets the staff cookie.
        MvcResult signup = mvc.perform(post("/api/v1/organizations/signup").header(Portal.HEADER, "staff").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationName\":\"Right Portal Ltd\",\"firstName\":\"Org\",\"lastName\":\"Owner\","
                                + "\"email\":\"owner@right.test\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        assertNotNull(refreshCookie(signup, STAFF_COOKIE));
        String adminToken = accessToken(mapper, signup);

        MvcResult invited = mvc.perform(post("/api/v1/admin/invitations").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"recruiter@right.test\",\"role\":\"RECRUITER\"}"))
                .andExpect(status().isCreated()).andReturn();
        String inviteUrl = data(mapper, invited).path("inviteUrl").asText();
        String inviteToken = inviteUrl.substring(inviteUrl.indexOf("token=") + "token=".length());
        String acceptJson = "{\"token\":\"" + inviteToken + "\",\"firstName\":\"Rec\",\"lastName\":\"Ruiter\",\"password\":\"" + PASSWORD + "\"}";

        mvc.perform(post("/api/v1/auth/accept-invite").header(Portal.HEADER, "candidate").contentType(MediaType.APPLICATION_JSON).content(acceptJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("HR portal")));
        assertTrue(users.findByEmail("recruiter@right.test").isEmpty(), "the invitation is still unused");
        mvc.perform(get("/api/v1/auth/invitations/{token}", inviteToken)).andExpect(status().isOk());

        MvcResult accepted = mvc.perform(post("/api/v1/auth/accept-invite").header(Portal.HEADER, "staff")
                        .contentType(MediaType.APPLICATION_JSON).content(acceptJson))
                .andExpect(status().isOk()).andReturn();
        assertNotNull(refreshCookie(accepted, STAFF_COOKIE));
        assertNull(refreshCookie(accepted, CANDIDATE_COOKIE));
    }

    @Test
    void withoutTheHeaderTheLegacyCookieIsUsedAndAnyRoleMaySignIn() throws Exception {
        MvcResult staff = login("manager@portal.test", null).andExpect(status().isOk()).andReturn();
        String legacy = refreshCookie(staff, "rp_refresh");
        assertNotNull(legacy);
        assertNull(refreshCookie(staff, STAFF_COOKIE));
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("rp_refresh", legacy))).andExpect(status().isOk());
        login("candidate@portal.test", null).andExpect(status().isOk());
    }

    @Test
    void anUnknownPortalValueIsABadRequest() throws Exception {
        login("candidate@portal.test", "admin-console").andExpect(status().isBadRequest());
        // The header value is case-insensitive.
        login("candidate@portal.test", "Candidate").andExpect(status().isOk());
    }

    @Test
    void changingThePasswordWritesTheNewSessionIntoThePortalsCookie() throws Exception {
        MvcResult staffLogin = login("manager@portal.test", "staff").andExpect(status().isOk()).andReturn();
        MvcResult changed = mvc.perform(post("/api/v1/me/password").header(Portal.HEADER, "staff")
                        .header("Authorization", bearer(accessToken(mapper, staffLogin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"BrandNewPassword456\"}"))
                .andExpect(status().isOk()).andReturn();
        assertNotNull(refreshCookie(changed, STAFF_COOKIE));
        assertNull(refreshCookie(changed, "rp_refresh"));
    }

    @Test
    void corsPreflightAllowsThePortalHeader() throws Exception {
        mvc.perform(options("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5174")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type," + Portal.HEADER.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5174"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString(Portal.HEADER.toLowerCase())));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String portal) throws Exception {
        var request = post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginJson(email, PASSWORD));
        if (portal != null) {
            request.header(Portal.HEADER, portal);
        }
        return mvc.perform(request);
    }

    private static String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
