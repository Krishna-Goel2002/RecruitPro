package com.recruitpro.security.jwt;

import com.recruitpro.security.userdetails.CustomUserDetails;
import com.recruitpro.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and parses short-lived access tokens (15 minutes by default, D11). Tokens carry the user id
 * ({@code uid}), role ({@code role}) and organisation ({@code org}) as claims; the user row is still loaded
 * on every request so that disabling an account or changing a role takes effect immediately.
 */
@Slf4j
@Service
public class JwtService {

    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_ORGANIZATION = "org";

    private static final int MINIMUM_SECRET_BYTES = 32;

    @Value("${app.jwt.secret}")
    private String secretKey;

    @Value("${app.jwt.access-token-expiry-ms}")
    private long jwtExpiration;

    @Value("${app.jwt.issuer}")
    private String issuer;

    private volatile SecretKey signingKey;

    @PostConstruct
    void validateConfiguration() {
        if (secretKey == null || secretKey.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException("app.jwt.secret (JWT_SECRET) must be configured with at least 32 bytes");
        }
    }

    /** Access token for a user; adds the uid/role/org claims when the details carry a RecruitPro user. */
    public String generateToken(UserDetails userDetails) {
        if (userDetails instanceof CustomUserDetails custom) {
            return generateAccessToken(custom.getUser());
        }
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return buildToken(extraClaims, userDetails.getUsername(), jwtExpiration);
    }

    public String generateAccessToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        if (user.getId() != null) {
            claims.put(CLAIM_USER_ID, user.getId());
        }
        if (user.getRole() != null) {
            claims.put(CLAIM_ROLE, user.getRole().name());
        }
        if (user.getOrganizationId() != null) {
            claims.put(CLAIM_ORGANIZATION, user.getOrganizationId());
        }
        return buildToken(claims, user.getEmail(), jwtExpiration);
    }

    /** Access-token lifetime in seconds (AuthResponse.expiresInSeconds). */
    public long getAccessTokenTtlSeconds() {
        return jwtExpiration / 1000L;
    }

    /**
     * Verifies signature, issuer and expiry once and returns the claims; empty for any invalid token.
     */
    public Optional<Claims> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .requireIssuer(issuer)
                    .verifyWith(getSignInKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected invalid or expired JWT");
            return Optional.empty();
        }
    }

    public String extractUsername(String token) {
        return parse(token).map(Claims::getSubject).orElse(null);
    }

    public boolean validateToken(String authToken) {
        return parse(authToken).isPresent();
    }

    /** Reads the numeric {@code uid} claim regardless of how the JSON number was deserialised. */
    public static Long userIdClaim(Claims claims) {
        Object raw = claims.get(CLAIM_USER_ID);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String buildToken(Map<String, Object> extraClaims, String subject, long expiration) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claims(extraClaims)
                .subject(subject)
                .issuer(issuer)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + expiration))
                .signWith(getSignInKey())
                .compact();
    }

    private SecretKey getSignInKey() {
        SecretKey key = signingKey;
        if (key == null) {
            key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
            signingKey = key;
        }
        return key;
    }
}
