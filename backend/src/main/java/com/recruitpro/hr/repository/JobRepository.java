package com.recruitpro.hr.repository;

import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job> {
    long countByStatus(JobStatus status);
    long countByCreatedById(Long hrProfileId);

    /** L2: job pages fetch their creator and the creator's user in the same query. */
    @Override
    @EntityGraph(attributePaths = {"createdBy", "createdBy.user"})
    Page<Job> findAll(Specification<Job> spec, Pageable pageable);

    // ---- Organisation-scoped queries (D3): a job of another organisation behaves as missing ----

    Optional<Job> findByIdAndOrganizationId(Long id, Long organizationId);

    long countByOrganizationId(Long organizationId);

    long countByOrganizationIdAndStatus(Long organizationId, JobStatus status);

    boolean existsByOrganizationIdAndTitle(Long organizationId, String title);

    /** Locks the job row (requirement version increments, deadline closer). Plain query, no joins. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from Job j where j.id = :id")
    Optional<Job> lockById(@Param("id") Long id);

    /** Jobs in the given status whose deadline is at or before {@code now} (deadline closer). */
    @Query("select j.id from Job j where j.status = :status and j.deadline is not null and j.deadline <= :now order by j.id")
    List<Long> findIdsByStatusAndDeadlineAtOrBefore(@Param("status") JobStatus status, @Param("now") LocalDateTime now);
}
