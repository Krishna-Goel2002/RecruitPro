package com.recruitpro.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.auth.service.AuthService;
import com.recruitpro.auth.service.AuthSession;
import com.recruitpro.auth.service.TokenHashing;
import com.recruitpro.auth.web.Portal;
import com.recruitpro.common.crypto.SecretCipher;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.organization.dto.SsoDomains;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import com.recruitpro.user.service.UserAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * OIDC single sign-on for staff (plan v2, Section 6.2): authorisation code flow with PKCE (S256), state and nonce
 * (stored hashed, single use, 10 minutes), id_token validation against the provider's JWKS, and linking to an
 * existing, enabled staff account: first by a linked identity (provider + subject), otherwise by the verified e-mail
 * address when the account's organisation lists the e-mail domain in {@code sso_allowed_domains}. Accounts are never
 * created. The session is a normal staff session (refresh cookie {@code rp_refresh_staff}); MFA is considered
 * satisfied by the identity provider. Every outcome ends in a redirect to the HR portal.
 */
@Service
public class SsoService {

    private static final Logger log = LoggerFactory.getLogger(SsoService.class);
    static final String STAFF_PORTAL = "STAFF";

    private final SsoProviderRegistry registry;
    private final OidcHttpClient http;
    private final OidcTokenValidator validator;
    private final SsoLoginStateRepository stateRepository;
    private final UserIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final UserAccountService userAccountService;
    private final AuthService authService;
    private final AuditService auditService;
    private final SecretCipher cipher;
    private final SsoProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final String hrPortalUrl;

    public SsoService(SsoProviderRegistry registry, OidcHttpClient http, OidcTokenValidator validator,
                      SsoLoginStateRepository stateRepository, UserIdentityRepository identityRepository,
                      UserRepository userRepository, OrganizationRepository organizationRepository,
                      UserAccountService userAccountService, AuthService authService, AuditService auditService,
                      SecretCipher cipher, SsoProperties properties, ObjectMapper objectMapper,
                      PlatformTransactionManager transactionManager,
                      @Value("${app.frontend.hr-url:http://localhost:5174}") String hrPortalUrl) {
        this.registry = registry;
        this.http = http;
        this.validator = validator;
        this.stateRepository = stateRepository;
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.userAccountService = userAccountService;
        this.authService = authService;
        this.auditService = auditService;
        this.cipher = cipher;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.hrPortalUrl = trimSlash(hrPortalUrl);
    }

    /** Providers offered on the HR sign-in page; empty without the encryption key (the PKCE verifier is encrypted). */
    public List<SsoProviderRegistry.ProviderSummary> providers() {
        return cipher.isConfigured() ? registry.configured() : List.of();
    }

