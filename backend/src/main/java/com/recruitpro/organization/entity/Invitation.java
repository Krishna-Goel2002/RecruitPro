package com.recruitpro.organization.entity;

import com.recruitpro.common.entity.BaseEntity;
import com.recruitpro.user.entity.Role;
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

/** Invitation of a staff member into an organisation (7-day single-use token; only the hash is stored). */
@Entity
@Table(name = "invitations")
@Getter
@Setter
@NoArgsConstructor
public class Invitation extends BaseEntity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private Role role;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "invited_by_user_id")
    private Long invitedByUserId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public boolean isPending(LocalDateTime now) {
        return acceptedAt == null && revokedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }

    /** PENDING, ACCEPTED, REVOKED or EXPIRED. */
    public String statusAt(LocalDateTime now) {
        if (acceptedAt != null) {
            return "ACCEPTED";
        }
        if (revokedAt != null) {
            return "REVOKED";
        }
        return expiresAt != null && expiresAt.isAfter(now) ? "PENDING" : "EXPIRED";
    }
}
