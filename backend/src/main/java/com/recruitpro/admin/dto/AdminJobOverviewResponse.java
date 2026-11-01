package com.recruitpro.admin.dto;

import com.recruitpro.hr.entity.JobStatus;
import java.time.LocalDateTime;

public record AdminJobOverviewResponse(
        Long id,
        String title,
        String department,
        String location,
        String jobType,
        String experienceLevel,
        JobStatus status,
        int vacancies,
        String createdByEmail,
        LocalDateTime createdAt,
        long applicationCount
) { }
