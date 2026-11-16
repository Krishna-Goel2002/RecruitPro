package com.recruitpro.auth.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R1-L5: a failed JWKS refresh keeps the keys already known; refreshes stay throttled. */
class JwksKeyCacheTest {

    static final String URI = "https://idp.example/jwks";
    static final String KID = "key-1";

    /** A clock the test moves forward. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-16T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A JWKS with one RSA signing key (kid {@value #KID}). Built here so that SsoIntegrationTest's stub server is not started. */
    static String jwks(RSAPublicKey key) {
        return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + KID + "\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
                + base64Url(key.getModulus()) + "\",\"e\":\"" + base64Url(key.getPublicExponent()) + "\"}]}";
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Test
    void aFailedRefreshKeepsTheKnownKeysAndIsThrottled() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String jwks = jwks((RSAPublicKey) generator.generateKeyPair().getPublic());
        OidcHttpClient http = mock(OidcHttpClient.class);
        when(http.get(anyString())).thenReturn(jwks).thenThrow(new IllegalStateException("provider down"));
        MutableClock clock = new MutableClock();
        JwksKeyCache cache = new JwksKeyCache(http, new ObjectMapper(), clock);

        assertNotNull(cache.find(URI, KID));
        verify(http, times(1)).get(URI);

        // Past the 6-hour cache age the refresh fails: the known key is still used.
        clock.advance(JwksKeyCache.MAX_AGE.plusMinutes(1));
        assertNotNull(cache.find(URI, KID), "a provider hiccup must not drop the cached keys");
        verify(http, times(2)).get(URI);

        // An unknown kid within 30 seconds of the failed attempt does not hit the provider again.
        clock.advance(Duration.ofSeconds(10));
        assertNull(cache.find(URI, "unknown-kid"));
        assertNotNull(cache.find(URI, KID));
        verify(http, times(2)).get(URI);
    }
}
