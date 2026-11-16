package com.recruitpro.auth.entity;

import com.recruitpro.common.entity.BaseEntity;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Refresh token (hash only). Tokens of one sign-in session share a {@code familyId}; rotation revokes the
 * presented token ({@code revokedReason = ROTATED}, {@code replacedAt}) and issues the next one in the family.
 * {@code familyStartedAt} bounds the family's absolute lifetime.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "replaced_at")
    private LocalDateTime replacedAt;

    @Column(name = "revoked_reason", length = 30)
    private String revokedReason;

    /**
     * When the sign-in that started this family happened (R1-U2, V28); copied to every rotated token so the family
     * ends after the absolute session lifetime however often it is rotated.
     */
    @Column(name = "family_started_at", nullable = false)
    private LocalDateTime familyStartedAt;

    @PrePersist
    void assignFamilyIfMissing() {
        if (familyId == null || familyId.isBlank()) {
            familyId = UUID.randomUUID().toString();
        }
        if (familyStartedAt == null) {
            familyStartedAt = UtcTime.now();
        }
    }
}
