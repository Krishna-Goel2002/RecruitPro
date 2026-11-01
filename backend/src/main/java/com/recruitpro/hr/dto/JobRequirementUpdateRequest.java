package com.recruitpro.hr.dto;

import com.recruitpro.hr.entity.RequirementType;
import jakarta.validation.constraints.*;

public record JobRequirementUpdateRequest(
        @NotNull RequirementType type,
        @NotBlank @Size(max=500) String description,
        @NotNull Boolean mandatory,
        @PositiveOrZero Integer minimumYears) { }
