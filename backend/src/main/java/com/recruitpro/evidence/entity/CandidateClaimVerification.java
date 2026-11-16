package com.recruitpro.evidence.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * One organisation's verification of a candidate claim (R2-H02, D3): CORROBORATED (a recruiter of the organisation
 * confirmed it) or SELF_CLAIMED (rejected: no longer used for that organisation's matching). One row per claim and
 * organisation, so a second organisation's verification never overwrites the first. Deleted with the claim.
 */
@Entity
@Table(name = "candidate_claim_verifications")
@Getter
@Setter
@NoArgsConstructor
public class CandidateClaimVerification extends BaseEntity {

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private EvidenceTier tier = EvidenceTier.SELF_CLAIMED;

    @Column(name = "verified_by_user_id")
    private Long verifiedByUserId;

    @Column(name = "verified_at", nullable = false)
    private LocalDateTime verifiedAt;

    @Column(name = "verification_note", length = 500)
    private String verificationNote;

    /** The organisation checked the claim and could not confirm it. */
    public boolean isRejected() {
        return tier == null || !tier.isVerified();
    }

    /** VERIFIED or REJECTED. */
    public String verificationStatus() {
        return isRejected() ? "REJECTED" : "VERIFIED";
    }
}
