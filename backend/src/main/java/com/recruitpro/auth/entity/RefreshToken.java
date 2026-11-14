package com.recruitpro.auth.entity;

import com.recruitpro.common.entity.BaseEntity;
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

    @PrePersist
    void assignFamilyIfMissing() {
        if (familyId == null || familyId.isBlank()) {
            familyId = UUID.randomUUID().toString();
        }
    }
}
