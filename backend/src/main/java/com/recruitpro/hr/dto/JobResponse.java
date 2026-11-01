package com.recruitpro.hr.dto;

import com.recruitpro.hr.entity.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record JobResponse(Long id, Long createdByProfileId, String createdByEmail, String title,
                          String description, String department, String location, JobType jobType,
                          ExperienceLevel experienceLevel, BigDecimal minimumSalary, BigDecimal maximumSalary,
                          int vacancies, JobStatus status, LocalDateTime publishedAt, LocalDateTime deadline,
                          LocalDateTime createdAt, LocalDateTime updatedAt,
                          List<JobRequirementResponse> requirements) {
    public static JobResponse from(Job j, List<JobRequirementResponse> requirements) {
        return new JobResponse(j.getId(), j.getCreatedBy().getId(), j.getCreatedBy().getUser().getEmail(),
                j.getTitle(), j.getDescription(), j.getDepartment(), j.getLocation(), j.getJobType(),
                j.getExperienceLevel(), j.getMinimumSalary(), j.getMaximumSalary(), j.getVacancies(),
                j.getStatus(), j.getPublishedAt(), j.getDeadline(), j.getCreatedAt(), j.getUpdatedAt(), requirements);
    }
}
