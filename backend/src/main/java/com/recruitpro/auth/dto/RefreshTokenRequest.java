package com.recruitpro.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RefreshTokenRequest {
    @NotBlank(message = "Refresh token is required")
    @Size(max = 128, message = "Refresh token is invalid")
    private String refreshToken;
}
