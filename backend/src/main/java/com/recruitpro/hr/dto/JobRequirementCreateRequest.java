package com.recruitpro.hr.dto;

import com.recruitpro.hr.entity.RequirementType;
import jakarta.validation.constraints.*;

public record JobRequirementCreateRequest(
        @NotNull RequirementType type,
        @NotBlank @Size(max=500) String description,
        Boolean mandatory,
        @PositiveOrZero Integer minimumYears) { }
