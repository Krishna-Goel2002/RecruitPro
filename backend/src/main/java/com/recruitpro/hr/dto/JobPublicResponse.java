package com.recruitpro.hr.dto;

import com.recruitpro.hr.entity.ExperienceLevel;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** JobPublic (plan 5.1, M3): what candidates see; no creator, status or internal fields. */
public record JobPublicResponse(Long id, String organizationName, String title, String description, String department,
                                String location, JobType jobType, ExperienceLevel experienceLevel,
                                BigDecimal minimumSalary, BigDecimal maximumSalary, int vacancies,
                                LocalDateTime deadline, LocalDateTime publishedAt, List<RequirementResponse> requirements) {
    public static JobPublicResponse from(Job j, String organizationName, List<RequirementResponse> requirements) {
        return new JobPublicResponse(j.getId(), organizationName, j.getTitle(), j.getDescription(), j.getDepartment(),
                j.getLocation(), j.getJobType(), j.getExperienceLevel(), j.getMinimumSalary(), j.getMaximumSalary(),
                j.getVacancies(), j.getDeadline(), j.getPublishedAt(), requirements);
    }
}
