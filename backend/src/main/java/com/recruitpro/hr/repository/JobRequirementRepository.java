package com.recruitpro.hr.repository;

import com.recruitpro.hr.entity.JobRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobRequirementRepository extends JpaRepository<JobRequirement, Long> {
    /** Creation order; use {@link #findByJobIdOrderByDisplayOrderAscIdAsc} wherever HR's order matters. */
    List<JobRequirement> findByJobIdOrderById(Long jobId);

    /** The job's requirements in HR's order (R6-B2, integration pass): display order, then id. */
    List<JobRequirement> findByJobIdOrderByDisplayOrderAscIdAsc(Long jobId);

    /** Highest display order of the job's requirements, -1 when it has none (a new requirement goes last). */
    @Query("select coalesce(max(r.displayOrder), -1) from JobRequirement r where r.job.id = :jobId")
    int maxDisplayOrder(@Param("jobId") Long jobId);
    Optional<JobRequirement> findByIdAndJobId(Long id, Long jobId);
    long countByJobId(Long jobId);

    /** L2: requirements of a page of jobs in one query. */
    @Query("select r from JobRequirement r where r.job.id in :jobIds order by r.job.id, r.displayOrder, r.id")
    List<JobRequirement> findAllByJobIdIn(@Param("jobIds") Collection<Long> jobIds);
}
