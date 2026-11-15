package com.recruitpro.auth.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.support.AuthTestSupport;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff OIDC SSO against a local stub provider (JDK HttpServer serving JWKS and the token endpoint, RS256 key
 * generated per run): PKCE S256, state single use, id_token validation (signature, nonce, aud, iss, exp, email
 * verification), linking only to existing enabled staff whose organisation allows the e-mail domain.
 */
@SpringBootTest(properties = {
        "app.encryption.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.frontend.hr-url=http://hr.test",
        "app.sso.redirect-base-url=http://api.test",
        "app.sso.providers.test.name=Test IdP",
        "app.sso.providers.test.client-id=client-123",
        "app.sso.providers.test.client-secret=secret-xyz"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SsoIntegrationTest {

    static final String CLIENT_ID = "client-123";
    static final String KID = "test-key-1";
    static final HttpServer SERVER;
    static final KeyPair KEYS;
    static final KeyPair OTHER_KEYS;
    static final AtomicReference<String> NEXT_ID_TOKEN = new AtomicReference<>();
    static final AtomicReference<String> LAST_TOKEN_REQUEST = new AtomicReference<>();
    static final String ISSUER;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEYS = generator.generateKeyPair();
            OTHER_KEYS = generator.generateKeyPair();
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/jwks", exchange -> respond(exchange, jwks((RSAPublicKey) KEYS.getPublic())));
            SERVER.createContext("/token", exchange -> {
                LAST_TOKEN_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String idToken = NEXT_ID_TOKEN.get();
                respond(exchange, idToken == null ? "{\"error\":\"invalid_grant\"}"
                        : "{\"access_token\":\"at\",\"token_type\":\"Bearer\",\"id_token\":\"" + idToken + "\"}");
            });
            SERVER.start();
            ISSUER = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/issuer";
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void providerEndpoints(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("app.sso.providers.test.issuer", () -> ISSUER);
        registry.add("app.sso.providers.test.authorization-uri", () -> base + "/authorize");
        registry.add("app.sso.providers.test.token-uri", () -> base + "/token");
        registry.add("app.sso.providers.test.jwks-uri", () -> base + "/jwks");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestDataFactory fixtures;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired UserIdentityRepository identities;
    @Autowired AuditEventRepository auditEvents;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        NEXT_ID_TOKEN.set(null);
        Organization acme = fixtures.organization("Acme SSO");
        acme.setSsoAllowedDomains("acme.test");
        organizations.save(acme);
        Organization closed = fixtures.organization("No SSO Org");
        fixtures.staff(acme, "alice@acme.test", Role.HR_MANAGER);
        fixtures.staff(acme, "carol@corp.test", Role.RECRUITER);
        fixtures.staff(acme, "erin@acme.test", Role.RECRUITER, TestDataFactory.UNUSABLE_PASSWORD_HASH, false);
        fixtures.staff(closed, "frank@acme.test", Role.HR_MANAGER);
        fixtures.candidate("dave@acme.test");
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void providersListsOnlyConfiguredProviders() throws Exception {
        mvc.perform(get("/api/v1/auth/sso/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(1)))
                .andExpect(jsonPath("$.data[0].id", is("test")))
                .andExpect(jsonPath("$.data[0].name", is("Test IdP")));
        mvc.perform(get("/api/v1/auth/sso/google/start")).andExpect(status().isFound())
                .andExpect(result -> assertEquals("http://hr.test/login?ssoError=not_configured",
                        result.getResponse().getHeader("Location")));
    }

    @Test
    void successfulSignInWithPkceLinksTheIdentityAndSetsTheStaffCookie() throws Exception {
        Start start = start();
        assertEquals("S256", start.params().getFirst("code_challenge_method"));
        assertEquals(CLIENT_ID, start.params().getFirst("client_id"));
        assertEquals("http://api.test/api/v1/auth/sso/test/callback",
                URLDecoder.decode(start.params().getFirst("redirect_uri"), StandardCharsets.UTF_8));
        NEXT_ID_TOKEN.set(token(claims(start.nonce()), KEYS.getPrivate(), KID));
        MvcResult result = callback(start.state());
        assertEquals("http://hr.test/sso/complete", result.getResponse().getHeader("Location"));
        assertNotNull(AuthTestSupport.refreshCookie(result, "rp_refresh_staff"));

        Map<String, String> form = form(LAST_TOKEN_REQUEST.get());
        assertEquals("authorization_code", form.get("grant_type"));
        assertEquals("the-code", form.get("code"));
        assertEquals(start.params().getFirst("code_challenge"), s256(form.get("code_verifier")), "PKCE S256");
        User alice = users.findByEmail("alice@acme.test").orElseThrow();
        assertTrue(identities.findByProviderAndSubject("test", "sub-alice").isPresent());
        assertEquals(alice.getId(), identities.findByProviderAndSubject("test", "sub-alice").orElseThrow().getUserId());
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.SSO_IDENTITY_LINKED).size());
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.SSO_LOGIN).size());

        // The state is single use.
        assertEquals("http://hr.test/login?ssoError=invalid_state", callback(start.state()).getResponse().getHeader("Location"));
        assertEquals("http://hr.test/login?ssoError=invalid_state", callback("unknown-state").getResponse().getHeader("Location"));

        // Second sign-in goes through the linked identity.
        Start again = start();
        NEXT_ID_TOKEN.set(token(claims(again.nonce()), KEYS.getPrivate(), KID));
        assertEquals("http://hr.test/sso/complete", callback(again.state()).getResponse().getHeader("Location"));
        assertEquals(1, identities.findAll().size());
    }

    @Test
    void invalidTokensAreRefused() throws Exception {
        expectError(claimsWith("nonce", "another-nonce"), KEYS.getPrivate(), KID, "invalid_token");
        expectError(claimsWith("aud", "someone-else"), KEYS.getPrivate(), KID, "invalid_token");
        expectError(claimsWith("iss", "https://evil.example"), KEYS.getPrivate(), KID, "invalid_token");
        Map<String, Object> expired = claimsWith("exp", Instant.now().minusSeconds(600));
        expired.put("iat", Instant.now().minusSeconds(1200));
        expectError(expired, KEYS.getPrivate(), KID, "invalid_token");
        expectError(claimsWith("iat", Instant.now().plusSeconds(3600)), KEYS.getPrivate(), KID, "invalid_token");
        expectError(claimsWith("email_verified", false), KEYS.getPrivate(), KID, "email_not_verified");
        expectError(claimsWith("email", null), KEYS.getPrivate(), KID, "email_not_verified");
        expectError(claimsWith(null, null), OTHER_KEYS.getPrivate(), KID, "invalid_token");
        expectError(claimsWith(null, null), OTHER_KEYS.getPrivate(), "unknown-kid", "invalid_token");
        assertTrue(identities.findAll().isEmpty());
    }

    @Test
    void onlyExistingEnabledStaffOfAnAllowingOrganisationCanSignIn() throws Exception {
        expectError(withEmail("carol@corp.test", "sub-carol"), KEYS.getPrivate(), KID, "domain_not_allowed");
        expectError(withEmail("frank@acme.test", "sub-frank"), KEYS.getPrivate(), KID, "domain_not_allowed");
        expectError(withEmail("nobody@acme.test", "sub-nobody"), KEYS.getPrivate(), KID, "no_account");
        expectError(withEmail("dave@acme.test", "sub-dave"), KEYS.getPrivate(), KID, "no_account");
        expectError(withEmail("erin@acme.test", "sub-erin"), KEYS.getPrivate(), KID, "account_disabled");
        assertTrue(identities.findAll().isEmpty(), "no identity is linked and no account is created");
        assertEquals(0, users.findAll().stream().filter(user -> user.getEmail().equals("nobody@acme.test")).count());
        assertEquals(5, auditEvents.findAllByActionOrderByIdAsc(AuditActions.SSO_LOGIN_REFUSED).size());
    }

    @Test
    void providerErrorsAndMissingCodesRedirectWithACode() throws Exception {
        Start start = start();
        MvcResult cancelled = mvc.perform(get("/api/v1/auth/sso/test/callback")
                        .param("state", start.state()).param("error", "access_denied"))
                .andExpect(status().isFound()).andReturn();
        assertEquals("http://hr.test/login?ssoError=cancelled", cancelled.getResponse().getHeader("Location"));
        assertNull(AuthTestSupport.refreshCookie(cancelled, "rp_refresh_staff"));
        Start second = start();
        NEXT_ID_TOKEN.set(null);
        assertEquals("http://hr.test/login?ssoError=token_exchange_failed",
                callback(second.state()).getResponse().getHeader("Location"));
    }

    // ---- helpers ----

    record Start(String state, String nonce, MultiValueMap<String, String> params) {
    }

    private Start start() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/sso/test/start").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        String url = AuthTestSupport.data(mapper, result).path("authorizeUrl").asText();
        assertTrue(url.startsWith("http://127.0.0.1:" + SERVER.getAddress().getPort() + "/authorize?"));
        MultiValueMap<String, String> params = UriComponentsBuilder.fromUriString(url).build().getQueryParams();
        return new Start(params.getFirst("state"), params.getFirst("nonce"), params);
    }

    private MvcResult callback(String state) throws Exception {
        return mvc.perform(get("/api/v1/auth/sso/test/callback").param("code", "the-code").param("state", state))
                .andExpect(status().isFound()).andReturn();
    }

    private void expectError(Map<String, Object> claims, PrivateKey key, String kid, String code) throws Exception {
        Start start = start();
        if (!claims.containsKey("nonce") || claims.get("nonce") == null) {
            claims.put("nonce", start.nonce());
        } else if ("PLACEHOLDER".equals(claims.get("nonce"))) {
            claims.put("nonce", start.nonce());
        }
        NEXT_ID_TOKEN.set(token(claims, key, kid));
        MvcResult result = callback(start.state());
        assertEquals("http://hr.test/login?ssoError=" + code, result.getResponse().getHeader("Location"),
                "expected " + code + " for " + claims);
        assertNull(AuthTestSupport.refreshCookie(result, "rp_refresh_staff"));
    }

    private Map<String, Object> claims(String nonce) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", CLIENT_ID);
        claims.put("sub", "sub-alice");
        claims.put("iat", Instant.now().minusSeconds(5));
        claims.put("exp", Instant.now().plusSeconds(300));
        claims.put("nonce", nonce);
        claims.put("email", "alice@acme.test");
        claims.put("email_verified", true);
        return claims;
    }

    /** Default claims with one change; the nonce is filled in from the authorisation request unless overridden. */
    private Map<String, Object> claimsWith(String name, Object value) {
        Map<String, Object> claims = claims("PLACEHOLDER");
        if (name != null) {
            if (value == null) {
                claims.remove(name);
            } else {
                claims.put(name, value);
            }
        }
        return claims;
    }

    private Map<String, Object> withEmail(String email, String subject) {
        Map<String, Object> claims = claims("PLACEHOLDER");
        claims.put("email", email);
        claims.put("sub", subject);
        return claims;
    }

    static String token(Map<String, Object> claims, PrivateKey key, String kid) {
        var builder = Jwts.builder().header().keyId(kid).and();
        for (Map.Entry<String, Object> claim : claims.entrySet()) {
            Object value = claim.getValue();
            switch (claim.getKey()) {
                case "iss" -> builder.issuer((String) value);
                case "sub" -> builder.subject((String) value);
                case "aud" -> builder.audience().add((String) value).and();
                case "iat" -> builder.issuedAt(Date.from((Instant) value));
                case "exp" -> builder.expiration(Date.from((Instant) value));
                default -> builder.claim(claim.getKey(), value);
            }
        }
        return builder.signWith(key, Jwts.SIG.RS256).compact();
    }

    static String jwks(RSAPublicKey key) {
        return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + KID + "\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
                + base64Url(key.getModulus()) + "\",\"e\":\"" + base64Url(key.getPublicExponent()) + "\"},"
                + "{\"kty\":\"RSA\",\"kid\":\"enc-key\",\"use\":\"enc\",\"n\":\"" + base64Url(key.getModulus())
                + "\",\"e\":\"AQAB\"}]}";
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Map<String, String> form(String body) {
        Map<String, String> values = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static String s256(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
