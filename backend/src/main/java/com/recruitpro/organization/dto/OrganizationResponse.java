package com.recruitpro.organization.dto;

import com.recruitpro.organization.entity.Organization;

import java.util.List;

/**
 * Organisation settings. WP-PLAT added {@code requireStaffMfa} and {@code ssoAllowedDomains} (a list of lower-case
 * domains, empty when SSO is off for the organisation). AS-BE added {@code assistantExternalModel} (the assistant may
 * use the external LLM planner; false = offline planner only).
 */
public record OrganizationResponse(Long id, String name, String slug, boolean blindReview, boolean shareVerificationStatus,
                                   boolean requireStaffMfa, List<String> ssoAllowedDomains,
                                   boolean assistantExternalModel) {

    public OrganizationResponse(Long id, String name, String slug, boolean blindReview, boolean shareVerificationStatus) {
        this(id, name, slug, blindReview, shareVerificationStatus, false, List.of(), false);
    }

    public OrganizationResponse(Long id, String name, String slug, boolean blindReview, boolean shareVerificationStatus,
                                boolean requireStaffMfa, List<String> ssoAllowedDomains) {
        this(id, name, slug, blindReview, shareVerificationStatus, requireStaffMfa, ssoAllowedDomains, false);
    }

    public static OrganizationResponse from(Organization organization) {
        return new OrganizationResponse(organization.getId(), organization.getName(), organization.getSlug(),
                organization.isBlindReview(), organization.isShareVerificationStatus(), organization.isRequireStaffMfa(),
                SsoDomains.parse(organization.getSsoAllowedDomains()), organization.isAssistantExternalModel());
    }
}
