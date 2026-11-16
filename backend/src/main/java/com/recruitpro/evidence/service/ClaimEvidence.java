package com.recruitpro.evidence.service;

import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.CandidateClaimVerification;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.entity.EvidenceTier;

/**
 * A candidate claim as evidence for one organisation's job (D3, D5). Claims belong to the candidate, but a
 * recruiter's verification (corroboration or rejection) only applies within the recruiter's organisation: for every
 * other organisation the claim stays an unverified SELF_CLAIMED claim, and the fact that another organisation
 * checked it is not disclosed. The claim's own verification columns are a candidate-facing summary and are never
 * read here (the legacy overload that read them was removed in the integration pass).
 */
public record ClaimEvidence(Long id, ClaimType claimType, String value, EvidenceTier tier, boolean rejected) {

    /**
     * R2-H02: the claim as evidence for one organisation, from that organisation's own verification row (null: the
     * organisation never checked it). Other organisations' verifications are ignored, so they can neither raise nor
     * remove the claim, and a later verification elsewhere never overwrites this one.
     */
    public static ClaimEvidence forOrganization(CandidateClaim claim, CandidateClaimVerification verification) {
        if (verification == null) {
            return new ClaimEvidence(claim.getId(), claim.getClaimType(), claim.getClaimValue(), EvidenceTier.SELF_CLAIMED, false);
        }
        boolean rejected = verification.isRejected();
        return new ClaimEvidence(claim.getId(), claim.getClaimType(), claim.getClaimValue(),
                rejected ? EvidenceTier.SELF_CLAIMED : verification.getTier(), rejected);
    }
}
