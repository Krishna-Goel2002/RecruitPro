package com.recruitpro.auth.mfa;

import com.recruitpro.auth.dto.AuthResponse;
import com.recruitpro.auth.mfa.dto.MfaVerifyRequest;
import com.recruitpro.auth.service.AuthService;
import com.recruitpro.auth.web.Portal;
import com.recruitpro.auth.web.RefreshTokenCookies;
import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.user.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Second step of the staff sign-in (public route, rate-limited with the other /api/v1/auth routes). */
@RestController
@RequestMapping("/api/v1/auth/mfa")
@Tag(name = "Authentication")
public class MfaAuthController {

    private final MfaService mfaService;
    private final AuthService authService;
    private final RefreshTokenCookies refreshTokenCookies;

    public MfaAuthController(MfaService mfaService, AuthService authService, RefreshTokenCookies refreshTokenCookies) {
        this.mfaService = mfaService;
        this.authService = authService;
        this.refreshTokenCookies = refreshTokenCookies;
    }

    @PostMapping("/verify")
    @Operation(summary = "Complete a sign-in with the mfaToken and a TOTP code or a recovery code; sets the portal's refresh cookie")
    public ApiResponse<AuthResponse> verify(@Valid @RequestBody MfaVerifyRequest request,
                                            HttpServletRequest httpRequest, HttpServletResponse response) {
        Portal portal = Portal.from(httpRequest);
        User user = mfaService.verifyLogin(request, portal);
        return ApiResponse.success(refreshTokenCookies.complete(authService.createSession(user), portal, response));
    }
}
