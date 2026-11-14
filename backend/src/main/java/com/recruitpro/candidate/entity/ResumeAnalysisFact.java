package com.recruitpro.candidate.entity;

import com.recruitpro.common.entity.BaseEntity;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.user.entity.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "resume_analysis_facts")
@Getter
@Setter
@NoArgsConstructor
public class ResumeAnalysisFact extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_id", nullable = false)
    private ResumeAnalysis analysis;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "fact_type", nullable = false, length = 30)
    private ResumeFactType type;

    @Column(name = "extracted_value", nullable = false, length = 1000)
    private String extractedValue;

    @Column(name = "normalized_value", nullable = false, length = 1000)
    private String normalizedValue;

    @Column(name = "evidence_text", nullable = false, length = 1000)
    private String evidence;

    @Column(name = "source_section", nullable = false, length = 80)
    private String sourceSection;

    @Column(name = "source_reference", length = 80)
    private String sourceReference;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "review_status", nullable = false, length = 20)
    private ResumeFactReviewStatus reviewStatus = ResumeFactReviewStatus.UNREVIEWED;

    @Column(name = "reviewed_value", length = 1000)
    private String reviewedValue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_user_id")
    private User reviewer;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /**
     * Evidence tier of the extracted fact; always RESUME_STATED for parser output. Candidate corrections never
     * change it: they become SELF_CLAIMED claims linked by {@code candidate_claims.source_fact_id} (C1).
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "evidence_tier", nullable = false, length = 30)
    private EvidenceTier evidenceTier = EvidenceTier.RESUME_STATED;

    /** Offsets of the evidence in the normalised document text (parser v2). */
    @Column(name = "span_start")
    private Integer spanStart;

    @Column(name = "span_end")
    private Integer spanEnd;

    /** Parser attributes (JSON object), e.g. skillId, domain, title, employer, startDate, months, degreeLevel. */
    @Column(name = "attributes_json", columnDefinition = "TEXT")
    private String attributesJson;
}
