package com.recruitpro.organization.controller;

import com.recruitpro.auth.service.AuthSession;
import com.recruitpro.auth.web.Portal;
import com.recruitpro.auth.web.RefreshTokenCookies;
import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.organization.dto.AcceptInvitationRequest;
import com.recruitpro.organization.dto.InvitationPreviewResponse;
import com.recruitpro.organization.service.InvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public invitation routes (no access token required). */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Invitations", description = "Preview and accept staff invitations")
public class InvitationAuthController {

    private final InvitationService invitationService;
    private final RefreshTokenCookies refreshTokenCookies;

    @GetMapping("/invitations/{token}")
    @Operation(summary = "Preview a pending invitation")
    public ApiResponse<InvitationPreviewResponse> preview(@PathVariable String token) {
        return ApiResponse.success(invitationService.preview(token));
    }

    @PostMapping("/accept-invite")
    @Operation(summary = "Accept an invitation: creates the staff account and signs it in (sets the portal's refresh cookie; 403 on the candidate portal), or returns {mfaRequired, mfaEnrollmentRequired, mfaToken} when the organisation requires staff MFA")
    public ApiResponse<Object> accept(@Valid @RequestBody AcceptInvitationRequest request,
                                      HttpServletRequest httpRequest, HttpServletResponse response) {
        Portal portal = Portal.from(httpRequest);
        // Invitations always create staff accounts; refuse them on the candidate portal before anything is written.
        if (portal == Portal.CANDIDATE) {
            throw new ForbiddenException("Invitations are accepted in the RecruitPro HR portal, not the candidate portal.");
        }
        AuthSession session = invitationService.accept(request);
        if (session.requiresMfa()) {
            // Same shape as /auth/login: no access token and no cookie until the second factor is enrolled.
            return ApiResponse.success(session.mfaChallenge(), "Invitation accepted; two-factor enrolment required");
        }
        return ApiResponse.success(refreshTokenCookies.complete(session, portal, response), "Invitation accepted");
    }
}
