package com.recruitpro.fairness.repository;

import com.recruitpro.application.entity.ApplicationStatus;

/** One decision-history event of an application (fairness: which stages the applicant ever reached). */
public record StatusEventRow(Long applicationId, ApplicationStatus previousStatus, ApplicationStatus newStatus) {
}
