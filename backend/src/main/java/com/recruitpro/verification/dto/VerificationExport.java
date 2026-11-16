package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The verification part of the candidate data export: sources (never tokens, stored file paths or pasted
 * credentials), runs, evidence metadata (no snapshots), verdicts and reference requests (never the token).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VerificationExport(List<Source> sources, List<Run> runs, List<Evidence> evidence, List<Verdict> verdicts,
                                 List<Reference> references) {

    public static VerificationExport empty() {
        return new VerificationExport(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Source(Long id, String kind, String identifier, String displayLabel, String ownershipStatus,
                         String ownershipMethod, LocalDateTime ownershipCheckedAt, boolean accessTokenStored,
                         String tokenScopes, String consentTextVersion, LocalDateTime consentedAt, LocalDateTime revokedAt,
                         LocalDateTime createdAt, String ownershipDetail) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Run(Long id, Long sourceId, String status, String verifier, String verifierVersion, String requestedBy,
                      LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime finishedAt, String errorCode) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Evidence(Long id, Long runId, Long sourceId, Long referenceId, String key, String kind, String url,
                           String title, LocalDateTime retrievedAt, String contentHash, String excerpt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Verdict(Long id, String subjectType, Long subjectId, String claimType, String claimValue, Long sourceId,
                          Long referenceId, Long runId, String verifier, String verifierVersion, String verdict,
                          boolean ownershipProven, JsonNode reasons, JsonNode integrityNotes, String probeQuestion,
                          String candidateStatus, String candidateNote, boolean current, LocalDateTime createdAt,
                          JsonNode domainTrust) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Reference(Long id, String refereeName, String refereeEmail, String relationship, String employerName,
                            String employerDomain, JsonNode claims, String status, JsonNode answers, String refereeComment,
                            LocalDateTime expiresAt, LocalDateTime answeredAt, LocalDateTime consentedAt,
                            LocalDateTime createdAt) {
    }
}
