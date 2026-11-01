package com.recruitpro.application.dto;

import jakarta.validation.constraints.Size;

public record ApplicationCreateRequest(Long resumeId, @Size(max = 10000) String coverLetter) { }
