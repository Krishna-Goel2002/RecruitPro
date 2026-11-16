package com.recruitpro.auth.controller;

import com.recruitpro.auth.dto.AuthResponse;
import com.recruitpro.auth.dto.ChangePasswordRequest;
import com.recruitpro.auth.dto.UserResponse;
import com.recruitpro.auth.service.AuthService;
import com.recruitpro.auth.web.Portal;
import com.recruitpro.auth.web.RefreshTokenCookies;
import com.recruitpro.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The authenticated user's own account (any role). */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@Tag(name = "Account", description = "The authenticated user's account")
public class MeController {

    private final AuthService authService;
    private final RefreshTokenCookies refreshTokenCookies;

    @GetMapping
    @Operation(summary = "Current user (AuthUser)")
    public ApiResponse<UserResponse> me() {
        return ApiResponse.success(authService.me());
    }

    /**
     * Changes the password. Every other session is signed out, including access tokens issued before the
     * change; this session receives a new refresh cookie and, in the body, a new access token.
     */
    @PostMapping("/password")
    @Operation(summary = "Change the password; other sessions are signed out, this one receives a new access token and refresh cookie")
    public ApiResponse<AuthResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                    HttpServletRequest httpRequest, HttpServletResponse response) {
        // The new session's refresh token goes into the cookie of the portal the request came from. The portal header
        // is validated first (R5#1): an invalid one is a 400 before the password changes and every session is revoked.
        Portal portal = Portal.from(httpRequest);
        return ApiResponse.success(refreshTokenCookies.complete(
                authService.changePassword(request.currentPassword(), request.newPassword()), portal, response),
                "Password changed");
    }
}
