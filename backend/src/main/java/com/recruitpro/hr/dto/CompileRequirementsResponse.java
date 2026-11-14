package com.recruitpro.hr.dto;

import com.recruitpro.hr.entity.Proficiency;
import com.recruitpro.hr.entity.RequirementType;

import java.util.List;

/** CompileResult (plan 5.3): suggestions only; nothing is persisted until HR saves requirements. */
public record CompileRequirementsResponse(String engine, List<CompiledRequirement> requirements, List<CompileWarning> warnings) {

    public record CompiledRequirement(RequirementType type, String description, boolean mandatory, boolean knockout,
                                      Integer minimumYears, List<String> alternatives, Proficiency proficiency,
                                      String rationale) { }

    /** kind: AGE_CODED, GENDER_CODED, DEGREE_INFLATION, EXCLUSIONARY, VAGUE or EXCESSIVE_EXPERIENCE. */
    public record CompileWarning(String phrase, String kind, String suggestion) { }
}
