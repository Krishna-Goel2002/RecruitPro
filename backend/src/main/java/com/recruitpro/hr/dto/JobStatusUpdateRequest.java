package com.recruitpro.hr.dto;

import com.recruitpro.hr.entity.JobStatus;
import jakarta.validation.constraints.NotNull;

public record JobStatusUpdateRequest(@NotNull JobStatus status) { }
