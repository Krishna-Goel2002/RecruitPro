package com.recruitpro.auth.controller;

import com.recruitpro.auth.dto.AuthResponse;
import com.recruitpro.auth.dto.ForgotPasswordRequest;
import com.recruitpro.auth.dto.LoginRequest;
import com.recruitpro.auth.dto.RefreshTokenRequest;
import com.recruitpro.auth.dto.RegisterRequest;
import com.recruitpro.auth.dto.ResetPasswordRequest;
import com.recruitpro.auth.dto.UserResponse;
import com.recruitpro.auth.dto.VerifyEmailRequest;
import com.recruitpro.auth.service.AuthService;
import com.recruitpro.auth.service.AuthSession;
import com.recruitpro.auth.web.Portal;
import com.recruitpro.auth.web.RefreshTokenCookies;
import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.common.exception.UnauthorizedException;
import com.recruitpro.user.entity.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in and session endpoints. Every session endpoint honours the {@link Portal#HEADER} header: the candidate and
 * HR portals each get their own refresh cookie ({@code rp_refresh_candidate} / {@code rp_refresh_staff}; plain
 * {@code rp_refresh} without the header), and an account whose role does not belong to the portal is refused with
 * 403 before a session is created.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Registration, sign-in, refresh-token cookie rotation, email verification and password reset")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookies refreshTokenCookies;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new candidate user (sets the portal's refresh cookie, sends a verification email)")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                              HttpServletRequest httpRequest, HttpServletResponse response) {
        Portal portal = Portal.from(httpRequest);
        // Public registration always creates a candidate; the staff portal never registers accounts.
        Portal.requireAllowed(portal, Role.CANDIDATE);
        return ApiResponse.success(refreshTokenCookies.complete(authService.register(request), portal, response));
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate a user; returns a 15-minute access token and sets the portal's refresh cookie (403 for an account of the other portal), or {mfaRequired, mfaEnrollmentRequired, mfaToken} for staff with MFA")
    public ApiResponse<Object> login(@Valid @RequestBody LoginRequest request,
                                     HttpServletRequest httpRequest, HttpServletResponse response) {
        Portal portal = Portal.from(httpRequest);
        AuthSession session = authService.login(request, portal);
        if (session.requiresMfa()) {
            // WP-PLAT: {mfaRequired, mfaEnrollmentRequired, mfaToken, expiresInSeconds}; no cookie, no access token.
            return ApiResponse.success(session.mfaChallenge(), "Second sign-in step required");
        }
        return ApiResponse.success(refreshTokenCookies.complete(session, portal, response));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate the portal's refresh-token cookie and issue a new access token")
    public ApiResponse<AuthResponse> refresh(@RequestBody(required = false) RefreshTokenRequest body,
                                             HttpServletRequest request, HttpServletResponse response) {
        Portal portal = Portal.from(request);
        String rawToken = refreshTokenCookies.resolve(request, portal, body);
        try {
            return ApiResponse.success(refreshTokenCookies.complete(authService.refresh(rawToken, portal), portal, response));
        } catch (UnauthorizedException | ForbiddenException ex) {
            refreshTokenCookies.clear(portal, response);
            throw ex;
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the portal session's refresh-token family and clear the portal's cookie")
    public ApiResponse<Void> logout(@RequestBody(required = false) RefreshTokenRequest body,
                                    HttpServletRequest request, HttpServletResponse response) {
        Portal portal = Portal.from(request);
        authService.logout(refreshTokenCookies.resolve(request, portal, body));
        refreshTokenCookies.clear(portal, response);
        return ApiResponse.success("Logged out successfully");
    }

    @PostMapping("/verify-email")
    @Operation(summary = "Confirm an email address with the emailed token")
    public ApiResponse<UserResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return ApiResponse.success(authService.verifyEmail(request.token()), "Email address verified");
    }

    @PostMapping("/resend-verification")
    @Operation(summary = "Send a new verification email to the authenticated user")
    public ApiResponse<Void> resendVerification() {
        authService.resendVerification();
        return ApiResponse.success("If your email address is not verified yet, a new link has been sent");
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Email a password reset link (always succeeds)")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.email());
        return ApiResponse.success("If an account exists for this address, a reset link has been sent");
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Set a new password with an emailed reset token; signs out every session")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.token(), request.newPassword());
        return ApiResponse.success("Password updated. Please sign in with your new password");
    }
}
