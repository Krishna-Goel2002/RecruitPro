package com.recruitpro.auth.mfa;

import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.auth.entity.UserToken;
import com.recruitpro.auth.entity.UserTokenPurpose;
import com.recruitpro.auth.mfa.dto.MfaChallengeResponse;
import com.recruitpro.auth.mfa.dto.MfaEnrollResponse;
import com.recruitpro.auth.mfa.dto.MfaStatusResponse;
import com.recruitpro.auth.mfa.dto.MfaVerifyRequest;
import com.recruitpro.auth.mfa.dto.RecoveryCodesResponse;
import com.recruitpro.auth.web.Portal;
import com.recruitpro.common.crypto.SecretCipher;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.DuplicateResourceException;
import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.common.exception.UnauthorizedException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import com.recruitpro.user.service.UserAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Staff TOTP MFA (plan v2, Section 6.1): RFC 6238, HMAC-SHA1, 6 digits, 30-second steps, ±1 step, replay guard
 * through {@code user_mfa.last_used_step}. Secrets are encrypted with {@link SecretCipher}; without
 * {@code app.encryption.key} every MFA operation answers 503 {@value #NOT_CONFIGURED_CODE}. Candidates are never
 * asked for MFA.
 *
 * <p>Failures of the sign-in step (and of a confirmation made with an enrolment token) count towards the account
 * lockout of {@link UserAccountService}; they are recorded in their own transaction so that the 401 does not roll
 * them back.</p>
 */
@Service
public class MfaService {

    public static final String NOT_CONFIGURED_CODE = "MFA_NOT_CONFIGURED";
    public static final int RECOVERY_CODE_COUNT = 10;
    static final int SECRET_BYTES = 20;
    static final String INVALID_CODE = "The verification code is not valid";
    static final String LOCKED = "Too many failed attempts. Please try again later.";
    private static final char[] RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    private final UserMfaRepository mfaRepository;
    private final UserRecoveryCodeRepository recoveryCodeRepository;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final UserAccountService userAccountService;
    private final MfaTokenService tokens;
    private final SecretCipher cipher;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final SecurityUtils securityUtils;
    private final TransactionTemplate tx;
    private final String issuer;
    private final SecureRandom random = new SecureRandom();

    public MfaService(UserMfaRepository mfaRepository, UserRecoveryCodeRepository recoveryCodeRepository,
                      UserRepository userRepository, OrganizationRepository organizationRepository,
                      UserAccountService userAccountService, MfaTokenService tokens, SecretCipher cipher,
                      PasswordEncoder passwordEncoder, AuditService auditService, SecurityUtils securityUtils,
                      PlatformTransactionManager transactionManager,
                      @Value("${app.mfa.issuer:RecruitPro}") String issuer) {
        this.mfaRepository = mfaRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.userAccountService = userAccountService;
        this.tokens = tokens;
        this.cipher = cipher;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.securityUtils = securityUtils;
        this.tx = new TransactionTemplate(transactionManager);
        this.issuer = issuer == null || issuer.isBlank() ? "RecruitPro" : issuer.trim();
    }

    public boolean isAvailable() {
        return cipher.isConfigured();
    }

    // ---- sign-in (called by AuthService.login after the password check) ----

    /**
     * The second step a staff user must pass, if any: MFA enabled &rarr; {@code mfaRequired}; organisation requires MFA
     * and the user is not enrolled &rarr; {@code mfaRequired} + {@code mfaEnrollmentRequired}. Candidates: none.
     */
    public Optional<MfaChallengeResponse> challengeFor(User user) {
        if (user == null || user.getRole() == null || user.getRole() == Role.CANDIDATE) {
            return Optional.empty();
        }
        UserMfa mfa = mfaRepository.findById(user.getId()).orElse(null);
        if (mfa != null && mfa.isEnabled()) {
            if (!cipher.isConfigured()) {
                log.error("User {} has MFA enabled but app.encryption.key is not configured; sign-in refused", user.getId());
                throw notConfigured();
            }
            return Optional.of(new MfaChallengeResponse(true, false, tokens.issue(user, UserTokenPurpose.MFA_LOGIN),
                    MfaTokenService.TTL.toSeconds()));
        }
        if (organizationRequiresMfa(user.getOrganizationId())) {
            if (!cipher.isConfigured()) {
                log.warn("Organisation {} requires staff MFA but app.encryption.key is not configured; MFA not enforced",
                        user.getOrganizationId());
                return Optional.empty();
            }
            return Optional.of(new MfaChallengeResponse(true, true, tokens.issue(user, UserTokenPurpose.MFA_ENROLL),
                    MfaTokenService.TTL.toSeconds()));
        }
        return Optional.empty();
    }

    /**
     * POST /api/v1/auth/mfa/verify: checks the token and the TOTP or recovery code; returns the user whose session the
     * caller creates. A wrong code counts as a failed sign-in (lockout) and is 401.
     */
    public User verifyLogin(MfaVerifyRequest request, Portal portal) {
        if (request == null) {
            throw new BadRequestException("mfaToken and code or recoveryCode are required");
        }
        boolean hasCode = request.code() != null && !request.code().isBlank();
        boolean hasRecovery = request.recoveryCode() != null && !request.recoveryCode().isBlank();
        if (hasCode == hasRecovery) {
            throw new BadRequestException("Send either code or recoveryCode");
        }
        Attempt attempt = tx.execute(status -> {
            UserToken token = tokens.lock(request.mfaToken(), UserTokenPurpose.MFA_LOGIN);
            User user = token.getUser();
            requireActive(user);
            Portal.requireAllowed(portal, user.getRole());
            if (isLocked(user)) {
                throw new UnauthorizedException(LOCKED);
            }
            if (!cipher.isConfigured() && hasCode) {
                throw notConfigured();
            }
            boolean ok = checkSecondFactor(user, request.code(), request.recoveryCode());
            if (ok) {
                token.setUsedAt(UtcTime.now());
            }
            return new Attempt(user, ok);
        });
        if (attempt == null || !attempt.ok()) {
            if (attempt != null) {
                recordFailure(attempt.user(), "sign-in");
            }
            throw new UnauthorizedException(INVALID_CODE);
        }
        userAccountService.recordSuccessfulLogin(attempt.user().getId());
        return attempt.user();
    }

    // ---- /api/v1/me/mfa ----

    public MfaStatusResponse status() {
        CurrentUser current = securityUtils.currentUser();
        if (current.role() == Role.CANDIDATE) {
            throw new ForbiddenException("Two-factor authentication is available for staff accounts only");
        }
        UserMfa mfa = mfaRepository.findById(current.userId()).orElse(null);
        boolean enabled = mfa != null && mfa.isEnabled();
        int remaining = enabled ? (int) recoveryCodeRepository.countByUserIdAndUsedAtIsNull(current.userId()) : 0;
        return new MfaStatusResponse(enabled, enabled ? mfa.getEnabledAt() : null, mfa != null && !enabled, remaining,
                organizationRequiresMfa(current.organizationId()), cipher.isConfigured());
    }

    /** POST /api/v1/me/mfa/enroll: a new (unconfirmed) secret; replaces an earlier unconfirmed one. */
    public MfaEnrollResponse enroll(String mfaToken) {
        requireConfigured();
        return tx.execute(status -> {
            User user = resolveActor(mfaToken);
            UserMfa mfa = mfaRepository.lockByUserId(user.getId()).orElse(null);
            if (mfa != null && mfa.isEnabled()) {
                throw new DuplicateResourceException("Two-factor authentication is already enabled");
            }
            byte[] secret = new byte[SECRET_BYTES];
            random.nextBytes(secret);
            String base32 = Base32.encode(secret);
            LocalDateTime now = UtcTime.now();
            if (mfa == null) {
                mfa = new UserMfa();
                mfa.setUserId(user.getId());
                mfa.setCreatedAt(now);
            }
            mfa.setSecretEnc(cipher.encrypt(base32, context(user.getId())));
            mfa.setEnabledAt(null);
            mfa.setLastUsedStep(null);
            mfa.setUpdatedAt(now);
            mfaRepository.save(mfa);
            return new MfaEnrollResponse(base32, otpauthUri(user.getEmail(), base32));
        });
    }

    /**
     * POST /api/v1/me/mfa/confirm: enables MFA with the first code and returns ten recovery codes. With an enrolment
     * {@code mfaToken} the token is consumed and {@link ConfirmResult#signedInUser()} is the user to sign in.
     */
    public ConfirmResult confirm(String code, String mfaToken) {
        requireConfigured();
        boolean viaToken = mfaToken != null && !mfaToken.isBlank();
        ConfirmAttempt attempt = tx.execute(status -> {
            User user;
            UserToken token = null;
            if (viaToken) {
                token = tokens.lock(mfaToken, UserTokenPurpose.MFA_ENROLL);
                user = token.getUser();
                requireActive(user);
                requireStaff(user);
                if (isLocked(user)) {
                    throw new UnauthorizedException(LOCKED);
                }
            } else {
                user = resolveActor(null);
            }
            UserMfa mfa = mfaRepository.lockByUserId(user.getId())
                    .orElseThrow(() -> new BadRequestException("Start the enrolment first (POST /api/v1/me/mfa/enroll)"));
            if (mfa.isEnabled()) {
                throw new DuplicateResourceException("Two-factor authentication is already enabled");
            }
            long step = Totp.verify(secret(mfa), code, currentStep(), Totp.DEFAULT_WINDOW, null, Totp.DEFAULT_DIGITS);
            if (step < 0) {
                return new ConfirmAttempt(user, null);
            }
            if (token != null) {
                token.setUsedAt(UtcTime.now());
            }
            mfa.setEnabledAt(UtcTime.now());
            mfa.setLastUsedStep(step);
            List<String> codes = replaceRecoveryCodes(user.getId());
            auditService.recordFor(user.getOrganizationId(), user.getId(), user.getEmail(), AuditActions.MFA_ENABLED,
                    "USER", String.valueOf(user.getId()), "Two-factor authentication enabled (TOTP)");
            return new ConfirmAttempt(user, codes);
        });
        if (attempt == null || attempt.codes() == null) {
            if (viaToken && attempt != null) {
                recordFailure(attempt.user(), "enrolment");
            }
            throw new BadRequestException(INVALID_CODE + ". Check the time on your device and try again.");
        }
        if (viaToken) {
            userAccountService.recordSuccessfulLogin(attempt.user().getId());
        }
        return new ConfirmResult(attempt.codes(), viaToken ? attempt.user() : null);
    }

    /** POST /api/v1/me/mfa/disable: password plus a TOTP or recovery code; refused while the organisation requires MFA. */
    public void disable(String password, String code) {
        CurrentUser current = securityUtils.currentUser();
        User user = userRepository.findById(current.userId())
                .orElseThrow(() -> new UnauthorizedException("Authentication is required"));
        requireStaff(user);
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BadRequestException("Current password is incorrect");
        }
        if (organizationRequiresMfa(user.getOrganizationId())) {
            throw new BadRequestException("Your organisation requires two-factor authentication, so it cannot be turned off");
        }
        String trimmed = code == null ? "" : code.trim();
        boolean totp = trimmed.length() == Totp.DEFAULT_DIGITS && trimmed.chars().allMatch(Character::isDigit);
        if (totp) {
            requireConfigured();
        }
        Boolean ok = tx.execute(status -> {
            UserMfa mfa = mfaRepository.lockByUserId(user.getId()).orElse(null);
            if (mfa == null) {
                throw new BadRequestException("Two-factor authentication is not enabled");
            }
            if (mfa.isEnabled() && !checkSecondFactor(user, totp ? trimmed : null, totp ? null : trimmed)) {
                return false;
            }
            mfaRepository.delete(mfa);
            recoveryCodeRepository.deleteAllByUser(user.getId());
            auditService.recordFor(user.getOrganizationId(), user.getId(), user.getEmail(), AuditActions.MFA_DISABLED,
                    "USER", String.valueOf(user.getId()), "Two-factor authentication disabled by the user");
            return true;
        });
        if (!Boolean.TRUE.equals(ok)) {
            recordFailure(user, "disable");
            throw new BadRequestException(INVALID_CODE);
        }
    }

    /** POST /api/v1/me/mfa/recovery-codes: ten new codes; the earlier ones stop working. */
    public RecoveryCodesResponse regenerateRecoveryCodes() {
        CurrentUser current = securityUtils.currentUser();
        return tx.execute(status -> {
            User user = userRepository.findById(current.userId())
                    .orElseThrow(() -> new UnauthorizedException("Authentication is required"));
            requireStaff(user);
            UserMfa mfa = mfaRepository.lockByUserId(user.getId()).orElse(null);
            if (mfa == null || !mfa.isEnabled()) {
                throw new BadRequestException("Two-factor authentication is not enabled");
            }
            List<String> codes = replaceRecoveryCodes(user.getId());
            auditService.recordFor(user.getOrganizationId(), user.getId(), user.getEmail(),
                    AuditActions.MFA_RECOVERY_CODES_REGENERATED, "USER", String.valueOf(user.getId()),
                    "New MFA recovery codes generated; earlier codes revoked");
            return new RecoveryCodesResponse(codes);
        });
    }

    // ---- internals ----

    /**
     * Checks a TOTP code (replay guard: the step must be later than the last accepted one) or an unused recovery code
     * (marked used). Locks the user's MFA row; must run inside a transaction.
     */
    boolean checkSecondFactor(User user, String code, String recoveryCode) {
        UserMfa mfa = mfaRepository.lockByUserId(user.getId()).orElse(null);
        if (mfa == null || !mfa.isEnabled()) {
            return false;
        }
        if (code != null && !code.isBlank()) {
            long step = Totp.verify(secret(mfa), code, currentStep(), Totp.DEFAULT_WINDOW, mfa.getLastUsedStep(),
                    Totp.DEFAULT_DIGITS);
            if (step < 0) {
                return false;
            }
            mfa.setLastUsedStep(step);
            mfa.setUpdatedAt(UtcTime.now());
            return true;
        }
        String normalized = normalizeRecoveryCode(recoveryCode);
        if (normalized == null) {
            return false;
        }
        List<UserRecoveryCode> unused = recoveryCodeRepository.findAllByUserIdAndUsedAtIsNullOrderByIdAsc(user.getId());
        for (UserRecoveryCode stored : unused) {
            if (passwordEncoder.matches(normalized, stored.getCodeHash())) {
                stored.setUsedAt(UtcTime.now());
                int remaining = unused.size() - 1;
                auditService.recordFor(user.getOrganizationId(), user.getId(), user.getEmail(),
                        AuditActions.MFA_RECOVERY_CODE_USED, "USER", String.valueOf(user.getId()),
                        "MFA recovery code used; " + Math.max(0, remaining) + " left");
                return true;
            }
        }
        return false;
    }

    /** Lower case, letters and digits only; null unless exactly 10 characters remain. */
    static String normalizeRecoveryCode(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.length() == 10 ? normalized : null;
    }

    private List<String> replaceRecoveryCodes(Long userId) {
        recoveryCodeRepository.deleteAllByUser(userId);
        List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            StringBuilder raw = new StringBuilder(10);
            for (int j = 0; j < 10; j++) {
                raw.append(RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)]);
            }
            String code = raw.toString();
            UserRecoveryCode stored = new UserRecoveryCode();
            stored.setUserId(userId);
            stored.setCodeHash(passwordEncoder.encode(code));
            recoveryCodeRepository.save(stored);
            codes.add(code.substring(0, 5) + "-" + code.substring(5));
        }
        return codes;
    }

    /** The caller: a valid enrolment token's user (not consumed), otherwise the authenticated staff user. */
    private User resolveActor(String mfaToken) {
        User user;
        if (mfaToken != null && !mfaToken.isBlank()) {
            user = tokens.resolve(mfaToken, UserTokenPurpose.MFA_ENROLL, false);
            if (isLocked(user)) {
                throw new UnauthorizedException(LOCKED);
            }
        } else {
            CurrentUser current = securityUtils.findCurrentUser()
                    .orElseThrow(() -> new UnauthorizedException("Authentication is required"));
            user = userRepository.findById(current.userId())
                    .orElseThrow(() -> new UnauthorizedException("Authentication is required"));
        }
        requireActive(user);
        requireStaff(user);
        return user;
    }

    private void recordFailure(User user, String step) {
        userAccountService.recordFailedLogin(user.getId());
        auditService.recordFor(user.getOrganizationId(), user.getId(), user.getEmail(),
                AuditActions.MFA_VERIFICATION_FAILED, "USER", String.valueOf(user.getId()),
                "Wrong second-factor code (" + step + ")");
    }

    private byte[] secret(UserMfa mfa) {
        return Base32.decode(cipher.decrypt(mfa.getSecretEnc(), context(mfa.getUserId())));
    }

    private boolean organizationRequiresMfa(Long organizationId) {
        return organizationId != null && organizationRepository.findById(organizationId)
                .map(Organization::isRequireStaffMfa).orElse(false);
    }

    private void requireConfigured() {
        if (!cipher.isConfigured()) {
            throw notConfigured();
        }
    }

    static ResponseStatusException notConfigured() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                NOT_CONFIGURED_CODE + ": two-factor authentication is not available on this server");
    }

    private static void requireStaff(User user) {
        if (user.getRole() == null || user.getRole() == Role.CANDIDATE) {
            throw new ForbiddenException("Two-factor authentication is available for staff accounts only");
        }
    }

    private static void requireActive(User user) {
        if (user == null || !user.isEnabled() || user.getDeletedAt() != null) {
            throw new UnauthorizedException(MfaTokenService.EXPIRED_MESSAGE);
        }
    }

    private static boolean isLocked(User user) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(UtcTime.now());
    }

    private static long currentStep() {
        return Totp.step(System.currentTimeMillis() / 1000L, Totp.DEFAULT_PERIOD_SECONDS);
    }

    private static String context(Long userId) {
        return "mfa:" + userId;
    }

    String otpauthUri(String email, String base32Secret) {
        String label = encode(issuer) + ":" + encode(email == null ? "" : email);
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + Totp.DEFAULT_DIGITS + "&period=" + Totp.DEFAULT_PERIOD_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Result of {@link #confirm}: the recovery codes and, for a token-based confirmation, the user to sign in. */
    public record ConfirmResult(List<String> recoveryCodes, User signedInUser) {
    }

    private record Attempt(User user, boolean ok) {
    }

    private record ConfirmAttempt(User user, List<String> codes) {
    }
}
