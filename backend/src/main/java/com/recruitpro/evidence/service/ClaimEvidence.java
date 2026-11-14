package com.recruitpro.evidence.service;

import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.entity.EvidenceTier;

/**
 * A candidate claim as evidence for one organisation's job (D3, D5). Claims belong to the candidate, but a
 * recruiter's verification (corroboration or rejection) only applies within the recruiter's organisation: for every
 * other organisation the claim stays an unverified SELF_CLAIMED claim, and the fact that another organisation
 * checked it is not disclosed.
 */
public record ClaimEvidence(Long id, ClaimType claimType, String value, EvidenceTier tier, boolean rejected) {

    /**
     * @param verifierOrganizationId organisation of the user who verified the claim (null when unverified)
     * @param organizationId         organisation whose job the claim is evaluated for
     */
    public static ClaimEvidence forOrganization(CandidateClaim claim, Long verifierOrganizationId, Long organizationId) {
        if (claim.getVerifiedAt() != null && !verifiedBy(verifierOrganizationId, organizationId)) {
            return new ClaimEvidence(claim.getId(), claim.getClaimType(), claim.getClaimValue(), EvidenceTier.SELF_CLAIMED, false);
        }
        EvidenceTier tier = claim.getTier() == null ? EvidenceTier.SELF_CLAIMED : claim.getTier();
        return new ClaimEvidence(claim.getId(), claim.getClaimType(), claim.getClaimValue(), tier, claim.isRejected());
    }

    /** True when the verification was made by the given organisation. */
    public static boolean verifiedBy(Long verifierOrganizationId, Long organizationId) {
        return verifierOrganizationId != null && verifierOrganizationId.equals(organizationId);
    }
}
