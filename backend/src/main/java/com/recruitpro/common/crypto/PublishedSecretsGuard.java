package com.recruitpro.common.crypto;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Refuses to start with a secret whose value has been published (security incident of 17 Nov 2026).
 *
 * <p>Earlier commits shipped development defaults for the encryption key, the JWT signing secret and the internal
 * service tokens in {@code docker-compose.yml}, the CI workflow, tests and examples. Those values are public in the
 * Git history, so they must never protect real data. This guard compares SHA-256 hashes of the configured values
 * (the published values themselves are not repeated here) and stops the application when one is used. Generate fresh
 * values with {@code tools/generate_env.py} or {@code tools/generate-env.ps1}.</p>
 *
 * <p>The {@code test} profile is exempt: unit tests run against an in-memory database with throw-away keys.</p>
 */
@Component
public class PublishedSecretsGuard implements InitializingBean {

    /** SHA-256 (hex) of every secret value that has appeared in the repository or its examples. */
    static final Set<String> PUBLISHED_SHA256 = Set.of(
            "d1ecc4a6d4b33a342286020bfef9bf5d5a66a648a14efccb149f75e4b4f15a06", // compose dev encryption key
            "432ce9e55570f9bb2b913986fe91333771ea0d72e26f8b28530ca53522d27803", // CI encryption key
            "2cf5e6ec387461b4bf954f587ad4d957753fcbc48bf892b5e49996b90cf3b476", // former test encryption key
            "51643eac9777b63a7b268174d1fd4276daedec9bc9ea0bc6e5abf69047bc54f6", // all-zero test encryption key
            "4d87bd614d40ff7b8c06a317a1dc390b16a94e18bd7ec9b898125a2313568718", // compose dev JWT secret
            "4be64ff12b37e052459b947e7a4103e523f4a60c96e092cab281dbb13d4ef63d", // CI JWT secret
            "a0b83ba1fa77764b7d0e64808df84d32727ceee981421c07cd0a1e0bcdd2ad6e", // test-profile JWT secret
            "597c069b0ad74c2a1023941f12539446d3726a2bf8899854519782f2d44942d3", // compose dev AI-service token
            "2fa1faee3a707f0be22c198c3193edb9dba756bdbc5877b4d6ed8bcf7c4dd200", // compose dev agent-service token
            "5a92543aaf0b4adcd3d2cec4faf715daab501b536869c3d2288a2460332e0eb9", // .env.example placeholder
            "1e94131608cf8bde81510a02deae57e2b34c27e2bc844ceaf8851a89205a8e16", // .env.example placeholder
            "be47c8e96320c1f4fa8e22ff9c93968d141f539ff091a45af0adde67ad1654ae"  // README example agent token
    );

    private static final List<String[]> CHECKED = List.of(
            new String[] {"app.encryption.key", "APP_ENCRYPTION_KEY"},
            new String[] {"app.jwt.secret", "JWT_SECRET"},
            new String[] {"app.ai-service.service-token", "AI_SERVICE_TOKEN"},
            new String[] {"app.agent-service.service-token", "AGENT_SERVICE_TOKEN"});

    private final Environment environment;

    public PublishedSecretsGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (environment.acceptsProfiles(Profiles.of("test"))) {
            return;
        }
        List<String> published = new ArrayList<>();
        for (String[] property : CHECKED) {
            String value = environment.getProperty(property[0]);
            if (isPublished(value)) {
                published.add(property[1]);
            }
        }
        if (!published.isEmpty()) {
            throw new IllegalStateException("Refusing to start: " + String.join(", ", published)
                    + " use a value that has been published in the repository. Generate new secrets with "
                    + "tools/generate_env.py (or tools/generate-env.ps1 on Windows) and restart.");
        }
    }

    /** True when the value (trimmed) is one of the published secrets. */
    public static boolean isPublished(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return PUBLISHED_SHA256.contains(sha256(value.trim()));
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
