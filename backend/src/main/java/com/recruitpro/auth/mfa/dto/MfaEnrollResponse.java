package com.recruitpro.auth.mfa.dto;

/** The new TOTP secret (Base32, shown once) and the {@code otpauth://totp/...} URI for a QR code. */
public record MfaEnrollResponse(String secret, String otpauthUri) {
}
