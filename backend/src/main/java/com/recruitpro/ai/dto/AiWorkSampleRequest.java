package com.recruitpro.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** POST /api/v1/work-samples/generate request (plan 6.1): job title (1..200), optional description, requirements v2. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiWorkSampleRequest(String jobTitle, String jobDescription, List<AiMatchRequest.Requirement> requirements) { }
