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

    List<VerificationRun> findAllByCandidateIdOrderByIdAsc(Long candidateId);

    @Modifying(flushAutomatically = true)
    @Query("delete from VerificationRun r where r.candidateId = :candidateId")
    int deleteAllByCandidate(@Param("candidateId") Long candidateId);
}
