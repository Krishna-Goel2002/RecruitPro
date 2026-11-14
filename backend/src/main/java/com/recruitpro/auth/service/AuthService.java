package com.recruitpro.auth.service;

import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.auth.dto.AuthResponse;
import com.recruitpro.auth.dto.LoginRequest;
import com.recruitpro.auth.dto.RegisterRequest;
import com.recruitpro.auth.dto.UserResponse;
import com.recruitpro.auth.entity.UserTokenPurpose;
import com.recruitpro.auth.validation.PasswordPolicy;
import com.recruitpro.auth.web.Portal;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.UnauthorizedException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.service.NotificationRecipient;
import com.recruitpro.notification.service.NotificationService;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.security.jwt.JwtService;
import com.recruitpro.security.userdetails.CustomUserDetails;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import com.recruitpro.user.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;

/**
 * Accounts and sessions: registration, sign-in with lockout, refresh-token rotation, logout, email
 * verification, password reset and change (D11, S1, S2, M6).
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserAccountService userAccountService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final RefreshTokenService refreshTokenService;
    private final UserTokenService userTokenService;
    private final NotificationService notificationService;
    private final OrganizationRepository organizationRepository;
    private final AuditService auditService;
    private final SecurityUtils securityUtils;

    @Value("${app.frontend.candidate-url:http://localhost:5173}")
    private String candidatePortalUrl;

    @Value("${app.frontend.hr-url:http://localhost:5174}")
    private String hrPortalUrl;

    @Value("${app.auth.email-verification-ttl-hours:24}")
    private long emailVerificationTtlHours;

    @Value("${app.auth.password-reset-ttl-minutes:60}")
    private long passwordResetTtlMinutes;

    /** Public registration always creates a CANDIDATE and sends a verification email. */
    @Transactional
    public AuthSession register(RegisterRequest request) {
        User user = userAccountService.createCandidateUser(request.getEmail(), request.getFirstName(),
                request.getLastName(), request.getPassword(), false);
        sendVerificationEmail(user);
        return createSession(user);
    }

    /**
     * Sign-in. Five consecutive failures lock the account for 15 minutes; a locked account receives the
     * same 401 as a wrong password.
     */
    public AuthSession login(LoginRequest request) {
        return login(request, null);
    }

    /**
     * Sign-in from a portal. An account whose role does not belong to the portal (a candidate on the staff
     * portal, staff on the candidate portal) gets 403 after the password check and before any session is
     * created; failure counters are left unchanged.
     */
    public AuthSession login(LoginRequest request, Portal portal) {
        String email = UserAccountService.normalizeEmail(request.getEmail());
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.getPassword()));
        } catch (LockedException ex) {
            throw new BadCredentialsException("Invalid credentials");
        } catch (BadCredentialsException ex) {
            userRepository.findByEmail(email).ifPresent(user -> userAccountService.recordFailedLogin(user.getId()));
            throw ex;
        }
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        User user = userDetails.getUser();
        Portal.requireAllowed(portal, user.getRole());
        userAccountService.recordSuccessfulLogin(user.getId());
        return createSession(user);
    }

    /** Rotates the refresh token (see {@link RefreshTokenService#rotate}). */
    public AuthSession refresh(String rawRefreshToken) {
        return refresh(rawRefreshToken, null);
    }

    /** Rotates the refresh token of a portal; a token of an account the portal does not allow is refused (403) unrotated. */
    public AuthSession refresh(String rawRefreshToken, Portal portal) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(rawRefreshToken, portal);
        return new AuthSession(buildResponse(rotation.user()), rotation.refreshToken());
    }

    /** Revokes the whole session family of the presented token. */
    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeFamilyOf(rawRefreshToken);
    }

    @Transactional
    public UserResponse verifyEmail(String rawToken) {
        User user = userTokenService.consume(rawToken, UserTokenPurpose.VERIFY_EMAIL);
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(UtcTime.now());
            userRepository.save(user);
        }
        return toUserResponse(user);
    }

    @Transactional
    public void resendVerification() {
        CurrentUser current = securityUtils.currentUser();
        User user = userRepository.findById(current.userId())
                .orElseThrow(() -> new UnauthorizedException("Authentication is required"));
        if (user.getEmailVerifiedAt() != null) {
            return;
        }
        sendVerificationEmail(user);
    }

    /** Always succeeds; a reset link is emailed only when an active account exists. */
    @Transactional
    public void forgotPassword(String email) {
        String normalized = UserAccountService.normalizeEmail(email);
        if (normalized == null || normalized.isBlank()) {
            return;
        }
        userRepository.findByEmail(normalized)
                .filter(user -> user.isEnabled() && user.getDeletedAt() == null)
                .ifPresent(user -> {
                    String token = userTokenService.issue(user, UserTokenPurpose.RESET_PASSWORD,
                            Duration.ofMinutes(passwordResetTtlMinutes));
                    String link = portalFor(user) + "/reset-password?token=" + token;
                    notificationService.notify(NotificationRecipient.of(user), NotificationTemplate.PASSWORD_RESET,
                            Map.of("firstName", user.getFirstName(), "link", link,
                                    "expiresInMinutes", String.valueOf(passwordResetTtlMinutes)));
                });
    }

    /** Sets a new password from a reset link and revokes every session of the user. */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordPolicy.requireValid(newPassword);
        User user = userTokenService.consume(rawToken, UserTokenPurpose.RESET_PASSWORD);
        userAccountService.applyNewPassword(user, newPassword);
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(UtcTime.now());
        }
        refreshTokenService.revokeAllForUser(user.getId(), RefreshTokenService.REASON_PASSWORD_CHANGED);
        auditService.recordFor(user.getOrganizationId(), user.getId(), user.getEmail(), AuditActions.PASSWORD_RESET,
                "USER", String.valueOf(user.getId()), "Password reset through an emailed link; all sessions revoked");
    }

    /**
     * Changes the current user's password, revokes every other session (refresh tokens and earlier access
     * tokens) and starts a new session for the caller.
     */
    @Transactional
    public AuthSession changePassword(String currentPassword, String newPassword) {
        CurrentUser current = securityUtils.currentUser();
        User user = userRepository.findById(current.userId())
                .orElseThrow(() -> new UnauthorizedException("Authentication is required"));
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadRequestException("Current password is incorrect");
        }
        if (newPassword != null && passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BadRequestException("The new password must be different from the current password");
        }
        userAccountService.applyNewPassword(user, newPassword);
        refreshTokenService.revokeAllForUser(user.getId(), RefreshTokenService.REASON_PASSWORD_CHANGED);
        auditService.recordFor(user.getOrganizationId(), user.getId(), user.getEmail(), AuditActions.PASSWORD_CHANGED,
                "USER", String.valueOf(user.getId()), "Password changed; other sessions revoked");
        return createSession(user);
    }

    @Transactional(readOnly = true)
    public UserResponse me() {
        CurrentUser current = securityUtils.currentUser();
        User user = userRepository.findById(current.userId())
                .orElseThrow(() -> new UnauthorizedException("Authentication is required"));
        return toUserResponse(user);
    }

    /** New sign-in session: access token plus the first refresh token of a new family. */
    public AuthSession createSession(User user) {
        String refreshToken = refreshTokenService.issueNewFamily(user);
        return new AuthSession(buildResponse(user), refreshToken);
    }

    /** Emails a 24-hour verification link (candidate portal for candidates, HR portal for staff). */
    public void sendVerificationEmail(User user) {
        String token = userTokenService.issue(user, UserTokenPurpose.VERIFY_EMAIL, Duration.ofHours(emailVerificationTtlHours));
        String link = portalFor(user) + "/verify-email?token=" + token;
        notificationService.notify(NotificationRecipient.of(user), NotificationTemplate.VERIFY_EMAIL,
                Map.of("firstName", user.getFirstName(), "link", link));
    }

    public UserResponse toUserResponse(User user) {
        String organizationName = user.getOrganizationId() == null ? null
                : organizationRepository.findById(user.getOrganizationId()).map(Organization::getName).orElse(null);
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRole().name())
                .organizationId(user.getOrganizationId())
                .organizationName(organizationName)
                .emailVerified(user.getEmailVerifiedAt() != null)
                .build();
    }

    private AuthResponse buildResponse(User user) {
        return AuthResponse.builder()
                .accessToken(jwtService.generateAccessToken(user))
                .expiresInSeconds(jwtService.getAccessTokenTtlSeconds())
                .user(toUserResponse(user))
                .build();
    }

    private String portalFor(User user) {
        String base = user.getRole() == Role.CANDIDATE ? candidatePortalUrl : hrPortalUrl;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
