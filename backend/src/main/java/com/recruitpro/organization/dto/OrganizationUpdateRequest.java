package com.recruitpro.organization.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * PATCH semantics: null fields are left unchanged. {@code ssoAllowedDomains}: the complete new list (an empty list
 * turns SSO off for the organisation). {@code requireStaffMfa=true} needs the server's encryption key (503
 * MFA_NOT_CONFIGURED otherwise).
 */
public record OrganizationUpdateRequest(@Size(min = 1, max = 200) String name, Boolean blindReview,
                                        Boolean shareVerificationStatus, Boolean requireStaffMfa,
                                        @Size(max = 20) List<String> ssoAllowedDomains) {

    public OrganizationUpdateRequest(String name, Boolean blindReview, Boolean shareVerificationStatus) {
        this(name, blindReview, shareVerificationStatus, null, null);
    }
}
