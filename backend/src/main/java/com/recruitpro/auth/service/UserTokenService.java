package com.recruitpro.auth.service;

import com.recruitpro.auth.entity.UserToken;
import com.recruitpro.auth.entity.UserTokenPurpose;
import com.recruitpro.auth.repository.UserTokenRepository;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/** One-time tokens for email verification and password reset (hash stored, single use, expiring). */
@Service
@RequiredArgsConstructor
public class UserTokenService {

    static final String INVALID_LINK = "This link is invalid or has expired";

    private final UserTokenRepository repository;

    /** Issues a new token; earlier unused tokens of the same purpose stop working. Returns the raw token. */
    @Transactional
    public String issue(User user, UserTokenPurpose purpose, Duration ttl) {
        LocalDateTime now = UtcTime.now();
        repository.invalidateOutstanding(user.getId(), purpose, now);
        String raw = TokenHashing.newToken();
        UserToken token = new UserToken();
        token.setUser(user);
        token.setPurpose(purpose);
        token.setTokenHash(TokenHashing.sha256(raw));
        token.setExpiresAt(now.plus(ttl));
        repository.save(token);
        return raw;
    }

    /** Consumes a token and returns its (managed) user; 400 when unknown, used or expired. */
    @Transactional
    public User consume(String rawToken, UserTokenPurpose purpose) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 256) {
            throw new BadRequestException(INVALID_LINK);
        }
        UserToken token = repository.findForUpdate(TokenHashing.sha256(rawToken.trim()), purpose)
                .orElseThrow(() -> new BadRequestException(INVALID_LINK));
        LocalDateTime now = UtcTime.now();
        if (token.getUsedAt() != null || !token.getExpiresAt().isAfter(now)) {
            throw new BadRequestException(INVALID_LINK);
        }
        token.setUsedAt(now);
        return token.getUser();
    }
}
