package com.recruitpro.hr.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.recruitpro.common.time.UtcTime;
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

    /** Owning organisation (D3). Every staff query is scoped by this column. */
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

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

    /** Incremented by every requirement change on a PUBLISHED or ON_HOLD job; match runs record it. */
    @Column(name = "requirements_version", nullable = false)
    private int requirementsVersion = 1;

    /** Why the job was closed: DEADLINE_PASSED (scheduled closer), CLOSED_BY_HR or ADMIN_OVERRIDE. */
    @Column(name = "closed_reason", length = 60)
    private String closedReason;

    /** Requirements may change in these statuses (plan 3.4); CLOSED and CANCELLED are terminal. */
    public boolean isEditable() {
        return status == JobStatus.DRAFT || status == JobStatus.PUBLISHED || status == JobStatus.ON_HOLD;
    }

    /** Candidates may see and apply to the job: PUBLISHED with no deadline or a deadline in the future. */
    public boolean isOpenForApplications(LocalDateTime now) {
        return status == JobStatus.PUBLISHED && (deadline == null || deadline.isAfter(now));
    }

    public void transitionTo(JobStatus target) {
        boolean allowed = switch (status) {
            case DRAFT -> target == JobStatus.PUBLISHED || target == JobStatus.CANCELLED;
            case PUBLISHED -> target == JobStatus.ON_HOLD || target == JobStatus.CLOSED;
            case ON_HOLD -> target == JobStatus.PUBLISHED || target == JobStatus.CLOSED;
            case CLOSED, CANCELLED -> false;
        };
        if (!allowed) throw new IllegalArgumentException("Job status cannot transition from " + status + " to " + target);
        status = target;
        if (target == JobStatus.PUBLISHED && publishedAt == null) publishedAt = UtcTime.now();
    }
}
