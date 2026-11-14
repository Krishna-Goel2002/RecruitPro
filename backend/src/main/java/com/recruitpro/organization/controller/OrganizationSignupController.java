package com.recruitpro.organization.controller;

import com.recruitpro.auth.dto.AuthResponse;
import com.recruitpro.auth.web.Portal;
import com.recruitpro.auth.web.RefreshTokenCookies;
import com.recruitpro.user.entity.Role;
import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.organization.dto.OrganizationSignupRequest;
import com.recruitpro.organization.service.OrganizationService;
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

@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
@Tag(name = "Organisations", description = "Self-service organisation sign-up")
public class OrganizationSignupController {

    private final OrganizationService organizationService;
    private final RefreshTokenCookies refreshTokenCookies;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an organisation and its first administrator (sets the portal's refresh cookie; 403 on the candidate portal)")
    public ApiResponse<AuthResponse> signup(@Valid @RequestBody OrganizationSignupRequest request,
                                            HttpServletRequest httpRequest, HttpServletResponse response) {
        Portal portal = Portal.from(httpRequest);
        // Sign-up creates an organisation ADMIN; refuse it on the candidate portal before anything is written.
        Portal.requireAllowed(portal, Role.ADMIN);
        return ApiResponse.success(refreshTokenCookies.complete(organizationService.signup(request), portal, response),
                "Organisation created");
    }
}
