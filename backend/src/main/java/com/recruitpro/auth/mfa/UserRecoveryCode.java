package com.recruitpro.auth.mfa;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** One single-use MFA recovery code; only the password-encoder hash is stored. */
@Entity
@Table(name = "user_recovery_codes")
@Getter
@Setter
@NoArgsConstructor
public class UserRecoveryCode extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(name = "used_at")
    private LocalDateTime usedAt;
}
