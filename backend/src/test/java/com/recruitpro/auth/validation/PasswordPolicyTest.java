package com.recruitpro.auth.validation;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R6-B7: the service check and the DTO pattern agree, also for letters outside the Basic Multilingual Plane. */
class PasswordPolicyTest {

    private static final Pattern DTO = Pattern.compile(PasswordPolicy.REGEX);

    @Test
    void anAstralLetterCountsAsALetterInBothChecks() {
        // U+1D400 MATHEMATICAL BOLD CAPITAL A is a letter (category Lu) encoded as a surrogate pair.
        String password = new String(Character.toChars(0x1D400)) + "123456789";
        assertTrue(DTO.matcher(password).matches());
        assertTrue(PasswordPolicy.isValid(password), "used to fail: each surrogate half alone is not a letter");
    }

    @Test
    void bothChecksAgreeOnOrdinaryPasswords() {
        for (String password : new String[] {"SecurePassword123", "onlyletters", "1234567890", "Ünïcödé1234", "x1"}) {
            boolean dto = DTO.matcher(password).matches() && password.length() >= PasswordPolicy.MIN_LENGTH
                    && password.length() <= PasswordPolicy.MAX_LENGTH;
            assertEquals(dto, PasswordPolicy.isValid(password), password);
        }
        assertFalse(PasswordPolicy.isValid(null));
    }
}
