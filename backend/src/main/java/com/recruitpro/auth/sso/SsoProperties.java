package com.recruitpro.auth.sso;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code app.sso.*} (plan v2, Section 6.2). Each provider under {@code app.sso.providers.<id>} needs
 * {@code client-id}, {@code client-secret} and either {@code issuer} (endpoints are then read lazily from
 * {@code <issuer>/.well-known/openid-configuration}) or explicit {@code authorization-uri}, {@code token-uri},
 * {@code jwks-uri} and {@code issuer}. A provider without client id or secret is not offered.
 */
@Component
@ConfigurationProperties(prefix = "app.sso")
public class SsoProperties {

    /** Public base URL of this backend; the callback is {@code <base>/api/v1/auth/sso/<id>/callback}. */
    private String redirectBaseUrl = "http://localhost:8080";
    private long stateTtlMinutes = 10;
    private long clockSkewSeconds = 60;
    /** Maximum age of an id_token's {@code iat}. */
    private long maxTokenAgeSeconds = 600;
    private int httpTimeoutMs = 10000;
    private Map<String, Provider> providers = new LinkedHashMap<>();

    public String getRedirectBaseUrl() {
        return redirectBaseUrl;
    }

    public void setRedirectBaseUrl(String redirectBaseUrl) {
        this.redirectBaseUrl = redirectBaseUrl;
    }

    public long getStateTtlMinutes() {
        return stateTtlMinutes;
    }

    public void setStateTtlMinutes(long stateTtlMinutes) {
        this.stateTtlMinutes = stateTtlMinutes;
    }

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public long getMaxTokenAgeSeconds() {
        return maxTokenAgeSeconds;
    }

    public void setMaxTokenAgeSeconds(long maxTokenAgeSeconds) {
        this.maxTokenAgeSeconds = maxTokenAgeSeconds;
    }

    public int getHttpTimeoutMs() {
        return httpTimeoutMs;
    }

    public void setHttpTimeoutMs(int httpTimeoutMs) {
        this.httpTimeoutMs = httpTimeoutMs;
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : providers;
    }

    /** One OIDC provider. */
    public static class Provider {
        private String name;
        private String clientId;
        private String clientSecret;
        private String issuer;
        private String authorizationUri;
        private String tokenUri;
        private String jwksUri;
        private String scopes = "openid email profile";

        public boolean isConfigured() {
            return notBlank(clientId) && notBlank(clientSecret)
                    && (notBlank(issuer) || (notBlank(authorizationUri) && notBlank(tokenUri) && notBlank(jwksUri)));
        }

        static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAuthorizationUri() {
            return authorizationUri;
        }

        public void setAuthorizationUri(String authorizationUri) {
            this.authorizationUri = authorizationUri;
        }

        public String getTokenUri() {
            return tokenUri;
        }

        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri;
        }

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri;
        }

        public String getScopes() {
            return scopes;
        }

        public void setScopes(String scopes) {
            this.scopes = scopes;
        }
    }
}
