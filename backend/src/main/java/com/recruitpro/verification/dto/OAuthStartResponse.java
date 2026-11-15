package com.recruitpro.verification.dto;

/** GET /oauth/github/start: where the portal sends the browser. */
public record OAuthStartResponse(String authorizeUrl) {
}
