package com.recruitpro.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts small secrets at rest (OAuth access tokens of connected verification sources, TOTP secrets) with
 * AES-256-GCM (plan v2, Section 3.4).
 *
 * <p>The key is {@code app.encryption.key}: 32 random bytes, Base64 encoded (generate with
 * {@code openssl rand -base64 32}). Without a key {@link #isConfigured()} is false and every encrypt/decrypt call
 * throws {@link IllegalStateException}; features that need it (GitHub OAuth, staff MFA) report themselves as not
 * configured instead of storing plaintext.</p>
 *
 * <p>Format: {@code v1:} + Base64(12-byte IV || ciphertext || 16-byte tag). The optional {@code context} is bound as
 * associated data, so a ciphertext copied to another row or purpose fails to decrypt.</p>
 */
@Component
public class SecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.encryption.key:}") String base64Key) {
        this.key = parseKey(base64Key);
    }

    static SecretKeySpec parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("app.encryption.key must be Base64 encoded", ex);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("app.encryption.key must decode to exactly 32 bytes (AES-256)");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plaintext, String context) {
        requireKey();
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            if (context != null) {
                cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            }
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length)
                    .put(iv).put(sealed).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Encryption failed", ex);
        }
    }

    public String decrypt(String ciphertext, String context) {
        requireKey();
        if (ciphertext == null) {
            return null;
        }
        if (!ciphertext.startsWith(PREFIX)) {
            throw new IllegalStateException("Unknown ciphertext format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            if (all.length <= IV_BYTES) {
                throw new IllegalStateException("Ciphertext too short");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            if (context != null) {
                cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            }
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Decryption failed", ex);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("app.encryption.key is not configured");
        }
    }
}
