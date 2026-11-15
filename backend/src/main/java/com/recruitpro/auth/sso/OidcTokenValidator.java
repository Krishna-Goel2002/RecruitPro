package com.recruitpro.auth.sso;

import com.recruitpro.auth.service.TokenHashing;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.ProtectedHeader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * Validates an OIDC id_token (plan v2, Section 6.2): RS256 or ES256 signature with a key from the provider's JWKS
 * (looked up by {@code kid}), {@code iss} (Microsoft's {@code {tenantid}} template resolved from {@code tid}),
 * {@code aud} contains the client id ({@code azp} must match when several audiences), {@code exp} required and in
 * the future, {@code iat} required, not in the future and not older than {@code app.sso.max-token-age-seconds}
 * (clock skew {@code app.sso.clock-skew-seconds}), {@code nonce} whose SHA-256 matches the stored hash, a subject,
 * and a verified e-mail address ({@code email_verified=true}, or Microsoft's {@code xms_edov=true}).
 */
@Component
public class OidcTokenValidator {

    static final Set<String> ALLOWED_ALGORITHMS = Set.of("RS256", "ES256");

    private final JwksKeyCache keys;
    private final SsoProperties properties;

    public OidcTokenValidator(JwksKeyCache keys, SsoProperties properties) {
        this.keys = keys;
        this.properties = properties;
    }

    public VerifiedIdentity validate(SsoProviderRegistry.ResolvedProvider provider, String idToken, String expectedNonceHash) {
        if (idToken == null || idToken.isBlank()) {
            throw new SsoException("invalid_token", "The provider returned no id_token");
        }
        long skew = Math.max(0, properties.getClockSkewSeconds());
        Claims claims;
        try {
            claims = Jwts.parser()
                    .keyLocator(header -> locate(provider, header))
                    .clockSkewSeconds(skew)
                    .requireAudience(provider.clientId())
                    .build()
                    .parseSignedClaims(idToken.trim())
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new SsoException("invalid_token", "The id_token could not be validated", ex);
        }
        String expectedIssuer = provider.issuer();
        if (expectedIssuer != null && expectedIssuer.contains("{tenantid}")) {
            String tenant = claims.get("tid", String.class);
            if (tenant == null || tenant.isBlank()) {
                throw new SsoException("invalid_token", "The id_token has no tenant id");
            }
            expectedIssuer = expectedIssuer.replace("{tenantid}", tenant);
        }
        if (expectedIssuer == null || !expectedIssuer.equals(claims.getIssuer())) {
            throw new SsoException("invalid_token", "The id_token was issued by an unexpected issuer");
        }
        Set<String> audience = claims.getAudience();
        if (audience != null && audience.size() > 1) {
            String azp = claims.get("azp", String.class);
            if (azp == null || !azp.equals(provider.clientId())) {
                throw new SsoException("invalid_token", "The id_token was issued to another client");
            }
        }
        Instant now = Instant.now();
        Date expiration = claims.getExpiration();
        if (expiration == null) {
            throw new SsoException("invalid_token", "The id_token has no expiry");
        }
        Date issuedAt = claims.getIssuedAt();
        if (issuedAt == null || issuedAt.toInstant().isAfter(now.plusSeconds(skew))
                || issuedAt.toInstant().isBefore(now.minusSeconds(Math.max(60, properties.getMaxTokenAgeSeconds()) + skew))) {
            throw new SsoException("invalid_token", "The id_token issue time is not acceptable");
        }
        String nonce = claims.get("nonce", String.class);
        if (nonce == null || expectedNonceHash == null || !MessageDigest.isEqual(
                TokenHashing.sha256(nonce).getBytes(StandardCharsets.US_ASCII),
                expectedNonceHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new SsoException("invalid_token", "The id_token nonce does not match");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw new SsoException("invalid_token", "The id_token has no subject");
        }
        String email = claims.get("email", String.class);
        if (email == null || email.isBlank() || !email.contains("@") || email.length() > 255) {
            throw new SsoException("email_not_verified", "The provider did not return an e-mail address");
        }
        if (!isTrue(claims.get("email_verified")) && !isTrue(claims.get("xms_edov"))) {
            throw new SsoException("email_not_verified", "The provider has not verified the e-mail address");
        }
        return new VerifiedIdentity(subject, email.trim().toLowerCase(Locale.ROOT));
    }

    private Key locate(SsoProviderRegistry.ResolvedProvider provider, Header header) {
        String algorithm = header == null ? null : header.getAlgorithm();
        if (algorithm == null || !ALLOWED_ALGORITHMS.contains(algorithm)) {
            throw new MalformedJwtException("Signature algorithm not allowed");
        }
        String kid = header instanceof ProtectedHeader protectedHeader ? protectedHeader.getKeyId() : null;
        Key key = keys.find(provider.jwksUri(), kid);
        if (key == null) {
            throw new MalformedJwtException("Unknown signing key");
        }
        return key;
    }

    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof String text && "true".equalsIgnoreCase(text.trim()));
    }

    /** The provider's subject and the verified, lower-case e-mail address. */
    public record VerifiedIdentity(String subject, String email) {
    }
}
