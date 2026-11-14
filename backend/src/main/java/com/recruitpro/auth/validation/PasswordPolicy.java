package com.recruitpro.auth.validation;

import com.recruitpro.common.exception.BadRequestException;

import java.nio.charset.StandardCharsets;

/**
 * Password policy (Section 5.2): 10-72 characters, at least one letter and one digit, and at most 72 bytes
 * in UTF-8 (the BCrypt input limit). DTOs use {@link #REGEX} with {@code @Size(min = MIN_LENGTH, max =
 * MAX_LENGTH)}; services call {@link #requireValid(String)}.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 72;
    public static final int MAX_BYTES = 72;
    /** At least one letter and one digit. */
    public static final String REGEX = "^(?=.*\\p{L})(?=.*\\p{Nd}).*$";
    public static final String MESSAGE = "Password must be 10-72 characters long and contain at least one letter and one digit";

    private PasswordPolicy() {
    }

    public static boolean isValid(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return false;
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return false;
        }
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) {
                letter = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            }
        }
        return letter && digit;
    }

    public static void requireValid(String password) {
        if (!isValid(password)) {
            throw new BadRequestException(MESSAGE);
        }
    }
}
