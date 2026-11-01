package com.recruitpro.hr.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "jobs")
@Getter @Setter @NoArgsConstructor
public class Job extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private HRProfile createdBy;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 100)
    private String department;

    @Column(nullable = false, length = 255)
    private String location;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "job_type", nullable = false, length = 30)
    private JobType jobType;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "experience_level", nullable = false, length = 30)
    private ExperienceLevel experienceLevel;

    @Column(name = "minimum_salary", precision = 12, scale = 2)
    private BigDecimal minimumSalary;

    @Column(name = "maximum_salary", precision = 12, scale = 2)
    private BigDecimal maximumSalary;

    @Column(nullable = false)
    private int vacancies = 1;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 30)
    private JobStatus status = JobStatus.DRAFT;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    private LocalDateTime deadline;

    public void transitionTo(JobStatus target) {
        boolean allowed = switch (status) {
            case DRAFT -> target == JobStatus.PUBLISHED || target == JobStatus.CANCELLED;
            case PUBLISHED -> target == JobStatus.ON_HOLD || target == JobStatus.CLOSED;
            case ON_HOLD -> target == JobStatus.PUBLISHED || target == JobStatus.CLOSED;
            case CLOSED, CANCELLED -> false;
        };
        if (!allowed) throw new IllegalArgumentException("Job status cannot transition from " + status + " to " + target);
        status = target;
        if (target == JobStatus.PUBLISHED && publishedAt == null) publishedAt = LocalDateTime.now();
    }
}
