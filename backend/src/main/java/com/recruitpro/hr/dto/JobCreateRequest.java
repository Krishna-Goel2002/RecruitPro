package com.recruitpro.hr.dto;

import com.recruitpro.hr.entity.ExperienceLevel;
import com.recruitpro.hr.entity.JobType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record JobCreateRequest(
        @NotBlank @Size(max=255) String title,
        @NotBlank String description,
        @NotBlank @Size(max=100) String department,
        @NotBlank @Size(max=255) String location,
        @NotNull JobType jobType,
        @NotNull ExperienceLevel experienceLevel,
        @DecimalMin("0.00") BigDecimal minimumSalary,
        @DecimalMin("0.00") BigDecimal maximumSalary,
        @NotNull @Positive Integer vacancies,
        @Future LocalDateTime deadline) { }
