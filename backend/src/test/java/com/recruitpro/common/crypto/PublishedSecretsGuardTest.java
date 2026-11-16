package com.recruitpro.common.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The published development secrets are refused outside the test profile (security incident of 17 Nov 2026). */
class PublishedSecretsGuardTest {

    // Published values are rebuilt from fragments so this file does not contain them verbatim.
    private static final String PUBLISHED_DEV_KEY = "ZGV2LW9ubHktcmVjcnVpdHBy" + "by1lbmNyeXB0LWtleSE=";
    private static final String PUBLISHED_DEV_JWT = "dev-only-jwt-secret-" + "change-me-0123456789abcdef";

    @Test
    void publishedValuesAreRecognisedAndFreshOnesAreNot() {
        assertTrue(PublishedSecretsGuard.isPublished(PUBLISHED_DEV_KEY));
        assertTrue(PublishedSecretsGuard.isPublished(" " + PUBLISHED_DEV_JWT + " "));
        assertFalse(PublishedSecretsGuard.isPublished(freshKey()));
        assertFalse(PublishedSecretsGuard.isPublished(""));
        assertFalse(PublishedSecretsGuard.isPublished(null));
    }

    @Test
    void startupFailsWithAPublishedSecretOutsideTheTestProfile() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.encryption.key", PUBLISHED_DEV_KEY)
                .withProperty("app.jwt.secret", PUBLISHED_DEV_JWT);
        environment.setActiveProfiles("dev");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new PublishedSecretsGuard(environment).afterPropertiesSet());
        assertTrue(failure.getMessage().contains("APP_ENCRYPTION_KEY"));
        assertTrue(failure.getMessage().contains("JWT_SECRET"));
        assertFalse(failure.getMessage().contains(PUBLISHED_DEV_KEY), "the value itself is never logged");
    }

    @Test
    void freshSecretsAndTheTestProfilePass() {
        MockEnvironment fresh = new MockEnvironment()
                .withProperty("app.encryption.key", freshKey())
                .withProperty("app.jwt.secret", freshKey() + freshKey());
        fresh.setActiveProfiles("prod");
        assertDoesNotThrow(() -> new PublishedSecretsGuard(fresh).afterPropertiesSet());

        MockEnvironment test = new MockEnvironment().withProperty("app.encryption.key", PUBLISHED_DEV_KEY);
        test.setActiveProfiles("test");
        assertDoesNotThrow(() -> new PublishedSecretsGuard(test).afterPropertiesSet());
    }

    private static String freshKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
