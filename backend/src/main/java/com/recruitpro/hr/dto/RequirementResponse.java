package com.recruitpro.hr.dto;

import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.Proficiency;
import com.recruitpro.hr.entity.RequirementType;

import java.util.List;

/** Requirement (plan 5.1); {@code displayOrder} (integration pass, R6-B2): its position in HR's list, from 0. */
public record RequirementResponse(Long id, RequirementType type, String description, boolean mandatory, boolean knockout,
                                  Integer minimumYears, List<String> alternatives, Proficiency proficiency,
                                  int displayOrder) {
    public static RequirementResponse from(JobRequirement r) {
        return new RequirementResponse(r.getId(), r.getType(), r.getDescription(), r.isMandatory(), r.isKnockout(),
                r.getMinimumYears(), List.copyOf(r.getAlternatives()), r.getProficiency(), r.getDisplayOrder());
    }
}
