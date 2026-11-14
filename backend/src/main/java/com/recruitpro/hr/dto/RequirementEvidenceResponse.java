package com.recruitpro.hr.dto;

import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.hr.entity.RequirementType;

import java.util.List;

/**
 * RequirementEvidence (plan 5.4) from the current match run, plus {@code provenance} (RESUME, CANDIDATE_CLAIM,
 * CORROBORATED, WORK_SAMPLE, INTERVIEW or null). {@code status}: MATCHED, UNCLEAR or NOT_OBSERVED.
 */
public record RequirementEvidenceResponse(Long requirementId, RequirementType type, String description, boolean mandatory,
                                          boolean knockout, Integer minimumYears, List<String> alternatives, String status,
                                          EvidenceTier tier, String evidence, String sourceSection, String explanation,
                                          String verificationHint, String provenance) {
}
