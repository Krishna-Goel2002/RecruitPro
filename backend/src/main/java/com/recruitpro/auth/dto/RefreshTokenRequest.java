package com.recruitpro.auth.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Optional body for /auth/refresh and /auth/logout. Browsers send their portal's refresh cookie instead;
 * the body is honoured only when {@code app.auth.expose-refresh-token-in-body=true}.
 */
@Data
public class RefreshTokenRequest {
    @Size(max = 256, message = "Refresh token is invalid")
    private String refreshToken;
}
