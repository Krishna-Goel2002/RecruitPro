package com.recruitpro.auth.mfa;

import com.recruitpro.common.time.UtcTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A staff user's TOTP secret (encrypted with SecretCipher, context {@code mfa:<userId>}). {@code enabledAt} is null
 * while the enrolment waits for its first code; {@code lastUsedStep} is the replay guard.
 */
@Entity
@Table(name = "user_mfa")
@Getter
@Setter
@NoArgsConstructor
public class UserMfa {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "secret_enc", nullable = false, length = 500)
    private String secretEnc;

    @Column(name = "enabled_at")
    private LocalDateTime enabledAt;

    @Column(name = "last_used_step")
    private Long lastUsedStep;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isEnabled() {
        return enabledAt != null;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = UtcTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = UtcTime.now();
    }
}
