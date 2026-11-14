package com.recruitpro.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** POST /api/v1/requirements/compile request. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiCompileRequest(String title, String description, String experienceLevel) { }
