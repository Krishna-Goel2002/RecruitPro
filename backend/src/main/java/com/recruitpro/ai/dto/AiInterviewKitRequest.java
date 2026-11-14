package com.recruitpro.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** POST /api/v1/interview-kits/generate request (plan 6.1). {@code focusRequirementIds} puts those requirements first. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiInterviewKitRequest(String jobTitle, List<AiMatchRequest.Requirement> requirements,
                                    List<Long> focusRequirementIds) { }
