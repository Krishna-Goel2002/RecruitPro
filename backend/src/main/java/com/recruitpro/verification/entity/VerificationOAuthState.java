package com.recruitpro.verification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * OAuth {@code state} of a pending connection: SHA-256 hex of 32 random bytes, 10-minute expiry, single use, bound
 * to the starting browser (hashed cookie nonce) and carrying the encrypted PKCE verifier.
 */
@Entity
@Table(name = "verification_oauth_states")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class VerificationOAuthState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "state_hash", nullable = false, length = 64, unique = true)
    private String stateHash;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /** SHA-256 hex of the browser-binding nonce kept in the httpOnly cookie set by {@code start} (V25). */
    @Column(name = "browser_binding_hash", length = 64)
    private String browserBindingHash;

    /** PKCE (S256) code verifier, encrypted with SecretCipher, context {@code voauth:<stateHash>} (V25). */
    @Column(name = "code_verifier_enc", length = 500)
    private String codeVerifierEnc;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
