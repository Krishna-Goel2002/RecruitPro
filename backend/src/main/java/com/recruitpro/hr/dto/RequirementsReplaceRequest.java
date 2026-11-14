package com.recruitpro.hr.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** PUT /api/v1/jobs/{id}/requirements: replaces the whole requirement list (max 100). */
public record RequirementsReplaceRequest(@Valid @NotNull @Size(max = 100) List<RequirementInput> requirements) {
}
