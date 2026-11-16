package com.recruitpro.candidate.privacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.recruitpro.application.dto.ApplicationDetailResponse;
import com.recruitpro.assessment.dto.WorkSampleFileResponse;
import com.recruitpro.candidate.dto.CandidateProfileResponse;
import com.recruitpro.evidence.dto.CandidateClaimResponse;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.fairness.dto.EqualOpportunityResponse;
import com.recruitpro.interview.dto.CandidateInterviewResponse;
import com.recruitpro.verification.dto.VerificationExport;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /api/v1/candidates/me/export: every record RecruitPro keeps about the candidate (DPDP / GDPR access right).
 * Staff-only material (review comments, scores, rubrics, interviewer notes, match evidence) is not part of it;
 * decisions appear as the status timeline the candidate already sees. Nulls are written explicitly. {@code verification}
 * (plan v2, WP-VBE): sources (never tokens), runs, evidence metadata, verdicts and reference requests.
 * Every nested record is export-specific or annotated {@code ALWAYS} (integration pass, R5#7: the profile and the
 * claims used the API's {@code non_null} records before, so their nulls were missing).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CandidateDataExport(String format, LocalDateTime exportedAt, Account account, ProfileExport profile,
                                  List<ResumeExport> resumes, List<FactExport> facts, List<ClaimExport> claims,
                                  List<ApplicationExport> applications, List<WorkSampleExport> workSamples,
                                  List<CandidateInterviewResponse> interviews, EqualOpportunityResponse equalOpportunity,
                                  List<NotificationExport> notifications, VerificationExport verification) {

    public static final String FORMAT = "recruitpro-candidate-export-1";

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Account(Long id, String email, String firstName, String lastName, String role,
                          LocalDateTime emailVerifiedAt, LocalDateTime createdAt) { }

    /** The profile as {@link CandidateProfileResponse}, with explicit nulls. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProfileExport(Long id, String email, String firstName, String lastName, String address, String city,
                                String state, String country, String zipCode, String linkedinUrl, String portfolioUrl,
                                Integer yearsOfExperience, String currentCompany, String currentDesignation,
                                BigDecimal expectedSalary, Integer noticePeriodDays, String profileSummary,
                                LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static ProfileExport from(CandidateProfileResponse p) {
            return p == null ? null : new ProfileExport(p.id(), p.email(), p.firstName(), p.lastName(), p.address(),
                    p.city(), p.state(), p.country(), p.zipCode(), p.linkedinUrl(), p.portfolioUrl(),
                    p.yearsOfExperience(), p.currentCompany(), p.currentDesignation(), p.expectedSalary(),
                    p.noticePeriodDays(), p.profileSummary(), p.createdAt(), p.updatedAt());
        }
    }

    /**
     * A claim as the candidate sees it ({@link CandidateClaimResponse#forCandidate}: the verification is the summary
     * of every organisation's check, without recruiter notes), with explicit nulls.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ClaimExport(Long id, ClaimType claimType, String value, String evidenceUrl, Long sourceFactId,
                              EvidenceTier tier, String verificationStatus, LocalDateTime verifiedAt,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static ClaimExport from(CandidateClaimResponse c) {
            return new ClaimExport(c.id(), c.claimType(), c.value(), c.evidenceUrl(), c.sourceFactId(), c.tier(),
                    c.verificationStatus(), c.verifiedAt(), c.createdAt(), c.updatedAt());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ResumeExport(Long id, String originalFilename, String contentType, long fileSize,
                               LocalDateTime uploadedAt, String analysisStatus, String parserVersion,
                               LocalDateTime analyzedAt, JsonNode flags, JsonNode timeline) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FactExport(Long id, Long resumeId, String type, String extractedValue, String reviewedValue,
                             String reviewStatus, String evidence, String sourceSection, String tier,
                             LocalDateTime reviewedAt, LocalDateTime createdAt, JsonNode attributes) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ApplicationExport(Long id, Long jobId, String jobTitle, String organizationName, Long resumeId,
                                    String status, String coverLetter, LocalDateTime appliedAt,
                                    LocalDateTime statusChangedAt, List<ApplicationDetailResponse.TimelineEntry> timeline) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WorkSampleExport(Long assignmentId, Long applicationId, String jobTitle, String title, String status,
                                   LocalDateTime assignedAt, LocalDateTime dueAt, LocalDateTime startedAt,
                                   LocalDateTime submittedAt, String responseText, String responseUrl,
                                   List<WorkSampleFileResponse> files, LocalDateTime cancelledAt, String cancelReason) { }

    /** {@code body} is included for inbox messages only; e-mails may carry one-time links and are listed without it. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NotificationExport(Long id, String channel, String template, String subject, String body, String status,
                                     LocalDateTime createdAt, LocalDateTime sentAt, LocalDateTime readAt) { }
}
