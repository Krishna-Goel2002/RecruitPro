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
 * A source the candidate connected for verification (plan v2, 3.1). The access token is encrypted with
 * {@code SecretCipher} under the context {@code vsrc:<id>}; revoking a source clears the token, the file and every
 * evidence item, and withdraws its verdicts. The row itself is kept (with {@code revoked_at}) for the run history.
 */
@Entity
@Table(name = "verification_sources")
@Getter
@Setter
@NoArgsConstructor
public class VerificationSource extends BaseEntity {

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private VerificationSourceKind kind;

    /** Login (GITHUB, GITLAB), URL (WEBSITE, OPEN_BADGE), file name (DOCUMENT) or {@code credential:<hash>}. */
    @Column(nullable = false, length = 500)
    private String identifier;

    @Column(name = "display_label", nullable = false, length = 300)
    private String displayLabel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "ownership_status", nullable = false, length = 20)
    private OwnershipStatus ownershipStatus = OwnershipStatus.UNPROVEN;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "ownership_method", length = 30)
    private OwnershipMethod ownershipMethod;

    @Column(name = "ownership_checked_at")
    private LocalDateTime ownershipCheckedAt;

    /** Outcome text of the last ownership check (from the agent service). */
    @Column(name = "ownership_detail", length = 500)
    private String ownershipDetail;

    @Column(name = "challenge_code", nullable = false, length = 64)
    private String challengeCode;

    @Column(name = "access_token_enc", columnDefinition = "TEXT")
    private String accessTokenEnc;

    @Column(name = "token_scopes", length = 200)
    private String tokenScopes;

    /** Storage key of an uploaded document. */
    @Column(name = "file_path", length = 500)
    private String filePath;

    /** A pasted Open Badge credential (JWT or JSON); null for hosted assertions. */
    @Column(name = "credential_text", columnDefinition = "TEXT")
    private String credentialText;

    @Column(name = "consent_text_version", nullable = false, length = 40)
    private String consentTextVersion;

    @Column(name = "consented_at", nullable = false)
    private LocalDateTime consentedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isOwnershipProven() {
        return ownershipStatus == OwnershipStatus.PROVEN;
    }
}
