package com.recruitpro.hr.dto;

public record RequirementMatchResponse(
        Long requirementId,
        String type,
        String description,
        boolean mandatory,
        Integer minimumYears,
        String status,
        String evidence,
        String sourceSection,
        String provenance,
        String explanation
) { }
