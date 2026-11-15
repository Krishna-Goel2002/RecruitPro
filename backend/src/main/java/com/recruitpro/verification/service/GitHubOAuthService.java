package com.recruitpro.verification.service;

import com.recruitpro.audit.service.AuditService;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.candidate.service.CandidateProfileService;
import com.recruitpro.common.crypto.SecretCipher;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.user.entity.User;
import com.recruitpro.verification.client.GitHubOAuthClient;
import com.recruitpro.verification.dto.OAuthStartResponse;
import com.recruitpro.verification.entity.OwnershipMethod;
import com.recruitpro.verification.entity.OwnershipStatus;
import com.recruitpro.verification.entity.VerificationOAuthState;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.VerificationCandidateLockRepository;
import com.recruitpro.verification.repository.VerificationOAuthStateRepository;
import com.recruitpro.verification.repository.VerificationSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * GitHub OAuth connection (plan v2 section 5): authorization code flow with scope {@code read:user}. The state is 32
 * random bytes (Base64url), stored as its SHA-256 hex with a 10-minute expiry and consumed on first use. The
 * callback is public (the browser comes back from GitHub without our access token); it exchanges the code, reads
 * {@code /user}, creates or updates the candidate's GITHUB source with ownership PROVEN by OAUTH and stores the
 * token encrypted ({@link SecretCipher}, context {@code vsrc:<sourceId>}), then redirects to the candidate portal.
 */
@Service
public class GitHubOAuthService {

    public static final Duration STATE_TTL = Duration.ofMinutes(10);
    public static final String PROVIDER = "GITHUB";
    public static final String NOT_CONFIGURED = "OAUTH_NOT_CONFIGURED";
    private static final Logger log = LoggerFactory.getLogger(GitHubOAuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GitHubOAuthSettings settings;
    private final GitHubOAuthClient client;
    private final SecretCipher cipher;
    private final CandidateProfileService profileService;
    private final CandidateProfileRepository profileRepository;
    private final VerificationOAuthStateRepository stateRepository;
    private final VerificationSourceRepository sourceRepository;
    private final VerificationCandidateLockRepository lockRepository;
    private final VerificationSourceService sourceService;
    private final AuditService auditService;
    private final TransactionTemplate transactions;
    private final String candidateUrl;

    public GitHubOAuthService(GitHubOAuthSettings settings, GitHubOAuthClient client, SecretCipher cipher,
                              CandidateProfileService profileService, CandidateProfileRepository profileRepository,
                              VerificationOAuthStateRepository stateRepository, VerificationSourceRepository sourceRepository,
                              VerificationCandidateLockRepository lockRepository, VerificationSourceService sourceService,
                              AuditService auditService, PlatformTransactionManager transactionManager,
                              @Value("${app.frontend.candidate-url:http://localhost:5173}") String candidateUrl) {
        this.settings = settings;
        this.client = client;
        this.cipher = cipher;
        this.profileService = profileService;
        this.profileRepository = profileRepository;
        this.stateRepository = stateRepository;
        this.sourceRepository = sourceRepository;
        this.lockRepository = lockRepository;
        this.sourceService = sourceService;
        this.auditService = auditService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.candidateUrl = candidateUrl.endsWith("/") ? candidateUrl.substring(0, candidateUrl.length() - 1) : candidateUrl;
    }

    /** GET /candidates/me/verification/oauth/github/start; 503 OAUTH_NOT_CONFIGURED without client or key. */
    @Transactional
    public OAuthStartResponse start() {
        if (!settings.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    NOT_CONFIGURED + ": connecting GitHub is not configured on this server");
        }
        CandidateProfile me = profileService.getOrProvisionMine();
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        VerificationOAuthState row = new VerificationOAuthState();
        row.setStateHash(sha256Hex(state));
        row.setCandidateId(me.getId());
        row.setProvider(PROVIDER);
        row.setExpiresAt(UtcTime.now().plus(STATE_TTL));
        stateRepository.save(row);
        String url = UriComponentsBuilder.fromUriString(settings.authorizeUrl())
                .queryParam("client_id", settings.clientId())
                .queryParam("redirect_uri", settings.redirectUri())
                .queryParam("scope", GitHubOAuthSettings.SCOPE)
                .queryParam("state", state)
                .queryParam("allow_signup", "false")
                .encode(StandardCharsets.UTF_8)
                .build().toUriString();
        return new OAuthStartResponse(url);
    }

