package com.recruitpro.verification.entity;

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
 * A reference request (plan v2, 3.2). The referee opens {@code <candidate-url>/reference/<token>}; only the SHA-256
 * of the token is stored. {@code claim_refs_json} keeps the claims as they were when the request was sent
 * ({@code [{subjectType, subjectId, claimType, value}]}).
 */
@Entity
@Table(name = "verification_reference_requests")
@Getter
@Setter
@NoArgsConstructor
public class VerificationReferenceRequest extends BaseEntity {

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Column(name = "referee_name", nullable = false, length = 200)
    private String refereeName;

    @Column(name = "referee_email", nullable = false, length = 255)
    private String refereeEmail;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ReferenceRelationship relationship;

    @Column(name = "employer_name", length = 200)
    private String employerName;

    @Column(name = "employer_domain", length = 253)
    private String employerDomain;

    @Column(name = "claim_refs_json", nullable = false, columnDefinition = "TEXT")
    private String claimRefsJson;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ReferenceStatus status = ReferenceStatus.SENT;

    @Column(name = "answers_json", columnDefinition = "TEXT")
    private String answersJson;

    @Column(name = "referee_comment", length = 2000)
    private String refereeComment;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    /** When the candidate consented to contacting the referee (request creation). */
    @Column(name = "consented_at", nullable = false)
    private LocalDateTime consentedAt;

    /** When the VERIFY_REFERENCE job stored the verdicts of the answer. */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "error_code", length = 60)
    private String errorCode;
}
