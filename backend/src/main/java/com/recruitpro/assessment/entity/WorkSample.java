package com.recruitpro.assessment.entity;

import com.recruitpro.common.entity.BaseEntity;
import com.recruitpro.hr.entity.Job;
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

/**
 * A timed work sample for a job (plan 5.6). {@code rubricJson} holds {@code RubricCriterion[]}: requirement link
 * (or null), criterion and four behavioural anchors keyed "1".."4".
 */
@Entity
@Table(name = "work_samples")
@Getter
@Setter
@NoArgsConstructor
public class WorkSample extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String instructions;

    @Column(name = "time_limit_minutes", nullable = false)
    private int timeLimitMinutes;

    @Column(name = "rubric_json", nullable = false, columnDefinition = "TEXT")
    private String rubricJson;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private WorkSampleStatus status = WorkSampleStatus.DRAFT;

    /** Staff user who generated the sample (plain id, like organisation ids). */
    @Column(name = "created_by_user_id")
    private Long createdByUserId;
}