    /**
     * GET /api/v1/verification/oauth/github/callback (public). Returns the portal URL to redirect to:
     * {@code <candidate-url>/verification?connected=github} or {@code ?error=<code>} with code
     * OAUTH_NOT_CONFIGURED, STATE_INVALID, STATE_EXPIRED, STATE_USED, ACCESS_DENIED, CODE_MISSING, CODE_REJECTED,
     * GITHUB_UNAVAILABLE or CONNECT_FAILED.
     */
    public String callback(String code, String state, String error) {
        if (!settings.isConfigured()) {
            return errorRedirect(NOT_CONFIGURED);
        }
        if (state == null || state.isBlank() || state.length() > 200) {
            return errorRedirect("STATE_INVALID");
        }
        StateCheck check = transactions.execute(status -> consumeState(state.trim()));
        if (check == null || check.error() != null) {
            return errorRedirect(check == null ? "STATE_INVALID" : check.error());
        }
        if (error != null && !error.isBlank()) {
            return errorRedirect("ACCESS_DENIED");
        }
        if (code == null || code.isBlank() || code.length() > 500) {
            return errorRedirect("CODE_MISSING");
        }
        String accessToken;
        String scopes;
        GitHubOAuthClient.GitHubUser user;
        try {
            GitHubOAuthClient.TokenResponse token = client.exchangeCode(settings.clientId(), settings.clientSecret(),
                    code.trim(), settings.redirectUri());
            if (token == null || token.accessToken() == null || token.accessToken().isBlank() || token.error() != null) {
                return errorRedirect("CODE_REJECTED");
            }
            accessToken = token.accessToken();
            scopes = token.scope();
            user = client.fetchUser(accessToken);
        } catch (RestClientException ex) {
            log.warn("GitHub OAuth exchange failed: {}", ex.getClass().getSimpleName());
            return errorRedirect("GITHUB_UNAVAILABLE");
        }
        if (user == null || user.login() == null || !VerificationSourceService.LOGIN.matcher(user.login()).matches()) {
            return errorRedirect("GITHUB_UNAVAILABLE");
        }
        String login = user.login().toLowerCase(Locale.ROOT);
        String grantedScopes = scopes;
        try {
            transactions.executeWithoutResult(status -> connect(check.candidateId(), login, accessToken, grantedScopes));
        } catch (RuntimeException ex) {
            log.warn("Storing the GitHub connection failed: {}", ex.getClass().getSimpleName());
            return errorRedirect("CONNECT_FAILED");
        }
        return candidateUrl + "/verification?connected=github";
    }

    private record StateCheck(Long candidateId, String error) {
    }

    private StateCheck consumeState(String state) {
        VerificationOAuthState row = stateRepository.findByStateHash(sha256Hex(state)).orElse(null);
        if (row == null || !PROVIDER.equals(row.getProvider())) {
            return new StateCheck(null, "STATE_INVALID");
        }
        if (row.getUsedAt() != null) {
            return new StateCheck(null, "STATE_USED");
        }
        LocalDateTime now = UtcTime.now();
        if (row.getExpiresAt().isBefore(now)) {
            return new StateCheck(null, "STATE_EXPIRED");
        }
        if (stateRepository.markUsed(row.getId(), now) != 1) {
            return new StateCheck(null, "STATE_USED");
        }
        return new StateCheck(row.getCandidateId(), null);
    }

    private void connect(Long candidateId, String login, String accessToken, String scopes) {
        CandidateProfile profile = profileRepository.findById(candidateId).orElseThrow(() -> new IllegalStateException("Candidate not found"));
        lockRepository.lockById(candidateId);
        VerificationSource source = null;
        for (VerificationSource existing : sourceRepository.findAllByCandidateIdAndKindAndRevokedAtIsNull(candidateId,
                VerificationSourceKind.GITHUB)) {
            if (existing.getIdentifier().equalsIgnoreCase(login)) {
                source = existing;
                break;
            }
        }
        LocalDateTime now = UtcTime.now();
        boolean created = source == null;
        if (created) {
            source = new VerificationSource();
            source.setCandidateId(candidateId);
            source.setKind(VerificationSourceKind.GITHUB);
            source.setIdentifier(login);
            source.setDisplayLabel("GitHub: " + login);
            source.setChallengeCode(VerificationSourceService.newChallengeCode());
            source.setConsentTextVersion(sourceService.consentVersion());
            source.setConsentedAt(now);
        }
        source.setOwnershipStatus(OwnershipStatus.PROVEN);
        source.setOwnershipMethod(OwnershipMethod.OAUTH);
        source.setOwnershipCheckedAt(now);
        source.setOwnershipDetail("Connected through GitHub sign-in");
        source.setAccessTokenEnc(null);
        VerificationSource saved = sourceRepository.saveAndFlush(source);
        saved.setAccessTokenEnc(cipher.encrypt(accessToken, tokenContext(saved.getId())));
        saved.setTokenScopes(scopes == null ? null : VerificationResultWriter.limit(scopes, 200));
        User user = profile.getUser();
        auditService.recordFor(null, user.getId(), user.getEmail(), VerificationAuditActions.SOURCE_CONNECTED,
                "VERIFICATION_SOURCE", String.valueOf(saved.getId()),
                (created ? "Connected" : "Re-connected") + " a GITHUB source through OAuth (scope " + GitHubOAuthSettings.SCOPE + ")");
        auditService.recordFor(null, user.getId(), user.getEmail(), VerificationAuditActions.OWNERSHIP_PROVEN,
                "VERIFICATION_SOURCE", String.valueOf(saved.getId()), "Ownership of a GITHUB source proven by OAUTH");
    }

    /** SecretCipher associated data of a source's access token. */
    public static String tokenContext(Long sourceId) {
        return "vsrc:" + sourceId;
    }

    /** Redirect used when the callback is rate-limited. */
    public String rateLimitedRedirect() {
        return errorRedirect("RATE_LIMITED");
    }

    private String errorRedirect(String code) {
        return candidateUrl + "/verification?error=" + code;
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
