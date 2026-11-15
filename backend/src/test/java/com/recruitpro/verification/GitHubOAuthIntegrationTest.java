package com.recruitpro.verification;

import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.common.crypto.SecretCipher;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.verification.client.GitHubOAuthClient;
import com.recruitpro.verification.entity.OwnershipMethod;
import com.recruitpro.verification.entity.OwnershipStatus;
import com.recruitpro.verification.entity.VerificationOAuthState;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.VerificationOAuthStateRepository;
import com.recruitpro.verification.repository.VerificationSourceRepository;
import com.recruitpro.verification.service.GitHubOAuthBindingCookie;
import com.recruitpro.verification.service.GitHubOAuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GitHub OAuth: hashed single-use state with expiry, bound to the starting browser by an httpOnly cookie, PKCE (S256)
 * with an encrypted verifier, public callback, encrypted token (plan v2 section 5; review fix 16 Nov 2026).
 */
class GitHubOAuthIntegrationTest extends VerificationIntegrationTestBase {

    private static final String START = "/api/v1/candidates/me/verification/oauth/github/start";
    private static final String CALLBACK = "/api/v1/verification/oauth/github/callback";
    private static final String TOKEN = "gho_plaintextSecretToken123";

    @Autowired VerificationOAuthStateRepository states;
    @Autowired VerificationSourceRepository sources;
    @Autowired SecretCipher cipher;
    @Autowired JdbcTemplate jdbc;

