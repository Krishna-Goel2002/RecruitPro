package com.recruitpro.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.auth.dto.LoginRequest;
import com.recruitpro.auth.dto.RefreshTokenRequest;
import com.recruitpro.auth.dto.RegisterRequest;
import com.recruitpro.auth.repository.RefreshTokenRepository;
import com.recruitpro.auth.entity.RefreshToken;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.security.jwt.JwtService;
import com.recruitpro.security.userdetails.CustomUserDetails;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.util.Date;
import java.util.Map;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired CandidateProfileRepository candidateProfileRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        candidateProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void registrationHashesPasswordReturnsOnlySafeUserAndIgnoresRequestedRole() throws Exception {
        RegisterRequest request = registration("candidate@example.com");
        request.setRole("ADMIN");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.role").value("CANDIDATE")).andReturn();
        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("passwordHash"));
        assertFalse(body.contains("SecurePassword123!"));
        User saved = userRepository.findByEmail("candidate@example.com").orElseThrow();
        assertNotEquals("SecurePassword123!", saved.getPasswordHash());
        assertTrue(passwordEncoder.matches("SecurePassword123!", saved.getPasswordHash()));
        assertEquals(1, refreshTokenRepository.count());
    }

    @Test
    void registrationRejectsInvalidEmailBlankNameAndWeakPassword() throws Exception {
        RegisterRequest invalidEmail = registration("bad-email");
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidEmail)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
        RegisterRequest blankName = registration("blank@example.com");
        blankName.setFirstName(" ");
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blankName)))
                .andExpect(status().isBadRequest());
        RegisterRequest weak = registration("weak@example.com");
        weak.setPassword("short");
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(weak)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateEmailUsesSharedErrorContract() throws Exception {
        userRepository.save(user("duplicate@example.com", Role.CANDIDATE, true));
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registration("duplicate@example.com"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Email is already registered"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void databaseEnforcesEmailUniqueness() {
        userRepository.saveAndFlush(user("unique@example.com", Role.CANDIDATE, true));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> userRepository.saveAndFlush(user("unique@example.com", Role.RECRUITER, true)));
    }

    @Test
    void loginHandlesValidInvalidUnknownAndDisabledUsers() throws Exception {
        userRepository.save(user("login@example.com", Role.CANDIDATE, true));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login("login@example.com", "SecurePassword123!"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.password").doesNotExist());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login("login@example.com", "wrong-password"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").exists());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login("absent@example.com", "SecurePassword123!"))))
                .andExpect(status().isUnauthorized());
        userRepository.save(user("disabled@example.com", Role.CANDIDATE, false));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login("disabled@example.com", "SecurePassword123!"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesTokensAndLogoutRevokesRefreshToken() throws Exception {
        userRepository.save(user("refresh@example.com", Role.CANDIDATE, true));
        JsonNode loginResult = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login("refresh@example.com", "SecurePassword123!"))))
                .andReturn().getResponse().getContentAsString()).get("data");
        String firstRefresh = loginResult.get("refreshToken").asText();
        JsonNode refreshed = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(refreshBody(firstRefresh)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        String secondRefresh = refreshed.get("refreshToken").asText();
        assertNotEquals(firstRefresh, secondRefresh);
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(firstRefresh)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(secondRefresh)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(secondRefresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRejectsExpiredTokens() throws Exception {
        User user = userRepository.save(user("expired-refresh@example.com", Role.CANDIDATE, true));
        String rawToken = "expired-refresh-token";
        refreshTokenRepository.save(RefreshToken.builder().user(user).tokenHash(hashForTest(rawToken))
                .expiresAt(LocalDateTime.now().minusMinutes(1)).build());
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(rawToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessTokenRejectsMissingInvalidMalformedAndExpiredTokens() throws Exception {
        mockMvc.perform(get("/test/security/admin")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/test/security/admin").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/test/security/admin").header("Authorization", "Bearer " + forgedJwt()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/test/security/admin").header("Authorization", "not-a-bearer-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/test/security/admin").header("Authorization", "Bearer " + expiredJwt()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void eachRoleCanAccessOnlyItsOwnProtectedEndpoint() throws Exception {
        Map<Role, String> endpoints = Map.of(Role.ADMIN, "admin", Role.HR_MANAGER, "hr-manager",
                Role.RECRUITER, "recruiter", Role.INTERVIEWER, "interviewer", Role.CANDIDATE, "candidate");
        for (Map.Entry<Role, String> allowed : endpoints.entrySet()) {
            User saved = userRepository.save(user(allowed.getKey().name().toLowerCase() + "@example.com", allowed.getKey(), true));
            String token = jwtService.generateToken(new CustomUserDetails(saved));
            for (String endpoint : endpoints.values()) {
                int expected = endpoint.equals(allowed.getValue()) ? 200 : 403;
                mockMvc.perform(get("/test/security/" + endpoint).header("Authorization", "Bearer " + token))
                        .andExpect(status().is(expected));
            }
        }
    }

    private RegisterRequest registration(String email) {
        return RegisterRequest.builder().firstName("Casey").lastName("Candidate").email(email)
                .password("SecurePassword123!").build();
    }

    private LoginRequest login(String email, String password) {
        return LoginRequest.builder().email(email).password(password).build();
    }

    private User user(String email, Role role, boolean enabled) {
        return User.builder().firstName("Test").lastName("User").email(email)
                .passwordHash(passwordEncoder.encode("SecurePassword123!")).role(role).enabled(enabled).build();
    }

    private String refreshBody(String token) throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken(token);
        return objectMapper.writeValueAsString(request);
    }

    private String expiredJwt() {
        String secret = (String) ReflectionTestUtils.getField(jwtService, "secretKey");
        String issuer = (String) ReflectionTestUtils.getField(jwtService, "issuer");
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder().issuer(issuer).subject("expired@example.com")
                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(Instant.now().minusSeconds(60))).signWith(key).compact();
    }

    private String forgedJwt() {
        SecretKey wrongKey = Keys.hmacShaKeyFor("unrelated-test-signing-key-long-enough-to-be-valid".getBytes(StandardCharsets.UTF_8));
        return Jwts.builder().issuer("recruitment-platform").subject("forged@example.com")
                .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(60))).signWith(wrongKey).compact();
    }

    private String hashForTest(String rawToken) throws Exception {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
    }
}
