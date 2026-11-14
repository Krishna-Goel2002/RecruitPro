package com.recruitpro.candidate.repository;

import com.recruitpro.candidate.entity.ResumeAnalysisStatus;

/** Resume analysis status and parser flags of one resume (applicant lists). */
public record AnalysisSummary(Long resumeId, Long analysisId, ResumeAnalysisStatus status, String flagsJson) {
}
