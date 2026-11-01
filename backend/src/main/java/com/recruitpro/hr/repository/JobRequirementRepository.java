package com.recruitpro.hr.repository;

import com.recruitpro.hr.entity.JobRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface JobRequirementRepository extends JpaRepository<JobRequirement, Long> {
    List<JobRequirement> findByJobIdOrderById(Long jobId);
    Optional<JobRequirement> findByIdAndJobId(Long id, Long jobId);
}
