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

@Entity
@Table(name = "job_match_runs", uniqueConstraints = @UniqueConstraint(name = "uk_job_match_runs_application", columnNames = "application_id"))
@Getter
@Setter
@NoArgsConstructor
public class JobMatchRun extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
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

    @Column(name = "observed_evidence_coverage", precision = 5, scale = 1)
    private BigDecimal observedEvidenceCoverage;

    @Column(name = "score_label", nullable = false, length = 255)
    private String scoreLabel;

    @Column(name = "requirement_results_json", nullable = false, columnDefinition = "TEXT")
    private String requirementResultsJson;

    @Column(name = "calculated_at", nullable = false)
    private LocalDateTime calculatedAt;
}
