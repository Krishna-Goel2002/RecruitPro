package com.recruitpro.hr.repository;

import com.recruitpro.hr.entity.JobRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobRequirementRepository extends JpaRepository<JobRequirement, Long> {
    List<JobRequirement> findByJobIdOrderById(Long jobId);
    Optional<JobRequirement> findByIdAndJobId(Long id, Long jobId);
    long countByJobId(Long jobId);

    /** L2: requirements of a page of jobs in one query. */
    @Query("select r from JobRequirement r where r.job.id in :jobIds order by r.job.id, r.id")
    List<JobRequirement> findAllByJobIdIn(@Param("jobIds") Collection<Long> jobIds);
}
