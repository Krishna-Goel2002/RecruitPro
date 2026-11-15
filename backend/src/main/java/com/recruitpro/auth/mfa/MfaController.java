package com.recruitpro.auth.mfa;

import com.recruitpro.auth.dto.AuthResponse;
import com.recruitpro.auth.mfa.dto.MfaConfirmRequest;
import com.recruitpro.auth.mfa.dto.MfaConfirmResponse;
import com.recruitpro.auth.mfa.dto.MfaDisableRequest;
import com.recruitpro.auth.mfa.dto.MfaEnrollRequest;
import com.recruitpro.auth.mfa.dto.MfaEnrollResponse;
import com.recruitpro.auth.mfa.dto.MfaStatusResponse;
import com.recruitpro.auth.mfa.dto.RecoveryCodesResponse;
import com.recruitpro.auth.service.AuthService;
import com.recruitpro.auth.web.Portal;
import com.recruitpro.auth.web.RefreshTokenCookies;
import com.recruitpro.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's TOTP MFA (staff only; candidates get 403). {@code enroll} and {@code confirm} are public routes in
 * SecurityConfig so that a user whose organisation requires MFA can enrol with the login's {@code mfaToken} before
 * holding an access token; every other route needs the access token.
 */
@RestController
@RequestMapping("/api/v1/me/mfa")
@Tag(name = "Account", description = "Two-factor authentication (TOTP) of the authenticated staff user")
public class MfaController {

    private final MfaService mfaService;
    private final AuthService authService;
    private final RefreshTokenCookies refreshTokenCookies;

    public MfaController(MfaService mfaService, AuthService authService, RefreshTokenCookies refreshTokenCookies) {
        this.mfaService = mfaService;
        this.authService = authService;
        this.refreshTokenCookies = refreshTokenCookies;
    }

    @GetMapping
    @Operation(summary = "MFA status of the current staff user")
    public ApiResponse<MfaStatusResponse> status() {
        return ApiResponse.success(mfaService.status());
    }

    @PostMapping("/enroll")
    @Operation(summary = "Start enrolment: new TOTP secret and otpauth URI (access token, or the login's mfaToken when enrolment is required)")
    public ApiResponse<MfaEnrollResponse> enroll(@Valid @RequestBody(required = false) MfaEnrollRequest request) {
        return ApiResponse.success(mfaService.enroll(request == null ? null : request.mfaToken()),
                "Scan the QR code, then confirm with a code from your app");
    }

    @PostMapping("/confirm")
    @Operation(summary = "Confirm enrolment with the first code; returns 10 recovery codes (and a session when an mfaToken was used)")
    public ApiResponse<MfaConfirmResponse> confirm(@Valid @RequestBody MfaConfirmRequest request,
                                                   HttpServletRequest httpRequest, HttpServletResponse response) {
        MfaService.ConfirmResult result = mfaService.confirm(request.code(), request.mfaToken());
        AuthResponse session = null;
        if (result.signedInUser() != null) {
            Portal portal = Portal.from(httpRequest);
            Portal.requireAllowed(portal, result.signedInUser().getRole());
            session = refreshTokenCookies.complete(authService.createSession(result.signedInUser()), portal, response);
        }
        return ApiResponse.success(new MfaConfirmResponse(result.recoveryCodes(), session),
                "Two-factor authentication is on. Store your recovery codes somewhere safe");
    }

    @PostMapping("/disable")
    @Operation(summary = "Turn MFA off (password plus a current code or a recovery code)")
    public ApiResponse<Void> disable(@Valid @RequestBody MfaDisableRequest request) {
        mfaService.disable(request.password(), request.code());
        return ApiResponse.success("Two-factor authentication is off");
    }

    @PostMapping("/recovery-codes")
    @Operation(summary = "Generate 10 new recovery codes (earlier codes stop working)")
    public ApiResponse<RecoveryCodesResponse> regenerateRecoveryCodes() {
        return ApiResponse.success(mfaService.regenerateRecoveryCodes(), "New recovery codes generated");
    }
}
