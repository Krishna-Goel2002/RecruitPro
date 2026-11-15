package com.recruitpro.auth.mfa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** RFC 6238 Appendix B (SHA-1 seed), RFC 4648 Base32 vectors, the ±1 step window and the replay guard. */
class TotpTest {

    private static final byte[] SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void rfc6238Sha1TestVectors() {
        assertEquals("94287082", Totp.code(SEED, Totp.step(59L, 30), 8));
        assertEquals("07081804", Totp.code(SEED, Totp.step(1111111109L, 30), 8));
        assertEquals("14050471", Totp.code(SEED, Totp.step(1111111111L, 30), 8));
        assertEquals("89005924", Totp.code(SEED, Totp.step(1234567890L, 30), 8));
        assertEquals("69279037", Totp.code(SEED, Totp.step(2000000000L, 30), 8));
        assertEquals("65353130", Totp.code(SEED, Totp.step(20000000000L, 30), 8));
    }

    @Test
    void sixDigitCodesAreTheLastSixDigits() {
        assertEquals("287082", Totp.code(SEED, Totp.step(59L, 30), 6));
        assertEquals("081804", Totp.code(SEED, Totp.step(1111111109L, 30), 6));
    }

    @Test
    void acceptsOneStepOfDriftOnEitherSideAndNoMore() {
        long step = Totp.step(1234567890L, 30);
        assertEquals(step - 1, Totp.verify(SEED, Totp.code(SEED, step - 1, 6), step, 1, null, 6));
        assertEquals(step, Totp.verify(SEED, Totp.code(SEED, step, 6), step, 1, null, 6));
        assertEquals(step + 1, Totp.verify(SEED, Totp.code(SEED, step + 1, 6), step, 1, null, 6));
        assertEquals(-1, Totp.verify(SEED, Totp.code(SEED, step - 2, 6), step, 1, null, 6));
        assertEquals(-1, Totp.verify(SEED, Totp.code(SEED, step + 2, 6), step, 1, null, 6));
    }

    @Test
    void replayGuardRejectsTheSameOrAnEarlierStep() {
        long step = Totp.step(1234567890L, 30);
        String code = Totp.code(SEED, step, 6);
        assertEquals(step, Totp.verify(SEED, code, step, 1, step - 1, 6));
        assertEquals(-1, Totp.verify(SEED, code, step, 1, step, 6), "a code may be used once");
        assertEquals(-1, Totp.verify(SEED, Totp.code(SEED, step - 1, 6), step, 1, step, 6), "older steps are refused");
        assertEquals(step + 1, Totp.verify(SEED, Totp.code(SEED, step + 1, 6), step, 1, step, 6));
    }

    @Test
    void malformedCodesAreRejected() {
        long step = Totp.step(59L, 30);
        assertEquals(-1, Totp.verify(SEED, null, step, 1, null, 6));
        assertEquals(-1, Totp.verify(SEED, "12345", step, 1, null, 6));
        assertEquals(-1, Totp.verify(SEED, "12a456", step, 1, null, 6));
        assertEquals(step, Totp.verify(SEED, " 287 082 ", step, 1, null, 6), "spaces are ignored");
    }

    @Test
    void base32MatchesRfc4648() {
        assertEquals("", Base32.encode(new byte[0]));
        assertEquals("MY", Base32.encode("f".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MZXQ", Base32.encode("fo".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MZXW6", Base32.encode("foo".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MZXW6YQ", Base32.encode("foob".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MZXW6YTB", Base32.encode("fooba".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII)));
        assertArrayEquals("foobar".getBytes(StandardCharsets.US_ASCII), Base32.decode("mzxw6ytboi======"));
        assertArrayEquals(SEED, Base32.decode(Base32.encode(SEED)));
        assertEquals(32, Base32.encode(new byte[20]).length(), "a 20-byte secret is 32 Base32 characters");
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("MZ1W"));
    }

    @Test
    void recoveryCodesAreNormalised() {
        assertEquals("abcde23456", MfaService.normalizeRecoveryCode(" ABCDE-23456 "));
        assertNull(MfaService.normalizeRecoveryCode("abcde-2345"));
        assertNull(MfaService.normalizeRecoveryCode(null));
    }
}
