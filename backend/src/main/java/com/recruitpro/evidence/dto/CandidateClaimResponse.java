package com.recruitpro.evidence.dto;

import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.entity.EvidenceTier;

import java.time.LocalDateTime;

/**
 * CandidateClaim (plan 5.4) plus {@code hasEvidenceUrl}, {@code sourceFactId}, {@code verificationStatus}
 * (UNVERIFIED, VERIFIED, REJECTED) and {@code updatedAt}.
 *
 * <p>Candidate view: the recruiter's note is not shown. Staff view: the evidence URL is hidden until the
 * application's identity is revealed (it may identify the candidate, D4); a verification made by another
 * organisation is not disclosed (D3): the claim then shows as SELF_CLAIMED and UNVERIFIED.</p>
 */
public record CandidateClaimResponse(Long id, ClaimType claimType, String value, String evidenceUrl, boolean hasEvidenceUrl,
                                     Long sourceFactId, EvidenceTier tier, String verificationStatus,
                                     LocalDateTime verifiedAt, String verificationNote, LocalDateTime createdAt,
                                     LocalDateTime updatedAt) {

    public static CandidateClaimResponse forCandidate(CandidateClaim claim) {
        return new CandidateClaimResponse(claim.getId(), claim.getClaimType(), claim.getClaimValue(), claim.getEvidenceUrl(),
                hasUrl(claim), claim.getSourceFactId(), claim.getTier(), claim.verificationStatus(), claim.getVerifiedAt(),
                null, claim.getCreatedAt(), claim.getUpdatedAt());
    }

    /**
     * @param verifiedByViewerOrganization true when the claim is unverified or was verified by the viewer's own
     *                                     organisation
     */
    public static CandidateClaimResponse forStaff(CandidateClaim claim, boolean identityRevealed,
                                                  boolean verifiedByViewerOrganization) {
        boolean foreign = claim.getVerifiedAt() != null && !verifiedByViewerOrganization;
        return new CandidateClaimResponse(claim.getId(), claim.getClaimType(), claim.getClaimValue(),
                identityRevealed ? claim.getEvidenceUrl() : null, hasUrl(claim), claim.getSourceFactId(),
                foreign ? EvidenceTier.SELF_CLAIMED : claim.getTier(),
                foreign ? "UNVERIFIED" : claim.verificationStatus(),
                foreign ? null : claim.getVerifiedAt(),
                foreign ? null : claim.getVerificationNote(),
                claim.getCreatedAt(), claim.getUpdatedAt());
    }

    private static boolean hasUrl(CandidateClaim claim) {
        return claim.getEvidenceUrl() != null && !claim.getEvidenceUrl().isBlank();
    }
}
