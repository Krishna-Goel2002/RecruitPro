package com.recruitpro.fairness.repository;

import com.recruitpro.application.entity.ApplicationStatus;

/** One application considered by a fairness report: its candidate and current status. */
public record ApplicantStageRow(Long applicationId, Long candidateId, ApplicationStatus status) {
}
