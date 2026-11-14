package com.recruitpro.hr.entity;

import com.recruitpro.application.entity.Application;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One matching run of an application against its job's requirements (D8: append-only). Exactly one run per
 * application is current ({@code is_current}); decisions record the run they were made on.
 * {@code requirementResultsJson} holds {@code {"requirements":[...], "unverifiedKnockouts":[...]}}.
 */
@Entity
@Table(name = "job_match_runs")
@Getter
@Setter
@NoArgsConstructor
public class JobMatchRun extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resume_analysis_id", nullable = false)
    private ResumeAnalysis resumeAnalysis;

    @Column(name = "input_fingerprint", nullable = false, length = 64)
    private String inputFingerprint;

    @Column(name = "algorithm_version", nullable = false, length = 80)
    private String algorithmVersion;

    /** Legacy column (V9); equals {@link #verifiedCoverage} for evidence-ladder runs. */
    @Column(name = "observed_evidence_coverage", precision = 5, scale = 1)
    private BigDecimal observedEvidenceCoverage;

    @Column(name = "score_label", nullable = false, length = 255)
    private String scoreLabel;

    @Column(name = "requirement_results_json", nullable = false, columnDefinition = "TEXT")
    private String requirementResultsJson;

    @Column(name = "calculated_at", nullable = false)
    private LocalDateTime calculatedAt;

    @Column(name = "is_current", nullable = false)
    private boolean currentRun = true;

    /** Weighted coverage of requirements matched at tier RESUME_STATED or higher (0-100). */
    @Column(name = "verified_coverage", precision = 5, scale = 1)
    private BigDecimal verifiedCoverage;

    /** Weighted coverage of requirements matched at any tier, including candidate claims (0-100). */
    @Column(name = "claimed_coverage", precision = 5, scale = 1)
    private BigDecimal claimedCoverage;

    @Column(name = "knockouts_verified")
    private Boolean knockoutsVerified;

    @Column(name = "requirements_version", nullable = false)
    private int requirementsVersion = 1;

    @Column(name = "trigger_reason", length = 40)
    private String triggerReason;
}
