package com.recruitpro.hr.dto;

import com.recruitpro.hr.entity.Proficiency;
import com.recruitpro.hr.entity.RequirementType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * RequirementInput (plan 5.1). {@code mandatory} defaults to true, {@code knockout} to {@code mandatory},
 * {@code alternatives} to []. {@code id} is only used by the bulk replace endpoint: an item with the id of an
 * existing requirement of the job updates it in place (keeps links from work samples and interview kits);
 * items without id are created; requirements not listed are deleted.
 */
public record RequirementInput(
        Long id,
        @NotNull RequirementType type,
        @NotBlank @Size(max = 500) String description,
        Boolean mandatory,
        Boolean knockout,
        @PositiveOrZero @Max(50) Integer minimumYears,
        @Size(max = 10) List<String> alternatives,
        Proficiency proficiency) {
}