    @Test
    void connectingStoresAnEncryptedTokenAndProvesOwnership() throws Exception {
        CandidateProfile me = fixtures.candidate("oauth@cand.test");
        var started = mvc.perform(get(START).with(candidate("oauth@cand.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorizeUrl").value(startsWith("https://github.com/login/oauth/authorize?")))
                .andReturn();
        String authorizeUrl = data(started).path("authorizeUrl").asText();
        var params = UriComponentsBuilder.fromUriString(authorizeUrl).build().getQueryParams();
        assertEquals("test-client-id", params.getFirst("client_id"));
        assertEquals("read:user", java.net.URLDecoder.decode(params.getFirst("scope"), StandardCharsets.UTF_8));
        String state = params.getFirst("state");
        assertNotNull(state);
        assertTrue(state.length() >= 43, "32 random bytes, Base64url");
        VerificationOAuthState row = states.findByStateHash(sha256(state)).orElseThrow();
        assertEquals(me.getId(), row.getCandidateId());
        assertTrue(row.getExpiresAt().isAfter(UtcTime.now().plusMinutes(9)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM verification_oauth_states WHERE state_hash = ?",
                Integer.class, state), "the raw state is never stored");

        // Browser binding: httpOnly, SameSite=Lax cookie scoped to the callback path; only its hash is stored.
        String setCookie = started.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.startsWith(GitHubOAuthBindingCookie.NAME + "="), setCookie);
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
        assertTrue(setCookie.contains("SameSite=Lax"), setCookie);
        assertTrue(setCookie.contains("Path=/api/v1/verification/oauth/github/callback"), setCookie);
        String nonce = setCookie.substring(GitHubOAuthBindingCookie.NAME.length() + 1, setCookie.indexOf(';'));
        assertTrue(nonce.length() >= 43);
        assertEquals(sha256(nonce), row.getBrowserBindingHash());

        // PKCE S256: the verifier is stored encrypted and matches the challenge in the authorize URL.
        assertEquals("S256", params.getFirst("code_challenge_method"));
        assertNotNull(row.getCodeVerifierEnc());
        assertTrue(row.getCodeVerifierEnc().startsWith("v1:"));
        String verifier = cipher.decrypt(row.getCodeVerifierEnc(), "voauth:" + row.getStateHash());
        assertTrue(verifier.length() >= 43 && verifier.length() <= 128);
        assertEquals(s256(verifier), params.getFirst("code_challenge"));

        when(gitHubClient.exchangeCode(eq("test-client-id"), eq("test-client-secret"), eq("the-code"), anyString(), eq(verifier)))
                .thenReturn(new GitHubOAuthClient.TokenResponse(TOKEN, "read:user", "bearer", null));
        when(gitHubClient.fetchUser(TOKEN)).thenReturn(new GitHubOAuthClient.GitHubUser("Octo-Dev", 42L, "Octo Dev"));

        // Without the cookie (another browser) or with a wrong one: refused, the state stays usable, GitHub not called.
        mvc.perform(get(CALLBACK).param("code", "the-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/verification?error=BROWSER_MISMATCH"));
        mvc.perform(get(CALLBACK).param("code", "the-code").param("state", state)
                        .cookie(new Cookie(GitHubOAuthBindingCookie.NAME, "not-the-nonce")))
                .andExpect(header().string("Location", "http://localhost:5173/verification?error=BROWSER_MISMATCH"));
        assertNull(states.findByStateHash(sha256(state)).orElseThrow().getUsedAt(), "a mismatch does not consume the state");
        verify(gitHubClient, never()).exchangeCode(any(), any(), any(), any(), any());

        // Public (no authentication), with the binding cookie; the answer clears the cookie.
        mvc.perform(get(CALLBACK).param("code", "the-code").param("state", state)
                        .cookie(new Cookie(GitHubOAuthBindingCookie.NAME, nonce)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/verification?connected=github"))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        List<VerificationSource> connected = sources.findAllByCandidateIdAndKindAndRevokedAtIsNull(me.getId(), VerificationSourceKind.GITHUB);
        assertEquals(1, connected.size());
        VerificationSource source = connected.get(0);
        assertEquals("octo-dev", source.getIdentifier());
        assertEquals(OwnershipStatus.PROVEN, source.getOwnershipStatus());
        assertEquals(OwnershipMethod.OAUTH, source.getOwnershipMethod());
        assertEquals("read:user", source.getTokenScopes());
        String stored = jdbc.queryForObject("SELECT access_token_enc FROM verification_sources WHERE id = ?", String.class, source.getId());
        assertNotNull(stored);
        assertFalse(stored.contains(TOKEN), "no plaintext token in the database");
        assertTrue(stored.startsWith("v1:"));
        assertEquals(TOKEN, cipher.decrypt(stored, "vsrc:" + source.getId()));
        assertEquals(TOKEN, cipher.decrypt(stored, GitHubOAuthService.tokenContext(source.getId())));

        // Single use: replaying the state fails without calling GitHub again.
        mvc.perform(get(CALLBACK).param("code", "the-code").param("state", state)
                        .cookie(new Cookie(GitHubOAuthBindingCookie.NAME, nonce)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/verification?error=STATE_USED"));
        verify(gitHubClient, org.mockito.Mockito.times(1)).exchangeCode(any(), any(), any(), any(), any());

        // The source response never carries the token.
        mvc.perform(get("/api/v1/candidates/me/verification/sources").with(candidate("oauth@cand.test")))
                .andExpect(jsonPath("$.data[0].ownershipMethod").value("OAUTH"))
                .andExpect(jsonPath("$.data[0].accessTokenEnc").doesNotExist());
    }

    @Test
    void expiredUnknownAndDeniedStatesRedirectWithAnError() throws Exception {
        CandidateProfile me = fixtures.candidate("expired@cand.test");
        VerificationOAuthState expired = new VerificationOAuthState();
        expired.setStateHash(sha256("expired-state-value"));
        expired.setCandidateId(me.getId());
        expired.setProvider("GITHUB");
        expired.setExpiresAt(UtcTime.now().minusMinutes(1));
        states.save(expired);
        mvc.perform(get(CALLBACK).param("code", "c").param("state", "expired-state-value"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/verification?error=STATE_EXPIRED"));
        mvc.perform(get(CALLBACK).param("code", "c").param("state", "never-issued"))
                .andExpect(header().string("Location", "http://localhost:5173/verification?error=STATE_INVALID"));
        mvc.perform(get(CALLBACK).param("code", "c"))
                .andExpect(header().string("Location", "http://localhost:5173/verification?error=STATE_INVALID"));

        pendingState(me.getId(), "denied-state-value", "denied-nonce", UtcTime.now().plusMinutes(10));
        mvc.perform(get(CALLBACK).param("error", "access_denied").param("state", "denied-state-value")
                        .cookie(new Cookie(GitHubOAuthBindingCookie.NAME, "denied-nonce")))
                .andExpect(header().string("Location", "http://localhost:5173/verification?error=ACCESS_DENIED"));
        assertNotNull(states.findByStateHash(sha256("denied-state-value")).orElseThrow().getUsedAt(), "consumed anyway");

        pendingState(me.getId(), "network-state-value", "network-nonce", UtcTime.now().plusMinutes(10));
        when(gitHubClient.exchangeCode(any(), any(), any(), any(), any())).thenThrow(new ResourceAccessException("down"));
        mvc.perform(get(CALLBACK).param("code", "c").param("state", "network-state-value")
                        .cookie(new Cookie(GitHubOAuthBindingCookie.NAME, "network-nonce")))
                .andExpect(header().string("Location", "http://localhost:5173/verification?error=GITHUB_UNAVAILABLE"));
        verify(gitHubClient, never()).fetchUser(any());
        assertTrue(sources.findAllByCandidateIdAndKindAndRevokedAtIsNull(me.getId(), VerificationSourceKind.GITHUB).isEmpty());
        assertNull(states.findByStateHash(sha256("never-issued")).orElse(null));
    }

    @Test
    void anAttackersStateOpenedInTheVictimsBrowserIsRefused() throws Exception {
        // Login-CSRF / account linking: the attacker starts the flow in their own account and gets the victim to open
        // the authorize URL. The victim's browser has no (or its own, different) binding cookie, so the victim's
        // GitHub account is never linked to the attacker's candidate account.
        CandidateProfile attacker = fixtures.candidate("attacker@cand.test");
        String attackerUrl = data(mvc.perform(get(START).with(candidate("attacker@cand.test"))).andReturn())
                .path("authorizeUrl").asText();
        String attackerState = UriComponentsBuilder.fromUriString(attackerUrl).build().getQueryParams().getFirst("state");
        fixtures.candidate("victim@cand.test");
        String victimCookie = mvc.perform(get(START).with(candidate("victim@cand.test"))).andReturn()
                .getResponse().getHeader("Set-Cookie");
        String victimNonce = victimCookie.substring(GitHubOAuthBindingCookie.NAME.length() + 1, victimCookie.indexOf(';'));

        mvc.perform(get(CALLBACK).param("code", "victim-code").param("state", attackerState))
                .andExpect(header().string("Location", "http://localhost:5173/verification?error=BROWSER_MISMATCH"));
        mvc.perform(get(CALLBACK).param("code", "victim-code").param("state", attackerState)
                        .cookie(new Cookie(GitHubOAuthBindingCookie.NAME, victimNonce)))
                .andExpect(header().string("Location", "http://localhost:5173/verification?error=BROWSER_MISMATCH"));
        verify(gitHubClient, never()).exchangeCode(any(), any(), any(), any(), any());
        assertTrue(sources.findAllByCandidateIdAndKindAndRevokedAtIsNull(attacker.getId(), VerificationSourceKind.GITHUB).isEmpty());
    }

    @Test
    void startRequiresACandidate() throws Exception {
        mvc.perform(get(START)).andExpect(status().isUnauthorized());
        mvc.perform(get(START).with(staff("hr@org.test", "HR_MANAGER"))).andExpect(status().isForbidden());
    }

    /** A pending state row as start() writes it (binding hash and encrypted PKCE verifier). */
    private void pendingState(Long candidateId, String state, String nonce, LocalDateTime expiresAt) throws Exception {
        VerificationOAuthState row = new VerificationOAuthState();
        row.setStateHash(sha256(state));
        row.setCandidateId(candidateId);
        row.setProvider("GITHUB");
        row.setExpiresAt(expiresAt);
        row.setBrowserBindingHash(sha256(nonce));
        row.setCodeVerifierEnc(cipher.encrypt("verifier-" + state + "-0123456789abcdefghijklmnopqrstuvwxyz", "voauth:" + sha256(state)));
        states.save(row);
    }

    private static String s256(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
