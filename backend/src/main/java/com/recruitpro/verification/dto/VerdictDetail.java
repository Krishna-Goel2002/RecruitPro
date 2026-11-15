package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /verdicts/{id} (candidate): the verdict with its run summary, reasons, integrity notes, probe question and
 * every evidence item (full, with URLs).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VerdictDetail(Long id, String subjectType, Long subjectId, String claimType, String value, String verifier,
                            String verifierVersion, String verdict, boolean ownershipProven, String candidateStatus,
                            String candidateNote, LocalDateTime candidateRespondedAt, boolean current, Long sourceId,
                            String sourceKind, String sourceLabel, Long referenceId, Long runId,
                            List<VerdictReason> summary, List<VerdictReason> reasons,
                            List<IntegrityNoteView> integrityNotes, String probeQuestion,
                            List<EvidenceItemResponse> evidence, LocalDateTime createdAt) {
}
