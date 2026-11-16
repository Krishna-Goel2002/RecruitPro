package com.recruitpro.evidence.dto;

import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.CandidateClaimVerification;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.entity.EvidenceTier;

import java.time.LocalDateTime;

/**
 * CandidateClaim (plan 5.4) plus {@code hasEvidenceUrl}, {@code sourceFactId}, {@code verificationStatus}
 * (UNVERIFIED, VERIFIED, REJECTED) and {@code updatedAt}.
 *
 * <p>Candidate view: the summary of every organisation's verification ({@code CandidateClaim#summarize}); the
 * recruiter's note is not shown. Staff view: the evidence URL is hidden until the
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
     * R2-H02: staff view from the viewer organisation's own verification row (null: not checked by that organisation).
     * Verifications by other organisations are never disclosed.
     */
    public static CandidateClaimResponse forStaff(CandidateClaim claim, boolean identityRevealed,
                                                  CandidateClaimVerification organizationVerification) {
        CandidateClaimVerification own = organizationVerification;
        return new CandidateClaimResponse(claim.getId(), claim.getClaimType(), claim.getClaimValue(),
                identityRevealed ? claim.getEvidenceUrl() : null, hasUrl(claim), claim.getSourceFactId(),
                own == null || own.isRejected() ? EvidenceTier.SELF_CLAIMED : own.getTier(),
                own == null ? "UNVERIFIED" : own.verificationStatus(),
                own == null ? null : own.getVerifiedAt(),
                own == null ? null : own.getVerificationNote(),
                claim.getCreatedAt(), claim.getUpdatedAt());
    }

    private static boolean hasUrl(CandidateClaim claim) {
        return claim.getEvidenceUrl() != null && !claim.getEvidenceUrl().isBlank();
    }
}
