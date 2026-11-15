package com.recruitpro.verification.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** One verification of a source by the agent service (VERIFY_SOURCE job, entity id = this id). */
@Entity
@Table(name = "verification_runs")
@Getter
@Setter
@NoArgsConstructor
public class VerificationRun extends BaseEntity {

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private VerificationRunStatus status = VerificationRunStatus.PENDING;

    @Column(length = 40)
    private String verifier;

    @Column(name = "verifier_version", length = 40)
    private String verifierVersion;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "requested_by", nullable = false, length = 20)
    private RunRequester requestedBy = RunRequester.CANDIDATE;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "error_code", length = 60)
    private String errorCode;

    @Column(name = "usage_json", columnDefinition = "TEXT")
    private String usageJson;

    /** Cited narrative of the run (agent {@code summary}: list of reasons). */
    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;

    @Column(name = "warnings_json", columnDefinition = "TEXT")
    private String warningsJson;

    public boolean isActive() {
        return status == VerificationRunStatus.PENDING || status == VerificationRunStatus.RUNNING;
    }
}
