package com.recruitpro.auth.mfa;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** RFC 4648 Base32 (alphabet A-Z2-7) without padding, as used by authenticator apps for TOTP secrets. */
public final class Base32 {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Base32() {
    }

    public static String encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET[(buffer >> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer << (5 - bits)) & 0x1F]);
        }
        return out.toString();
    }

    /** Decodes upper- or lower-case Base32; spaces, hyphens and '=' padding are ignored. */
    public static byte[] decode(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Base32 text is required");
        }
        String normalized = text.replace(" ", "").replace("-", "").replace("=", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream(normalized.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            int value;
            if (c >= 'A' && c <= 'Z') {
                value = c - 'A';
            } else if (c >= '2' && c <= '7') {
                value = c - '2' + 26;
            } else {
                throw new IllegalArgumentException("Invalid Base32 character");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
