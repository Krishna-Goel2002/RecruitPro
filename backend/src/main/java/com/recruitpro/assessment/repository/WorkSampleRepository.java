package com.recruitpro.assessment.repository;

import com.recruitpro.assessment.entity.WorkSample;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Work samples; always reached through an access-checked job (D3). */
public interface WorkSampleRepository extends JpaRepository<WorkSample, Long> {

    List<WorkSample> findAllByJobIdOrderByIdAsc(Long jobId);

    Optional<WorkSample> findByIdAndJobId(Long id, Long jobId);
}
