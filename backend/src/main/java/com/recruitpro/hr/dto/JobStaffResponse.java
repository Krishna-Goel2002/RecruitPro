package com.recruitpro.hr.dto;

import com.recruitpro.hr.entity.ExperienceLevel;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.JobType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** JobStaff (plan 5.1): the job as staff of its organisation see it. */
public record JobStaffResponse(Long id, Long organizationId, String title, String description, String department,
                               String location, JobType jobType, ExperienceLevel experienceLevel,
                               BigDecimal minimumSalary, BigDecimal maximumSalary, int vacancies, JobStatus status,
                               LocalDateTime publishedAt, LocalDateTime deadline, String closedReason,
                               int requirementsVersion, String createdByEmail, String createdByName,
                               LocalDateTime createdAt, LocalDateTime updatedAt, List<RequirementResponse> requirements,
                               long applicationCount) {
    public static JobStaffResponse from(Job j, List<RequirementResponse> requirements, long applicationCount) {
        var creator = j.getCreatedBy() == null ? null : j.getCreatedBy().getUser();
        return new JobStaffResponse(j.getId(), j.getOrganizationId(), j.getTitle(), j.getDescription(), j.getDepartment(),
                j.getLocation(), j.getJobType(), j.getExperienceLevel(), j.getMinimumSalary(), j.getMaximumSalary(),
                j.getVacancies(), j.getStatus(), j.getPublishedAt(), j.getDeadline(), j.getClosedReason(),
                j.getRequirementsVersion(), creator == null ? null : creator.getEmail(),
                creator == null ? null : creator.getFullName(), j.getCreatedAt(), j.getUpdatedAt(), requirements,
                applicationCount);
    }
}
