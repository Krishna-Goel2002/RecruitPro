package com.recruitpro.hr.dto;

import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.RequirementType;
import java.time.LocalDateTime;

public record JobRequirementResponse(Long id, Long jobId, RequirementType type, String description,
                                     boolean mandatory, Integer minimumYears,
                                     LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static JobRequirementResponse from(JobRequirement r) {
        return new JobRequirementResponse(r.getId(), r.getJob().getId(), r.getType(), r.getDescription(),
                r.isMandatory(), r.getMinimumYears(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
