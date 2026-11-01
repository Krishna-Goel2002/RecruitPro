package com.recruitpro.hr.repository;

import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job> {
    long countByStatus(JobStatus status);
    long countByCreatedById(Long hrProfileId);
}
