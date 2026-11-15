package com.recruitpro.verification.entity;

import com.recruitpro.common.entity.BaseEntity;
import com.recruitpro.evidence.entity.ClaimType;
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
 * A verdict on one claim from one source or reference (append-only, plan v2 3.1). A newer verdict for the same
 * subject and source supersedes the current one ({@code is_current = false}, status SUPERSEDED). Reasons, integrity
 * notes and the probe question come de-identified from the agent service.
 */
@Entity
@Table(name = "claim_verdicts")
@Getter
@Setter
@NoArgsConstructor
public class ClaimVerdict extends BaseEntity {

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subject_type", nullable = false, length = 20)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "claim_type", nullable = false, length = 30)
    private ClaimType claimType;

    @Column(name = "claim_value", nullable = false, length = 1000)
    private String claimValue;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "run_id")
    private Long runId;

    @Column(nullable = false, length = 40)
    private String verifier;

    @Column(name = "verifier_version", length = 40)
    private String verifierVersion;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private VerdictValue verdict;

    @Column(name = "ownership_proven", nullable = false)
    private boolean ownershipProven;

    @Column(name = "reasons_json", columnDefinition = "TEXT")
    private String reasonsJson;

    @Column(name = "integrity_notes_json", columnDefinition = "TEXT")
    private String integrityNotesJson;

    @Column(name = "evidence_item_ids_json", columnDefinition = "TEXT")
    private String evidenceItemIdsJson;

    @Column(name = "probe_question", length = 500)
    private String probeQuestion;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "candidate_status", nullable = false, length = 30)
    private CandidateVerdictStatus candidateStatus = CandidateVerdictStatus.AWAITING_CANDIDATE;

    @Column(name = "candidate_note", length = 1000)
    private String candidateNote;

    @Column(name = "candidate_responded_at")
    private LocalDateTime candidateRespondedAt;

    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    /** Subject reference as sent to the agent service: {@code fact:<id>} or {@code claim:<id>}. */
    public String subjectRef() {
        return ClaimSubjectRef.of(subjectType, subjectId);
    }
}
