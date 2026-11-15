package com.recruitpro.auth.mfa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.AuthTestSupport;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff TOTP MFA (plan v2, Section 6.1): enrolment and confirmation, the two-step sign-in with a single-use mfaToken,
 * replay guard, single-use recovery codes, lockout on wrong codes, the organisation requirement with first enrolment
 * through the mfaToken, disabling, and candidates being unaffected.
 */
@SpringBootTest(properties = "app.encryption.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MfaIntegrationTest {

    static final String PASSWORD = "SecurePassword123!";
    static final String STAFF = "hr@mfa.test";
    static final String ADMIN = "admin@mfa.test";
    static final String CANDIDATE = "cand@mfa.test";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestDataFactory fixtures;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AuditEventRepository auditEvents;

    Organization organization;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        organization = fixtures.organization("MFA Org");
        String hash = passwordEncoder.encode(PASSWORD);
        fixtures.staff(organization, STAFF, Role.HR_MANAGER, hash, true);
        fixtures.staff(organization, ADMIN, Role.ADMIN, hash, true);
        users.save(User.builder().email(CANDIDATE).passwordHash(hash).firstName("Cand").lastName("Idate")
                .role(Role.CANDIDATE).enabled(true).build());
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    // ---- tests ----

    @Test
    void withoutMfaStaffAndCandidatesSignInDirectly() throws Exception {
        login(STAFF, "staff").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.mfaRequired").doesNotExist());
        login(CANDIDATE, "candidate").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
        mvc.perform(get("/api/v1/me/mfa").with(user(CANDIDATE).roles("CANDIDATE"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/mfa/enroll").with(user(CANDIDATE).roles("CANDIDATE"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/mfa").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled", is(false)))
                .andExpect(jsonPath("$.data.available", is(true)))
                .andExpect(jsonPath("$.data.requiredByOrganization", is(false)));
    }

    @Test
    void enrolConfirmAndSignInWithTheSecondStep() throws Exception {
        JsonNode enrol = data(mvc.perform(post("/api/v1/me/mfa/enroll").with(staff())).andExpect(status().isOk()).andReturn());
        String secret = enrol.path("secret").asText();
        assertEquals(32, secret.length());
        assertTrue(enrol.path("otpauthUri").asText().startsWith("otpauth://totp/RecruitPro:hr%40mfa.test?secret=" + secret));
        assertTrue(enrol.path("otpauthUri").asText().contains("&issuer=RecruitPro&algorithm=SHA1&digits=6&period=30"));

        long step = currentStep();
        mvc.perform(confirm(wrongCode(secret, step), null).with(staff())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/mfa").with(staff())).andExpect(jsonPath("$.data.pendingEnrollment", is(true)));
        JsonNode confirmed = data(mvc.perform(confirm(code(secret, step), null).with(staff()))
                .andExpect(status().isOk()).andReturn());
        assertEquals(10, confirmed.path("recoveryCodes").size());
        assertTrue(confirmed.path("recoveryCodes").get(0).asText().matches("[a-z2-9]{5}-[a-z2-9]{5}"));
        assertTrue(confirmed.path("session").isMissingNode(), "an authenticated confirmation creates no new session");
        mvc.perform(get("/api/v1/me/mfa").with(staff()))
                .andExpect(jsonPath("$.data.enabled", is(true)))
                .andExpect(jsonPath("$.data.recoveryCodesRemaining", is(10)));
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.MFA_ENABLED).size());

        MvcResult challenge = login(STAFF, "staff").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mfaRequired", is(true)))
                .andExpect(jsonPath("$.data.mfaEnrollmentRequired", is(false)))
                .andExpect(jsonPath("$.data.expiresInSeconds", is(300)))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andReturn();
        assertNull(AuthTestSupport.refreshCookie(challenge, "rp_refresh_staff"), "no session before the second step");
        String mfaToken = data(challenge).path("mfaToken").asText();

        // The code used for the confirmation cannot be used again (replay guard).
        verify(mfaToken, code(secret, step), null).andExpect(status().isUnauthorized());
        MvcResult signedIn = verify(mfaToken, code(secret, step + 1), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.email", is(STAFF)))
                .andReturn();
        assertNotNull(AuthTestSupport.refreshCookie(signedIn, "rp_refresh_staff"));
        assertEquals(0, account(STAFF).getFailedLoginCount(), "a successful second step resets the failure counter");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + AuthTestSupport.accessToken(mapper, signedIn)))
                .andExpect(status().isOk());

        // The mfaToken is single use.
        verify(mfaToken, code(secret, step + 1), null).andExpect(status().isUnauthorized());
        verify("not-a-token", code(secret, step + 1), null).andExpect(status().isUnauthorized());
        verify(mfaToken, null, null).andExpect(status().isBadRequest());
    }

    @Test
    void recoveryCodesWorkOnceEach() throws Exception {
        Enrolment enrolment = enable(STAFF);
        String first = enrolment.recoveryCodes().get(0);
        verify(challenge(), null, first.toUpperCase()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
        verify(challenge(), null, first).andExpect(status().isUnauthorized());
        verify(challenge(), null, enrolment.recoveryCodes().get(1).replace("-", "")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/mfa").with(staff())).andExpect(jsonPath("$.data.recoveryCodesRemaining", is(8)));
        assertEquals(2, auditEvents.findAllByActionOrderByIdAsc(AuditActions.MFA_RECOVERY_CODE_USED).size());

        JsonNode regenerated = data(mvc.perform(post("/api/v1/me/mfa/recovery-codes").with(staff()))
                .andExpect(status().isOk()).andReturn());
        assertEquals(10, regenerated.path("recoveryCodes").size());
        verify(challenge(), null, enrolment.recoveryCodes().get(2)).andExpect(status().isUnauthorized());
        verify(challenge(), null, regenerated.path("recoveryCodes").get(0).asText()).andExpect(status().isOk());
    }

    @Test
    void wrongCodesLockTheAccount() throws Exception {
        Enrolment enrolment = enable(STAFF);
        String mfaToken = challenge();
        long step = currentStep();
        for (int attempt = 0; attempt < 5; attempt++) {
            verify(mfaToken, wrongCode(enrolment.secret(), step), null).andExpect(status().isUnauthorized());
        }
        assertNotNull(account(STAFF).getLockedUntil(), "five wrong codes lock the account like five wrong passwords");
        assertEquals(5, auditEvents.findAllByActionOrderByIdAsc(AuditActions.MFA_VERIFICATION_FAILED).size());
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.LOGIN_LOCKED).size());
        verify(mfaToken, code(enrolment.secret(), step + 1), null).andExpect(status().isUnauthorized());
        login(STAFF, "staff").andExpect(status().isUnauthorized());
    }

    @Test
    void organisationRequirementForcesEnrolmentAtSignIn() throws Exception {
        mvc.perform(patch("/api/v1/admin/organization").with(user(ADMIN).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requireStaffMfa\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requireStaffMfa", is(true)));
        login(CANDIDATE, "candidate").andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        JsonNode challenge = data(login(STAFF, "staff").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mfaRequired", is(true)))
                .andExpect(jsonPath("$.data.mfaEnrollmentRequired", is(true)))
                .andReturn());
        String mfaToken = challenge.path("mfaToken").asText();
        // An enrolment token is not a login token.
        verify(mfaToken, "123456", null).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/mfa/enroll")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/mfa/enroll").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mfaToken\":\"forged\"}")).andExpect(status().isUnauthorized());
        String secret = data(mvc.perform(post("/api/v1/me/mfa/enroll").contentType(MediaType.APPLICATION_JSON)
                        .content(json("mfaToken", mfaToken)))
                .andExpect(status().isOk()).andReturn()).path("secret").asText();
        long step = currentStep();
        MvcResult confirmed = mvc.perform(confirm(code(secret, step), mfaToken).header("X-RecruitPro-Portal", "staff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recoveryCodes.length()", is(10)))
                .andExpect(jsonPath("$.data.session.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.session.user.email", is(STAFF)))
                .andReturn();
        assertNotNull(AuthTestSupport.refreshCookie(confirmed, "rp_refresh_staff"));
        mvc.perform(confirm(code(secret, step + 1), mfaToken)).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/me/mfa/disable").with(staff()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\",\"code\":\"" + code(secret, step + 1) + "\"}"))
                .andExpect(status().isBadRequest());
        login(STAFF, "staff").andExpect(jsonPath("$.data.mfaRequired", is(true)))
                .andExpect(jsonPath("$.data.mfaEnrollmentRequired", is(false)));
    }

    @Test
    void disablingNeedsThePasswordAndACode() throws Exception {
        Enrolment enrolment = enable(STAFF);
        disable("WrongPassword123!", enrolment.recoveryCodes().get(0)).andExpect(status().isBadRequest());
        disable(PASSWORD, wrongCode(enrolment.secret(), currentStep())).andExpect(status().isBadRequest());
        disable(PASSWORD, enrolment.recoveryCodes().get(0)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/mfa").with(staff())).andExpect(jsonPath("$.data.enabled", is(false)));
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.MFA_DISABLED).size());
        login(STAFF, "staff").andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    void organisationSettingsValidateSsoDomains() throws Exception {
        mvc.perform(patch("/api/v1/admin/organization").with(user(ADMIN).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ssoAllowedDomains\":[\"Acme.test\",\"@corp.example\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ssoAllowedDomains[0]", is("acme.test")))
                .andExpect(jsonPath("$.data.ssoAllowedDomains[1]", is("corp.example")));
        mvc.perform(patch("/api/v1/admin/organization").with(user(ADMIN).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ssoAllowedDomains\":[\"*.acme.test\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/admin/organization").with(user(ADMIN).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ssoAllowedDomains\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ssoAllowedDomains.length()", is(0)));
        mvc.perform(get("/api/v1/admin/organization").with(user(ADMIN).roles("ADMIN")))
                .andExpect(jsonPath("$.data.requireStaffMfa", is(false)))
                .andExpect(jsonPath("$.data.otpauthUri").doesNotExist())
                .andExpect(jsonPath("$.data.name", startsWith("MFA Org")));
    }

    // ---- helpers ----

    record Enrolment(String secret, List<String> recoveryCodes) {
    }

    /** Enrols and confirms MFA for the user with a code two steps back (so later tests can use current codes). */
    private Enrolment enable(String email) throws Exception {
        String secret = data(mvc.perform(post("/api/v1/me/mfa/enroll").with(user(email).roles("HR_MANAGER")))
                .andExpect(status().isOk()).andReturn()).path("secret").asText();
        JsonNode confirmed = data(mvc.perform(confirm(code(secret, currentStep() - 1), null).with(user(email).roles("HR_MANAGER")))
                .andExpect(status().isOk()).andReturn());
        List<String> codes = new ArrayList<>();
        confirmed.path("recoveryCodes").forEach(node -> codes.add(node.asText()));
        return new Enrolment(secret, codes);
    }

    private String challenge() throws Exception {
        return data(login(STAFF, "staff").andExpect(jsonPath("$.data.mfaRequired", is(true))).andReturn())
                .path("mfaToken").asText();
    }

    private ResultActions login(String email, String portal) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").header("X-RecruitPro-Portal", portal)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"));
    }

    private ResultActions verify(String mfaToken, String code, String recoveryCode) throws Exception {
        StringBuilder body = new StringBuilder("{\"mfaToken\":\"").append(mfaToken).append('"');
        if (code != null) {
            body.append(",\"code\":\"").append(code).append('"');
        }
        if (recoveryCode != null) {
            body.append(",\"recoveryCode\":\"").append(recoveryCode).append('"');
        }
        body.append('}');
        return mvc.perform(post("/api/v1/auth/mfa/verify").header("X-RecruitPro-Portal", "staff")
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirm(String code, String mfaToken) {
        String body = mfaToken == null ? "{\"code\":\"" + code + "\"}"
                : "{\"code\":\"" + code + "\",\"mfaToken\":\"" + mfaToken + "\"}";
        return post("/api/v1/me/mfa/confirm").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions disable(String password, String code) throws Exception {
        return mvc.perform(post("/api/v1/me/mfa/disable").with(staff()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + password + "\",\"code\":\"" + code + "\"}"));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor staff() {
        return user(STAFF).roles("HR_MANAGER");
    }

    private User account(String email) {
        return users.findByEmail(email).orElseThrow();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return AuthTestSupport.data(mapper, result);
    }

    private static String json(String field, String value) {
        return "{\"" + field + "\":\"" + value + "\"}";
    }

    static long currentStep() {
        return Totp.step(System.currentTimeMillis() / 1000L, 30);
    }

    static String code(String base32Secret, long step) {
        return Totp.code(Base32.decode(base32Secret), step, 6);
    }

    /** A six-digit code that is valid for none of the steps around {@code step}. */
    static String wrongCode(String base32Secret, long step) {
        Set<String> valid = new HashSet<>();
        for (long candidate = step - 3; candidate <= step + 3; candidate++) {
            valid.add(code(base32Secret, candidate));
        }
        for (int i = 0; i < 1000; i++) {
            String guess = String.format("%06d", (i * 7919) % 1_000_000);
            if (!valid.contains(guess)) {
                return guess;
            }
        }
        throw new IllegalStateException("unreachable");
    }
}
