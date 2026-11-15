package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/** GET /api/v1/public/references/{token}. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicReferenceResponse(String candidateFirstName, String relationship, String employerName,
                                      List<PublicReferenceClaim> claims, LocalDateTime expiresAt) {
}
