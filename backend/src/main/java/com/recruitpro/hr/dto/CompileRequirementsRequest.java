package com.recruitpro.hr.dto;

import com.recruitpro.hr.entity.ExperienceLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/jobs/compile-requirements: {title, description, experienceLevel?}. The limits equal the job's own
 * (title 255, description 50,000; integration pass R6-B1), so every job that can be saved can be compiled; the
 * service cuts them to the AI service's 200 / 20,000 characters.
 */
public record CompileRequirementsRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 50000) String description,
        ExperienceLevel experienceLevel) {
}
