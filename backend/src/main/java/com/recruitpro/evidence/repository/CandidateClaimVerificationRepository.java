package com.recruitpro.evidence.repository;

import com.recruitpro.evidence.entity.CandidateClaimVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Per-organisation claim verifications (R2-H02). */
public interface CandidateClaimVerificationRepository extends JpaRepository<CandidateClaimVerification, Long> {

    Optional<CandidateClaimVerification> findByClaimIdAndOrganizationId(Long claimId, Long organizationId);

    /** Every organisation's row of one claim (the candidate-facing summary, {@code CandidateClaim#summarize}). */
    List<CandidateClaimVerification> findAllByClaimId(Long claimId);

    List<CandidateClaimVerification> findAllByClaimIdInAndOrganizationId(Collection<Long> claimIds, Long organizationId);

    /** Every organisation's verification of a claim is cleared when the candidate changes the claim's value. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from CandidateClaimVerification v where v.claimId = :claimId")
    int deleteAllByClaimId(@Param("claimId") Long claimId);
}
