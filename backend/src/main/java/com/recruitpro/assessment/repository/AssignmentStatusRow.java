package com.recruitpro.assessment.repository;

import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;

/** Status of one assignment of an application (HR applicant list extras). */
public record AssignmentStatusRow(Long applicationId, Long assignmentId, WorkSampleAssignmentStatus status) {
}
