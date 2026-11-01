package com.recruitpro.hr.dto;

import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;

import java.time.LocalDateTime;
import java.util.List;

public record ApplicantIntelligenceResponse(
        Long applicationId,
        ApplicationStatus applicationStatus,
        LocalDateTime appliedAt,
        ResumeAnalysisStatus analysisStatus,
        Double observedEvidenceCoverage,
        String scoreLabel,
        String algorithmVersion,
        String matchingErrorCode,
        List<RequirementMatchResponse> requirements
) { }
