package com.recruitpro.organization.dto;

import com.recruitpro.organization.entity.Organization;

public record OrganizationResponse(Long id, String name, String slug, boolean blindReview, boolean shareVerificationStatus) {
    public static OrganizationResponse from(Organization organization) {
        return new OrganizationResponse(organization.getId(), organization.getName(), organization.getSlug(),
                organization.isBlindReview(), organization.isShareVerificationStatus());
    }
}
