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
import com.recruitpro.verification.service.GitHubOAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

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

/** GitHub OAuth: hashed single-use state with expiry, public callback, encrypted token (plan v2 section 5). */
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
        String authorizeUrl = data(mvc.perform(get(START).with(candidate("oauth@cand.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorizeUrl").value(startsWith("https://github.com/login/oauth/authorize?")))
                .andReturn()).path("authorizeUrl").asText();
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

        when(gitHubClient.exchangeCode(eq("test-client-id"), eq("test-client-secret"), eq("the-code"), anyString()))
                .thenReturn(new GitHubOAuthClient.TokenResponse(TOKEN, "read:user", "bearer", null));
        when(gitHubClient.fetchUser(TOKEN)).thenReturn(new GitHubOAuthClient.GitHubUser("Octo-Dev", 42L, "Octo Dev"));

        // Public: no authentication on the callback.
        mvc.perform(get(CALLBACK).param("code", "the-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/verification?connected=github"));

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
        mvc.perform(get(CALLBACK).param("code", "the-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/verification?error=STATE_USED"));
        verify(gitHubClient, org.mockito.Mockito.times(1)).exchangeCode(any(), any(), any(), any());

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

        VerificationOAuthState valid = new VerificationOAuthState();
        valid.setStateHash(sha256("denied-state-value"));
        valid.setCandidateId(me.getId());
        valid.setProvider("GITHUB");
        valid.setExpiresAt(UtcTime.now().plusMinutes(10));
        states.save(valid);
        mvc.perform(get(CALLBACK).param("error", "access_denied").param("state", "denied-state-value"))
                .andExpect(header().string("Location", "http://localhost:5173/verification?error=ACCESS_DENIED"));
        assertNotNull(states.findByStateHash(sha256("denied-state-value")).orElseThrow().getUsedAt(), "consumed anyway");

        VerificationOAuthState unreachable = new VerificationOAuthState();
        unreachable.setStateHash(sha256("network-state-value"));
        unreachable.setCandidateId(me.getId());
        unreachable.setProvider("GITHUB");
        unreachable.setExpiresAt(UtcTime.now().plusMinutes(10));
        states.save(unreachable);
        when(gitHubClient.exchangeCode(any(), any(), any(), any())).thenThrow(new ResourceAccessException("down"));
        mvc.perform(get(CALLBACK).param("code", "c").param("state", "network-state-value"))
                .andExpect(header().string("Location", "http://localhost:5173/verification?error=GITHUB_UNAVAILABLE"));
        verify(gitHubClient, never()).fetchUser(any());
        assertTrue(sources.findAllByCandidateIdAndKindAndRevokedAtIsNull(me.getId(), VerificationSourceKind.GITHUB).isEmpty());
        assertNull(states.findByStateHash(sha256("never-issued")).orElse(null));
    }

    @Test
    void startRequiresACandidate() throws Exception {
        mvc.perform(get(START)).andExpect(status().isUnauthorized());
        mvc.perform(get(START).with(staff("hr@org.test", "HR_MANAGER"))).andExpect(status().isForbidden());
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
