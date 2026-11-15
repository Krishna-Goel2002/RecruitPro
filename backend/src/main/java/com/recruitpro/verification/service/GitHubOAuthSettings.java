package com.recruitpro.verification.service;

import com.recruitpro.common.crypto.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GitHub OAuth configuration ({@code app.verification.github.*} from GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET,
 * GITHUB_OAUTH_REDIRECT_URI). OAuth is available only when all three are set and {@link SecretCipher} has a key,
 * so a token is never stored in plaintext.
 */
@Component
public class GitHubOAuthSettings {

    public static final String SCOPE = "read:user";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String authorizeUrl;
    private final SecretCipher cipher;

    public GitHubOAuthSettings(@Value("${app.verification.github.client-id:}") String clientId,
                               @Value("${app.verification.github.client-secret:}") String clientSecret,
                               @Value("${app.verification.github.redirect-uri:}") String redirectUri,
                               @Value("${app.verification.github.authorize-url:https://github.com/login/oauth/authorize}") String authorizeUrl,
                               SecretCipher cipher) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
        this.authorizeUrl = authorizeUrl;
        this.cipher = cipher;
    }

    public boolean isConfigured() {
        return !clientId.isEmpty() && !clientSecret.isEmpty() && !redirectUri.isEmpty() && cipher.isConfigured();
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public String redirectUri() {
        return redirectUri;
    }

    public String authorizeUrl() {
        return authorizeUrl;
    }
}
