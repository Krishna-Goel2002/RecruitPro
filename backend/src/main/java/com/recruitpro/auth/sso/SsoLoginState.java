package com.recruitpro.auth.sso;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A pending OIDC authorisation request (plan v2, Section 6.2): SHA-256 hashes of state and nonce, the PKCE verifier
 * encrypted with SecretCipher (context {@code sso:<stateHash>}), single use, 10 minutes.
 */
@Entity
@Table(name = "sso_login_states")
@Getter
@Setter
@NoArgsConstructor
public class SsoLoginState extends BaseEntity {

    @Column(name = "state_hash", nullable = false, unique = true, length = 64)
    private String stateHash;

    @Column(name = "nonce_hash", nullable = false, length = 64)
    private String nonceHash;

    @Column(name = "code_verifier_enc", nullable = false, length = 500)
    private String codeVerifierEnc;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 20)
    private String portal;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;
}