    /** Builds the provider's authorisation URL and stores the hashed state, hashed nonce and encrypted verifier. */
    public String start(String providerId) {
        if (!cipher.isConfigured()) {
            throw new SsoException("not_configured", "SSO needs app.encryption.key");
        }
        SsoProviderRegistry.ResolvedProvider provider = registry.resolve(providerId);
        String state = TokenHashing.newToken();
        String nonce = TokenHashing.newToken();
        String verifier = TokenHashing.newToken() + TokenHashing.newToken();
        String stateHash = TokenHashing.sha256(state);
        tx.executeWithoutResult(status -> {
            stateRepository.deleteExpiredBefore(UtcTime.now().minusDays(1));
            SsoLoginState row = new SsoLoginState();
            row.setStateHash(stateHash);
            row.setNonceHash(TokenHashing.sha256(nonce));
            row.setCodeVerifierEnc(cipher.encrypt(verifier, context(stateHash)));
            row.setProvider(provider.id());
            row.setPortal(STAFF_PORTAL);
            row.setExpiresAt(UtcTime.now().plusMinutes(Math.max(1, properties.getStateTtlMinutes())));
            stateRepository.save(row);
        });
        return UriComponentsBuilder.fromUriString(provider.authorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", provider.clientId())
                .queryParam("redirect_uri", redirectUri(provider.id()))
                .queryParam("scope", provider.scopes())
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", codeChallenge(verifier))
                .queryParam("code_challenge_method", "S256")
                .queryParam("prompt", "select_account")
                .encode()
                .build()
                .toUriString();
    }

    /**
     * Handles the provider's redirect. Returns where to send the browser and, on success, the session whose refresh
     * token goes into the staff cookie.
     */
    public CallbackResult callback(String providerId, String code, String state, String error) {
        try {
            return new CallbackResult(hrPortalUrl + "/sso/complete", completeLogin(providerId, code, state, error));
        } catch (SsoException ex) {
            log.info("SSO sign-in refused: {}", ex.code());
            return new CallbackResult(hrPortalUrl + "/login?ssoError=" + ex.code(), null);
        } catch (RuntimeException ex) {
            log.warn("SSO sign-in failed: {}", ex.getClass().getSimpleName());
            return new CallbackResult(hrPortalUrl + "/login?ssoError=provider_error", null);
        }
    }

    /** Where to send the browser when {@code start} fails before reaching the provider. */
    public String errorRedirect(String code) {
        return hrPortalUrl + "/login?ssoError=" + code;
    }

    private AuthSession completeLogin(String providerId, String code, String state, String error) {
        String normalizedProvider = SsoProviderRegistry.normalizeId(providerId);
        PendingLogin pending = consumeState(normalizedProvider, state);
        if (error != null && !error.isBlank()) {
            throw new SsoException("access_denied".equals(error) ? "cancelled" : "provider_error", "The provider reported an error");
        }
        if (code == null || code.isBlank() || code.length() > 4096) {
            throw new SsoException("provider_error", "The provider returned no authorisation code");
        }
        SsoProviderRegistry.ResolvedProvider provider = registry.resolve(normalizedProvider);
        String idToken = exchange(provider, code, pending.codeVerifier());
        OidcTokenValidator.VerifiedIdentity identity = validator.validate(provider, idToken, pending.nonceHash());
        User user = tx.execute(status -> link(provider.id(), identity));
        if (user == null) {
            throw new SsoException("no_account", "No account");
        }
        userAccountService.recordSuccessfulLogin(user.getId());
        return authService.createSession(user);
    }

    /** Marks the state used (committed before the token exchange, so it can never be replayed) and decrypts the verifier. */
    private PendingLogin consumeState(String providerId, String state) {
        if (state == null || state.isBlank() || state.length() > 200 || providerId == null) {
            throw new SsoException("invalid_state", "Missing or malformed state");
        }
        String stateHash = TokenHashing.sha256(state.trim());
        PendingLogin pending = tx.execute(status -> {
            SsoLoginState row = stateRepository.lockByStateHash(stateHash).orElse(null);
            if (row == null || row.getUsedAt() != null || !row.getExpiresAt().isAfter(UtcTime.now())) {
                return null;
            }
            row.setUsedAt(UtcTime.now());
            if (!row.getProvider().equals(providerId) || !STAFF_PORTAL.equals(row.getPortal())) {
                return null;
            }
            return new PendingLogin(row.getNonceHash(), cipher.decrypt(row.getCodeVerifierEnc(), context(stateHash)));
        });
        if (pending == null) {
            throw new SsoException("invalid_state", "Unknown, used or expired state");
        }
        return pending;
    }

    private String exchange(SsoProviderRegistry.ResolvedProvider provider, String code, String verifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri(provider.id()));
        form.add("client_id", provider.clientId());
        form.add("client_secret", provider.clientSecret());
        form.add("code_verifier", verifier);
        try {
            JsonNode response = objectMapper.readTree(http.postForm(provider.tokenUri(), form));
            JsonNode idToken = response == null ? null : response.get("id_token");
            if (idToken == null || !idToken.isTextual()) {
                throw new SsoException("token_exchange_failed", "The token response has no id_token");
            }
            return idToken.asText();
        } catch (SsoException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SsoException("token_exchange_failed", "The authorisation code could not be exchanged", ex);
        }
    }

