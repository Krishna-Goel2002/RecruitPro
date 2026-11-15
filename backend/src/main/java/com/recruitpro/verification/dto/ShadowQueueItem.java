package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One shared, current verdict in the shadow-evaluation queue. {@code evidence} is null unless an application of
 * the candidate to the organisation is revealed.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ShadowQueueItem(Long verdictId, Long applicationId, boolean revealed, String subjectType, Long subjectId,
                              String claimType, String value, String sourceKind, String verifier, String verdict,
                              boolean ownershipProven, List<VerdictReason> reasons, List<IntegrityNoteView> integrityNotes,
                              String probeQuestion, List<EvidenceItemResponse> evidence, int evidenceCount,
                              LocalDateTime createdAt, LabelView myLabel, int labelCount) {
}
