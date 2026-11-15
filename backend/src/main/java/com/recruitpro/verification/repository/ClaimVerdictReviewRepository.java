package com.recruitpro.verification.repository;

import com.recruitpro.verification.entity.ClaimVerdictReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ClaimVerdictReviewRepository extends JpaRepository<ClaimVerdictReview, Long> {

    /** Every review of the verdicts by one organisation, oldest first (the last one per verdict wins). */
    List<ClaimVerdictReview> findAllByVerdictIdInAndOrganizationIdOrderByIdAsc(Collection<Long> verdictIds, Long organizationId);

    boolean existsByVerdictIdIn(Collection<Long> verdictIds);

    /** Candidates whose verdicts were reviewed by the organisation (re-matched when its mode changes). */
    @Query("select distinct v.candidateId from ClaimVerdictReview r, ClaimVerdict v "
            + "where r.verdictId = v.id and r.organizationId = :organizationId")
    List<Long> findReviewedCandidateIds(@Param("organizationId") Long organizationId);

    @Modifying(flushAutomatically = true)
    @Query("delete from ClaimVerdictReview r where r.verdictId in :verdictIds")
    int deleteAllByVerdictIds(@Param("verdictIds") Collection<Long> verdictIds);
}
