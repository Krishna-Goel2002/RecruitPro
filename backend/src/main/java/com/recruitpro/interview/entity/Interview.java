package com.recruitpro.interview.entity;

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

import java.time.LocalDateTime;

/** A scheduled interview of one application with one interviewer (an enabled staff user of the job's organisation). */
@Entity
@Table(name = "interviews")
@Getter
@Setter
@NoArgsConstructor
public class Interview extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @Column(name = "interviewer_user_id", nullable = false)
    private Long interviewerUserId;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(length = 500)
    private String location;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private InterviewStatus status = InterviewStatus.SCHEDULED;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    /** ICS SEQUENCE of the calendar event; incremented on every reschedule and on cancellation (WP-PLAT). */
    @Column(name = "ics_sequence", nullable = false)
    private int icsSequence = 0;
}
