package com.recruitpro.auth.mfa;

import com.recruitpro.auth.entity.UserToken;
import com.recruitpro.auth.entity.UserTokenPurpose;
import com.recruitpro.auth.repository.UserTokenRepository;
import com.recruitpro.auth.service.TokenHashing;
import com.recruitpro.auth.service.UserTokenService;
import com.recruitpro.common.exception.UnauthorizedException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * The {@code mfaToken} of the two-step staff sign-in: an HS256 JWT (issuer {@value #ISSUER}, key derived from
 * {@code app.jwt.secret} with a purpose label, so it can never pass as an access token) whose {@code jti} is a
 * one-time token stored hashed in {@code user_tokens} (purpose MFA_LOGIN or MFA_ENROLL, 5 minutes). The signature
 * and expiry are checked first, then the stored row (unused, unexpired), which makes the token single use.
 */
@Component
public class MfaTokenService {

    public static final Duration TTL = Duration.ofMinutes(5);
    static final String ISSUER = "recruitpro-mfa";
    static final String CLAIM_PURPOSE = "pur";
    static final String EXPIRED_MESSAGE = "This sign-in step has expired. Please sign in again.";

    private final UserTokenService userTokenService;
    private final UserTokenRepository userTokenRepository;
    private final SecretKey key;

    public MfaTokenService(UserTokenService userTokenService, UserTokenRepository userTokenRepository,
                           @Value("${app.jwt.secret}") String jwtSecret) {
        this.userTokenService = userTokenService;
        this.userTokenRepository = userTokenRepository;
        this.key = deriveKey(jwtSecret);
    }

    static SecretKey deriveKey(String jwtSecret) {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("app.jwt.secret is required");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] derived = mac.doFinal("recruitpro-mfa-token-v1".getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(derived);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is unavailable", ex);
        }
    }

    /** Issues a new token for the user; earlier unused tokens of the same purpose stop working. */
    public String issue(User user, UserTokenPurpose purpose) {
        String jti = userTokenService.issue(user, purpose, TTL);
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(user.getId()))
                .id(jti)
                .claim(CLAIM_PURPOSE, purpose.name())
                .issuedAt(new Date(now))
                .expiration(new Date(now + TTL.toMillis()))
                .signWith(key)
                .compact();
    }

    /**
     * The user of a valid, unused token of the purpose (row locked); {@code consume} marks it used. Must run inside a
     * transaction. Any problem is 401 with one generic message.
     */
    public User resolve(String mfaToken, UserTokenPurpose purpose, boolean consume) {
        UserToken token = lock(mfaToken, purpose);
        if (consume) {
            token.setUsedAt(UtcTime.now());
        }
        return token.getUser();
    }

    /**
     * The stored row of a valid, unused token (locked, user fetched). Must run inside a transaction; mark it used
     * with {@code setUsedAt} once the step succeeded.
     */
    public UserToken lock(String mfaToken, UserTokenPurpose purpose) {
        String jti = parseJti(mfaToken, purpose);
        UserToken token = userTokenRepository.findForUpdate(TokenHashing.sha256(jti), purpose)
                .orElseThrow(() -> new UnauthorizedException(EXPIRED_MESSAGE));
        LocalDateTime now = UtcTime.now();
        if (token.getUsedAt() != null || !token.getExpiresAt().isAfter(now)) {
            throw new UnauthorizedException(EXPIRED_MESSAGE);
        }
        return token;
    }

    private String parseJti(String mfaToken, UserTokenPurpose purpose) {
        if (mfaToken == null || mfaToken.isBlank() || mfaToken.length() > 2048) {
            throw new UnauthorizedException(EXPIRED_MESSAGE);
        }
        try {
            Claims claims = Jwts.parser()
                    .requireIssuer(ISSUER)
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(mfaToken.trim())
                    .getPayload();
            String jti = claims.getId();
            if (jti == null || jti.isBlank() || !purpose.name().equals(claims.get(CLAIM_PURPOSE, String.class))) {
                throw new UnauthorizedException(EXPIRED_MESSAGE);
            }
            return jti;
        } catch (JwtException | IllegalArgumentException ex) {
            throw new UnauthorizedException(EXPIRED_MESSAGE);
        }
    }
}
