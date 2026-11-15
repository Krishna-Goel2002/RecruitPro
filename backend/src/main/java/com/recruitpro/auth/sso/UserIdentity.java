package com.recruitpro.auth.sso;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** An external OIDC identity ({@code provider} + {@code sub}) linked to an existing staff account. */
@Entity
@Table(name = "user_identities")
@Getter
@Setter
@NoArgsConstructor
public class UserIdentity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(length = 255)
    private String email;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;
}
