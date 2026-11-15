package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A shared verdict as staff see it. Blind-first: {@code evidence} and {@code candidateNote} are null until the
 * application's identity is revealed; reasons, notes and the probe question are de-identified at the source.
 * Extra fields beyond the contract: {@code sourceKind} (GITHUB, ..., REFERENCE), {@code createdAt}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record StaffVerdict(Long id, String verifier, String verdict, boolean ownershipProven, List<VerdictReason> reasons,
                           List<IntegrityNoteView> integrityNotes, String probeQuestion, String candidateNote,
                           ReviewView myOrgReview, List<EvidenceItemResponse> evidence, int evidenceCount,
                           String sourceKind, LocalDateTime createdAt) {
}
