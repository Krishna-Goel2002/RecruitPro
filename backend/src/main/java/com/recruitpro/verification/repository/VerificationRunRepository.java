package com.recruitpro.verification.repository;

import com.recruitpro.verification.entity.VerificationRun;
import com.recruitpro.verification.entity.VerificationRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VerificationRunRepository extends JpaRepository<VerificationRun, Long> {

    Optional<VerificationRun> findFirstBySourceIdOrderByIdDesc(Long sourceId);

    boolean existsBySourceIdAndStatusIn(Long sourceId, Collection<VerificationRunStatus> statuses);

    List<VerificationRun> findAllBySourceIdAndStatusIn(Long sourceId, Collection<VerificationRunStatus> statuses);

    long countByCandidateIdAndCreatedAtAfter(Long candidateId, LocalDateTime after);

    /** The oldest run inside the window (its age tells when the 24-hour limit frees up: Retry-After). */
    Optional<VerificationRun> findFirstByCandidateIdAndCreatedAtAfterOrderByCreatedAtAsc(Long candidateId, LocalDateTime after);

    /**
     * The run's candidate without loading the run (R3-B01): the result writer locks the candidate first and only then
     * reads the run and its source, so a concurrent revoke is never missed.
     */
    @Query("select r.candidateId from VerificationRun r where r.id = :id")
    Optional<Long> findCandidateIdById(@Param("id") Long id);

    List<VerificationRun> findAllByCandidateIdOrderByIdAsc(Long candidateId);

    @Modifying(flushAutomatically = true)
    @Query("delete from VerificationRun r where r.candidateId = :candidateId")
    int deleteAllByCandidate(@Param("candidateId") Long candidateId);
}
