package com.recruitpro.interview.entity;

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

/**
 * The assigned interviewer's scorecard (one per interview, immutable once submitted). {@code ratingsJson} holds
 * {@code {questionId, requirementId, rating 1-4, notes}[]}.
 */
@Entity
@Table(name = "interview_scorecards")
@Getter
@Setter
@NoArgsConstructor
public class InterviewScorecard extends BaseEntity {

    @Column(name = "interview_id", nullable = false, unique = true)
    private Long interviewId;

    @Column(name = "ratings_json", nullable = false, columnDefinition = "TEXT")
    private String ratingsJson;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Recommendation recommendation;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;
}
