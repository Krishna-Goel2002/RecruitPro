package com.recruitpro.application.repository;

import com.recruitpro.application.entity.ApplicationStatus;

import java.time.LocalDateTime;

/** Lightweight projection of one application for server-side ranking (no associations, no text columns). */
public record ApplicantRow(Long applicationId, ApplicationStatus status, LocalDateTime appliedAt,
                           LocalDateTime statusChangedAt, LocalDateTime identityRevealedAt, Long resumeId) {
}
