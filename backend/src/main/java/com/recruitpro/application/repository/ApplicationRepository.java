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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {
    boolean existsByCandidateIdAndJobId(Long candidateId, Long jobId);
    long countByJobId(Long jobId);
    long countByStatus(ApplicationStatus status);
    long countByAppliedAtAfter(LocalDateTime cutoff);

    // ---- Organisation-scoped counts (D3) ----
    long countByJob_OrganizationId(Long organizationId);
    long countByJob_OrganizationIdAndStatus(Long organizationId, ApplicationStatus status);
    long countByJob_OrganizationIdAndAppliedAtAfter(Long organizationId, LocalDateTime cutoff);

    @Query("select count(distinct a.candidate.id) from Application a where a.job.organizationId = :organizationId")
    long countDistinctCandidatesByOrganizationId(@Param("organizationId") Long organizationId);

    /** L2: application counts for a page of jobs in one query; rows are {jobId, count}. */
    @Query("select a.job.id, count(a) from Application a where a.job.id in :jobIds group by a.job.id")
    List<Object[]> countGroupedByJobIds(@Param("jobIds") Collection<Long> jobIds);

    // ---- Candidate side ----

    @EntityGraph(attributePaths = {"candidate", "resume", "job"})
    Page<Application> findAllByCandidateId(Long candidateId, Pageable pageable);

    @EntityGraph(attributePaths = {"candidate", "resume", "job"})
    Optional<Application> findByIdAndCandidateId(Long id, Long candidateId);

    /**
     * Locks the candidate's application row (withdrawal). A plain query without entity graph or fetch joins:
     * PostgreSQL rejects FOR UPDATE on the nullable side of an outer join. Associations load lazily afterwards.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Application a where a.id = :id and a.candidate.id = :candidateId")
    Optional<Application> findOwnedForUpdate(@Param("id") Long id, @Param("candidateId") Long candidateId);

    /**
     * Locks every application of a candidate (account deletion withdraws open ones). Plain query without joins, so
     * the lock is FOR UPDATE on the applications rows only.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Application a where a.candidate.id = :candidateId order by a.id")
    List<Application> lockAllByCandidateId(@Param("candidateId") Long candidateId);

    // ---- Staff side (always through an access-checked job) ----

    Optional<Application> findByIdAndJobId(Long id, Long jobId);

    /**
     * Locks the application row for an HR decision, reveal or comment. Plain query (no entity graph, no fetch
     * joins) so that the database lock is FOR UPDATE on the applications row only; associations are loaded
     * separately inside the same transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Application a where a.id = :id and a.job.id = :jobId")
    Optional<Application> findForReview(@Param("id") Long id, @Param("jobId") Long jobId);

    /** Locks the application row by id (match run persistence). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Application a where a.id = :id")
    Optional<Application> lockById(@Param("id") Long id);

    /** Ranking input: every application of the job (C2: ranking spans all pages). */
    @Query("select new com.recruitpro.application.repository.ApplicantRow(a.id, a.status, a.appliedAt, a.statusChangedAt, "
            + "a.identityRevealedAt, a.resume.id) from Application a where a.job.id = :jobId")
    List<ApplicantRow> findApplicantRows(@Param("jobId") Long jobId);

    @Query("select new com.recruitpro.application.repository.ApplicantRow(a.id, a.status, a.appliedAt, a.statusChangedAt, "
            + "a.identityRevealedAt, a.resume.id) from Application a where a.job.id = :jobId and a.status = :status")
    List<ApplicantRow> findApplicantRowsByStatus(@Param("jobId") Long jobId, @Param("status") ApplicationStatus status);

    /** One page of applicants with candidate, user and resume (no lock). */
    @EntityGraph(attributePaths = {"candidate", "candidate.user", "resume"})
    @Query("select a from Application a where a.id in :ids")
    List<Application> findAllWithCandidateByIdIn(@Param("ids") Collection<Long> ids);

    // ---- Match triggers ----

    @Query("select a.id from Application a where a.job.id = :jobId and a.status in :statuses order by a.id")
    List<Long> findIdsByJobIdAndStatusIn(@Param("jobId") Long jobId, @Param("statuses") Collection<ApplicationStatus> statuses);

    @Query("select a.id from Application a where a.resume.id = :resumeId and a.status in :statuses order by a.id")
    List<Long> findIdsByResumeIdAndStatusIn(@Param("resumeId") Long resumeId, @Param("statuses") Collection<ApplicationStatus> statuses);

    @Query("select a.id from Application a where a.candidate.id = :candidateId and a.status in :statuses order by a.id")
    List<Long> findIdsByCandidateIdAndStatusIn(@Param("candidateId") Long candidateId,
                                               @Param("statuses") Collection<ApplicationStatus> statuses);

    @Query("select a.id from Application a where a.candidate.id = :candidateId and a.job.organizationId = :organizationId "
            + "and a.status in :statuses order by a.id")
    List<Long> findIdsByCandidateIdAndOrganizationIdAndStatusIn(@Param("candidateId") Long candidateId,
                                                               @Param("organizationId") Long organizationId,
                                                               @Param("statuses") Collection<ApplicationStatus> statuses);
}
