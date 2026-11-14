package com.recruitpro.auth.service;

import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.auth.entity.RefreshToken;
import com.recruitpro.auth.repository.RefreshTokenRepository;
import com.recruitpro.auth.web.Portal;
import com.recruitpro.common.exception.UnauthorizedException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * D11 / S2 / M6: refresh-token rotation with families. A presented active token is rotated (revoked with
 * reason ROTATED and replaced by the next token of the same family). Presenting a token rotated less than
 * {@code app.auth.refresh-grace-seconds} (30 s) ago returns a fresh access token without a new refresh token
 * (concurrent tabs). Presenting it later is treated as theft: the whole family is revoked (REUSE_DETECTED).
 */
@Service
public class RefreshTokenService {

    public static final String REASON_ROTATED = "ROTATED";
    public static final String REASON_LOGOUT = "LOGOUT";
    public static final String REASON_REUSE_DETECTED = "REUSE_DETECTED";
    public static final String REASON_PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String REASON_ACCOUNT_DISABLED = "ACCOUNT_DISABLED";

    static final String INVALID_REFRESH_TOKEN = "Invalid or expired refresh token";
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository repository;
    private final AuditService auditService;
    private final long refreshTokenExpiryMs;
    private final long graceSeconds;

    public RefreshTokenService(RefreshTokenRepository repository, AuditService auditService,
                               @Value("${app.jwt.refresh-token-expiry-ms}") long refreshTokenExpiryMs,
                               @Value("${app.auth.refresh-grace-seconds:30}") long graceSeconds) {
        this.repository = repository;
        this.auditService = auditService;
        this.refreshTokenExpiryMs = refreshTokenExpiryMs;
        this.graceSeconds = graceSeconds;
    }

    /** Starts a new family (a new sign-in) and returns the raw token. */
    @Transactional
    public String issueNewFamily(User user) {
        return issue(user, UUID.randomUUID().toString());
    }

    /** Issues the next token of a family and returns the raw token. */
    @Transactional
    public String issue(User user, String familyId) {
        String raw = TokenHashing.newToken();
        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash(TokenHashing.sha256(raw))
                .familyId(familyId)
                .expiresAt(UtcTime.now().plusNanos(refreshTokenExpiryMs * 1_000_000L))
                .build();
        repository.save(token);
        return raw;
    }

    /**
     * Rotates a presented refresh token. Throws 401 for unknown, expired, revoked or reused tokens; the
     * family revocation for reuse is committed even though the call fails.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public Rotation rotate(String rawToken) {
        return rotate(rawToken, null);
    }

    /**
     * Rotates a refresh token presented to a portal. When the token's account does not belong to the portal
     * (see {@link Portal#allows}) the call fails with 403 before anything is rotated or revoked.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public Rotation rotate(String rawToken, Portal portal) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 256) {
            throw new UnauthorizedException(INVALID_REFRESH_TOKEN);
        }
        RefreshToken current = repository.findByTokenHashForUpdate(TokenHashing.sha256(rawToken.trim()))
                .orElseThrow(() -> new UnauthorizedException(INVALID_REFRESH_TOKEN));
        LocalDateTime now = UtcTime.now();
        User user = current.getUser();
        if (!current.getExpiresAt().isAfter(now)) {
            throw new UnauthorizedException(INVALID_REFRESH_TOKEN);
        }
        if (!user.isEnabled() || user.getDeletedAt() != null) {
            repository.revokeFamily(current.getFamilyId(), REASON_ACCOUNT_DISABLED, now);
            throw new UnauthorizedException(INVALID_REFRESH_TOKEN);
        }
        Portal.requireAllowed(portal, user.getRole());
        if (current.getRevokedAt() == null) {
            current.setRevokedAt(now);
            current.setReplacedAt(now);
            current.setRevokedReason(REASON_ROTATED);
            repository.save(current);
            return new Rotation(user, issue(user, current.getFamilyId()));
        }
        boolean rotated = REASON_ROTATED.equals(current.getRevokedReason());
        boolean withinGrace = current.getReplacedAt() != null
                && current.getReplacedAt().isAfter(now.minusSeconds(graceSeconds));
        if (rotated && withinGrace && repository.existsByFamilyIdAndRevokedAtIsNull(current.getFamilyId())) {
            return new Rotation(user, null);
        }
        if (rotated) {
            int revoked = repository.revokeFamily(current.getFamilyId(), REASON_REUSE_DETECTED, now);
            if (revoked > 0) {
                // Only a family that was still active indicates theft; a stale tab after logout does not.
                log.warn("Refresh token reuse detected for user {}; revoked {} active token(s)", user.getId(), revoked);
                auditService.recordFor(user.getOrganizationId(), user.getId(), user.getEmail(),
                        AuditActions.REFRESH_TOKEN_REUSE_DETECTED, "USER", String.valueOf(user.getId()),
                        "A rotated refresh token was presented again; the session family was revoked");
            }
        }
        throw new UnauthorizedException(INVALID_REFRESH_TOKEN);
    }

    /** Logout: revokes every active token of the presented token's family. Unknown tokens are ignored. */
    @Transactional
    public void revokeFamilyOf(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 256) {
            return;
        }
        repository.findByTokenHashForUpdate(TokenHashing.sha256(rawToken.trim()))
                .ifPresent(token -> repository.revokeFamily(token.getFamilyId(), REASON_LOGOUT, UtcTime.now()));
    }

    /** Password reset/change, account disable: revokes all of the user's refresh tokens. */
    @Transactional
    public int revokeAllForUser(Long userId, String reason) {
        return repository.revokeAllForUser(userId, reason, UtcTime.now());
    }

    /** Outcome of a rotation; {@code refreshToken} is null inside the concurrent-tab grace window. */
    public record Rotation(User user, String refreshToken) {
    }
}
