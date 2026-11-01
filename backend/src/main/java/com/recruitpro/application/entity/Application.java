package com.recruitpro.application.entity;

import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.common.entity.BaseEntity;
import com.recruitpro.hr.entity.Job;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "applications", uniqueConstraints = @UniqueConstraint(name = "uk_applications_candidate_job", columnNames = {"candidate_id", "job_id"}))
@Getter @Setter @NoArgsConstructor
public class Application extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private CandidateProfile candidate;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resume_id", nullable = false)
    private Resume resume;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 30)
    private ApplicationStatus status = ApplicationStatus.SUBMITTED;
    @Column(name = "cover_letter", columnDefinition = "TEXT") private String coverLetter;
    @Column(name = "review_comment", columnDefinition = "TEXT") private String reviewComment;
    @Column(name = "applied_at", nullable = false) private LocalDateTime appliedAt;
}
