package com.recruitpro.verification.repository;

import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.SubjectType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClaimVerdictRepository extends JpaRepository<ClaimVerdict, Long> {

    Optional<ClaimVerdict> findByIdAndCandidateId(Long id, Long candidateId);

    List<ClaimVerdict> findAllByCandidateIdAndCurrentTrueOrderByIdAsc(Long candidateId);

    List<ClaimVerdict> findAllByCandidateIdAndCurrentTrueAndCandidateStatusOrderByIdAsc(Long candidateId,
                                                                                         CandidateVerdictStatus status);

    List<ClaimVerdict> findAllByCandidateIdInAndCurrentTrueAndCandidateStatusOrderByIdDesc(Collection<Long> candidateIds,
                                                                                            CandidateVerdictStatus status);

    /** Current verdicts of one claim subject (any source or reference): withdrawn when the subject disappears. */
    List<ClaimVerdict> findAllByCandidateIdAndSubjectTypeAndSubjectIdAndCurrentTrue(Long candidateId, SubjectType subjectType,
                                                                                     Long subjectId);

    List<ClaimVerdict> findAllByCandidateIdAndSubjectTypeAndSubjectIdAndSourceIdAndCurrentTrue(
            Long candidateId, SubjectType subjectType, Long subjectId, Long sourceId);

    List<ClaimVerdict> findAllByCandidateIdAndSubjectTypeAndSubjectIdAndReferenceIdAndCurrentTrue(
            Long candidateId, SubjectType subjectType, Long subjectId, Long referenceId);

    List<ClaimVerdict> findAllBySourceId(Long sourceId);

    List<ClaimVerdict> findAllByReferenceIdOrderByIdAsc(Long referenceId);

    List<ClaimVerdict> findAllByRunIdOrderByIdAsc(Long runId);

    List<ClaimVerdict> findAllByCandidateIdOrderByIdAsc(Long candidateId);

    @Query("select v.id from ClaimVerdict v where v.candidateId = :candidateId")
    List<Long> findIdsByCandidateId(@Param("candidateId") Long candidateId);

    @Modifying(flushAutomatically = true)
    @Query("delete from ClaimVerdict v where v.candidateId = :candidateId")
    int deleteAllByCandidate(@Param("candidateId") Long candidateId);
}
