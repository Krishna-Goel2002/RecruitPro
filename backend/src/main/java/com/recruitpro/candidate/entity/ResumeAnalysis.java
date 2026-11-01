package com.recruitpro.candidate.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "resume_analyses", uniqueConstraints = @UniqueConstraint(name = "uk_resume_analyses_resume", columnNames = "resume_id"))
@Getter
@Setter
@NoArgsConstructor
public class ResumeAnalysis extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resume_id", nullable = false)
    private Resume resume;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private ResumeAnalysisStatus status;

    @Column(name = "parser_version", length = 80)
    private String parserVersion;

    @Column(name = "source_sha256", length = 64)
    private String sourceSha256;

    @Column(name = "error_code", length = 60)
    private String errorCode;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
