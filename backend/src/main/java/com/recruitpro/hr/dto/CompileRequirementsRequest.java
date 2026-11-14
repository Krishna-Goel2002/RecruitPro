package com.recruitpro.hr.dto;

import com.recruitpro.hr.entity.ExperienceLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/jobs/compile-requirements: {title, description, experienceLevel?}. */
public record CompileRequirementsRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 20000) String description,
        ExperienceLevel experienceLevel) {
}