    /**
     * The staff account for the identity (inside a transaction): a linked identity first, else an enabled staff user
     * with the same verified e-mail whose organisation allows the domain (the identity is then linked).
     */
    private User link(String providerId, OidcTokenValidator.VerifiedIdentity identity) {
        UserIdentity linked = identityRepository.findByProviderAndSubject(providerId, identity.subject()).orElse(null);
        User user;
        if (linked != null) {
            user = userRepository.findById(linked.getUserId()).orElse(null);
            if (user == null) {
                throw refused(null, identity, "no_account");
            }
        } else {
            user = userRepository.findByEmail(identity.email()).orElse(null);
            if (user == null) {
                throw refused(null, identity, "no_account");
            }
        }
        if (user.getRole() == null || user.getRole() == Role.CANDIDATE || user.getOrganizationId() == null) {
            throw refused(null, identity, "no_account");
        }
        if (!user.isEnabled() || user.getDeletedAt() != null) {
            throw refused(user, identity, "account_disabled");
        }
        Organization organization = organizationRepository.findById(user.getOrganizationId()).orElse(null);
        String allowed = organization == null ? null : organization.getSsoAllowedDomains();
        if (!SsoDomains.allows(allowed, identity.email()) || !SsoDomains.allows(allowed, user.getEmail())) {
            throw refused(user, identity, "domain_not_allowed");
        }
        if (linked == null && !Objects.equals(user.getEmail().toLowerCase(Locale.ROOT), identity.email())) {
            throw refused(user, identity, "no_account");
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(UtcTime.now())) {
            throw refused(user, identity, "account_locked");
        }
        LocalDateTime now = UtcTime.now();
        if (linked == null) {
            linked = new UserIdentity();
            linked.setUserId(user.getId());
            linked.setProvider(providerId);
            linked.setSubject(identity.subject());
            auditService.recordFor(user.getOrganizationId(), user.getId(), user.getEmail(), AuditActions.SSO_IDENTITY_LINKED,
                    "USER", String.valueOf(user.getId()), "Linked a " + providerId + " identity by verified e-mail");
        }
        linked.setEmail(identity.email());
        linked.setLastLoginAt(now);
        identityRepository.save(linked);
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(now);
        }
        auditService.recordFor(user.getOrganizationId(), user.getId(), user.getEmail(), AuditActions.SSO_LOGIN,
                "USER", String.valueOf(user.getId()), "Signed in with " + providerId);
        return user;
    }

    private SsoException refused(User user, OidcTokenValidator.VerifiedIdentity identity, String code) {
        // Written in its own transaction: the caller's transaction is rolled back by the exception.
        TransactionTemplate own = new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        own.executeWithoutResult(status -> auditService.recordFor(user == null ? null : user.getOrganizationId(),
                user == null ? null : user.getId(), user == null ? "SYSTEM" : user.getEmail(), AuditActions.SSO_LOGIN_REFUSED,
                "USER", user == null ? null : String.valueOf(user.getId()),
                "SSO sign-in refused (" + code + ") for an identity with e-mail domain " + domainOf(identity.email())));
        return new SsoException(code, "SSO sign-in refused: " + code);
    }

    String redirectUri(String providerId) {
        return trimSlash(properties.getRedirectBaseUrl()) + "/api/v1/auth/sso/" + providerId + "/callback";
    }

    static String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String context(String stateHash) {
        return "sso:" + stateHash;
    }

    private static String domainOf(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at < 0 ? "?" : email.substring(at + 1);
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** Where to redirect and the session to put into the staff cookie (null when refused). */
    public record CallbackResult(String redirectUrl, AuthSession session) {
    }

    private record PendingLogin(String nonceHash, String codeVerifier) {
    }

    /** The portal SSO sessions belong to. */
    public static Portal portal() {
        return Portal.STAFF;
    }
}
