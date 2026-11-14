package com.recruitpro.organization.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.organization.dto.InvitationCreateRequest;
import com.recruitpro.organization.dto.InvitationResponse;
import com.recruitpro.organization.dto.OrganizationResponse;
import com.recruitpro.organization.dto.OrganizationUpdateRequest;
import com.recruitpro.organization.service.InvitationService;
import com.recruitpro.organization.service.OrganizationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Organisation settings and invitations, for the organisation's administrators. */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Administration: organisation", description = "Organisation settings and staff invitations")
public class AdminOrganizationController {

    private final OrganizationService organizationService;
    private final InvitationService invitationService;

    @GetMapping("/organization")
    public ApiResponse<OrganizationResponse> getOrganization() {
        return ApiResponse.success(organizationService.getCurrent());
    }

    @PatchMapping("/organization")
    public ApiResponse<OrganizationResponse> updateOrganization(@Valid @RequestBody OrganizationUpdateRequest request) {
        return ApiResponse.success(organizationService.updateCurrent(request), "Organisation updated");
    }

    @GetMapping("/invitations")
    public ApiResponse<List<InvitationResponse>> listInvitations() {
        return ApiResponse.success(invitationService.listForCurrentOrganization());
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InvitationResponse> createInvitation(@Valid @RequestBody InvitationCreateRequest request) {
        return ApiResponse.success(invitationService.create(request), "Invitation sent");
    }

    @DeleteMapping("/invitations/{invitationId}")
    public ApiResponse<Void> revokeInvitation(@PathVariable Long invitationId) {
        invitationService.revoke(invitationId);
        return ApiResponse.success("Invitation revoked");
    }
}
