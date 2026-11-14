package com.recruitpro.assessment.entity;

import com.recruitpro.application.entity.Application;
import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One work sample assigned to one application (unique per sample and application). {@code scoresJson} holds
 * {@code {requirementId, criterion, score 1-4, note}[]} in rubric order; {@code totalScore} is their mean (1.00-4.00).
 */
@Entity
@Table(name = "work_sample_assignments")
@Getter
@Setter
@NoArgsConstructor
public class WorkSampleAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_sample_id", nullable = false)
    private WorkSample workSample;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private WorkSampleAssignmentStatus status = WorkSampleAssignmentStatus.ASSIGNED;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    /** Latest time the candidate may start (null: no due date). */
    @Column(name = "due_at")
    private LocalDateTime dueAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "response_text", columnDefinition = "TEXT")
    private String responseText;

    @Column(name = "response_url", length = 500)
    private String responseUrl;

    @Column(name = "scores_json", columnDefinition = "TEXT")
    private String scoresJson;

    @Column(name = "total_score", precision = 5, scale = 2)
    private BigDecimal totalScore;

    @Column(name = "scored_by_user_id")
    private Long scoredByUserId;

    @Column(name = "scored_at")
    private LocalDateTime scoredAt;
}
