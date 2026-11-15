package com.recruitpro.assessment.repository;

import com.recruitpro.assessment.entity.WorkSampleSubmissionFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorkSampleSubmissionFileRepository extends JpaRepository<WorkSampleSubmissionFile, Long> {

    List<WorkSampleSubmissionFile> findAllByAssignmentIdOrderByIdAsc(Long assignmentId);

    List<WorkSampleSubmissionFile> findAllByAssignmentIdInOrderByIdAsc(Collection<Long> assignmentIds);

    Optional<WorkSampleSubmissionFile> findByIdAndAssignmentId(Long id, Long assignmentId);

    long countByAssignmentId(Long assignmentId);
}
