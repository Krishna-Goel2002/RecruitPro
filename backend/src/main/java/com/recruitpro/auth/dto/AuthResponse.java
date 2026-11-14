package com.recruitpro.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code { accessToken, tokenType: 'Bearer', expiresInSeconds, user }}. The refresh token travels in the
 * portal's httpOnly refresh cookie ({@code rp_refresh_candidate}, {@code rp_refresh_staff}, or {@code rp_refresh}
 * without the X-RecruitPro-Portal header); {@code refreshToken} is only filled when
 * {@code app.auth.expose-refresh-token-in-body=true} (API clients and tests).
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    @Builder.Default
    private String tokenType = "Bearer";
    private long expiresInSeconds;
    private UserResponse user;
}
