package com.recruitpro.hr.repository;

import com.recruitpro.hr.entity.JobMatchRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JobMatchRunRepository extends JpaRepository<JobMatchRun, Long> {
    Optional<JobMatchRun> findByApplicationId(Long applicationId);
}
