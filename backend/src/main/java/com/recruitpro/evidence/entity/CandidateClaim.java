package com.recruitpro.evidence.entity;

import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * Something the candidate states about themselves (D5, C1). New claims and corrections of resume facts are
 * SELF_CLAIMED (tier 0) and never raise verified coverage.
 *
 * <p>Recruiter verification is per organisation (R2-H02): the source of truth is
 * {@link CandidateClaimVerification} (table {@code candidate_claim_verifications}), read by matching, the staff
 * views and the verification module. The columns {@code tier}, {@code verified_by_user_id}, {@code verified_at} and
 * {@code verification_note} below are <b>deprecated as a decision input</b>: they hold only a derived, candidate-facing
 * summary of all organisations' rows ({@link #summarize}), kept in sync on every verification write and cleared when
 * the claim changes. Nothing that decides for an organisation may read them (integration pass, 16 Nov 2026).</p>
 */
@Entity
@Table(name = "candidate_claims")
@Getter
@Setter
@NoArgsConstructor
public class CandidateClaim extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private CandidateProfile candidate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "claim_type", nullable = false, length = 30)
    private ClaimType claimType;

    @Column(name = "claim_value", nullable = false, length = 1000)
    private String claimValue;

    @Column(name = "evidence_url", length = 500)
    private String evidenceUrl;

    /** Resume fact this claim corrects (fact review), or null for a free-standing claim. */
    @Column(name = "source_fact_id")
    private Long sourceFactId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private EvidenceTier tier = EvidenceTier.SELF_CLAIMED;

    @Column(name = "verified_by_user_id")
    private Long verifiedByUserId;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verification_note", length = 500)
    private String verificationNote;

    /**
     * Candidate-facing summary of the per-organisation verifications (never used for an organisation's decisions):
     * CORROBORATED when at least one organisation corroborated the claim (the most recent corroboration), otherwise
     * a rejection (SELF_CLAIMED with the most recent rejection's time) when one organisation checked it, otherwise
     * unverified. The result therefore does not depend on which organisation checked last.
     */
    public void summarize(Collection<CandidateClaimVerification> verifications) {
        CandidateClaimVerification latestCorroboration = null;
        CandidateClaimVerification latestRejection = null;
        for (CandidateClaimVerification row : verifications == null ? java.util.List.<CandidateClaimVerification>of() : verifications) {
            if (row == null || row.getVerifiedAt() == null) {
                continue;
            }
            if (row.isRejected()) {
                latestRejection = later(latestRejection, row);
            } else {
                latestCorroboration = later(latestCorroboration, row);
            }
        }
        CandidateClaimVerification shown = latestCorroboration != null ? latestCorroboration : latestRejection;
        if (shown == null) {
            resetVerification();
            return;
        }
        tier = shown.isRejected() ? EvidenceTier.SELF_CLAIMED : shown.getTier();
        verifiedByUserId = shown.getVerifiedByUserId();
        verifiedAt = shown.getVerifiedAt();
        verificationNote = shown.getVerificationNote();
    }

    private static CandidateClaimVerification later(CandidateClaimVerification current, CandidateClaimVerification row) {
        return current == null || row.getVerifiedAt().isAfter(current.getVerifiedAt()) ? row : current;
    }

    /** UNVERIFIED, VERIFIED or REJECTED: the candidate-facing summary only (see {@link #summarize}). */
    public String verificationStatus() {
        if (verifiedAt == null) {
            return "UNVERIFIED";
        }
        return tier.isVerified() ? "VERIFIED" : "REJECTED";
    }

    /** Clears any recruiter verification (used when the candidate changes the claim). */
    public void resetVerification() {
        tier = EvidenceTier.SELF_CLAIMED;
        verifiedByUserId = null;
        verifiedAt = null;
        verificationNote = null;
    }
}
