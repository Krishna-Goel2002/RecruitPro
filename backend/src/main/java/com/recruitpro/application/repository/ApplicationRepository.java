package com.recruitpro.application.repository;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {
    boolean existsByCandidateIdAndJobId(Long candidateId, Long jobId);
    long countByJobId(Long jobId);
    long countByStatus(ApplicationStatus status);
    long countByAppliedAtAfter(LocalDateTime cutoff);

    @EntityGraph(attributePaths = {"candidate", "resume", "job"})
    Page<Application> findAllByCandidateId(Long candidateId, Pageable pageable);

    @EntityGraph(attributePaths = {"candidate", "resume", "job"})
    Optional<Application> findByIdAndCandidateId(Long id, Long candidateId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"candidate", "resume", "job"})
    @Query("select a from Application a where a.id = :id and a.candidate.id = :candidateId")
    Optional<Application> findOwnedForUpdate(@Param("id") Long id, @Param("candidateId") Long candidateId);

    @EntityGraph(attributePaths = {"candidate", "resume", "job"})
    Page<Application> findAllByJobIdAndStatus(Long jobId, ApplicationStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"candidate", "resume", "job"})
    Page<Application> findAllByJobId(Long jobId, Pageable pageable);

    Optional<Application> findByIdAndJobId(Long id, Long jobId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"candidate", "resume", "job", "job.createdBy", "job.createdBy.user"})
    @Query("select a from Application a where a.id = :id and a.job.id = :jobId")
    Optional<Application> findForReview(@Param("id") Long id, @Param("jobId") Long jobId);
}
