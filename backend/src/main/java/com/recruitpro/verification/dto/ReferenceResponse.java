package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/** A reference request as the candidate sees it (the referee's answers appear as verdicts). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReferenceResponse(Long id, String refereeName, String refereeEmail, String relationship, String employerName,
                                String employerDomain, List<ReferenceClaim> claims, String status, LocalDateTime expiresAt,
                                LocalDateTime answeredAt, LocalDateTime createdAt, int verdictCount, String errorCode) {
}
