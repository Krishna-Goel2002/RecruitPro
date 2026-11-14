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

/**
 * Something the candidate states about themselves (D5, C1). New claims and corrections of resume facts are
 * SELF_CLAIMED (tier 0) and never raise verified coverage; a recruiter may corroborate one (tier 2) or reject
 * it (stays tier 0 with a verification time, and is no longer used for matching).
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

    /** A recruiter checked the claim and could not confirm it; it is excluded from matching. */
    public boolean isRejected() {
        return verifiedAt != null && tier == EvidenceTier.SELF_CLAIMED;
    }

    /** UNVERIFIED, VERIFIED or REJECTED (derived). */
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
