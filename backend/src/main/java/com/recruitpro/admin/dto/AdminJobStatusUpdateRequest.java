package com.recruitpro.admin.dto;

import com.recruitpro.hr.entity.JobStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminJobStatusUpdateRequest(
        @NotNull(message = "status is required")
        JobStatus status,
        @NotBlank(message = "reason is required for administrative override")
        @Size(max = 500, message = "reason cannot exceed 500 characters")
        String reason
) { }
