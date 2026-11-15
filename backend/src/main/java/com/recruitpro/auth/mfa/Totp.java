package com.recruitpro.auth.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * RFC 6238 TOTP with HMAC-SHA1 (RFC 4226 dynamic truncation), 30-second steps and 6 digits by default.
 * Pure functions; the replay guard (last accepted step) is kept by the caller.
 */
public final class Totp {

    public static final int DEFAULT_DIGITS = 6;
    public static final long DEFAULT_PERIOD_SECONDS = 30;
    /** Accepted clock drift in steps on either side of the current step. */
    public static final int DEFAULT_WINDOW = 1;

    private static final int[] POWERS = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};

    private Totp() {
    }

    /** Time step (counter) for a Unix time in seconds. */
    public static long step(long epochSeconds, long periodSeconds) {
        return Math.floorDiv(epochSeconds, periodSeconds);
    }

    /** The {@code digits}-digit code of {@code counter} (zero-padded). */
    public static String code(byte[] secret, long counter, int digits) {
        if (digits < 1 || digits > 8) {
            throw new IllegalArgumentException("digits must be between 1 and 8");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % POWERS[digits];
            StringBuilder text = new StringBuilder(Integer.toString(otp));
            while (text.length() < digits) {
                text.insert(0, '0');
            }
            return text.toString();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA1 is unavailable", ex);
        }
    }

    /**
     * Finds the step within {@code currentStep ± window} whose code equals {@code submitted} and that is later than
     * {@code lastUsedStep} (replay guard; null = never used). Returns the matching step, or -1.
     */
    public static long verify(byte[] secret, String submitted, long currentStep, int window, Long lastUsedStep, int digits) {
        if (submitted == null) {
            return -1;
        }
        String code = submitted.replace(" ", "").trim();
        if (code.length() != digits || !code.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        long matched = -1;
        for (long candidate = currentStep - window; candidate <= currentStep + window; candidate++) {
            if (candidate < 0) {
                continue;
            }
            // Compare every candidate in constant time; no early exit on the first match.
            boolean equal = MessageDigest.isEqual(code(secret, candidate, digits).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII));
            if (equal && (lastUsedStep == null || candidate > lastUsedStep) && candidate > matched) {
                matched = candidate;
            }
        }
        return matched;
    }
}
