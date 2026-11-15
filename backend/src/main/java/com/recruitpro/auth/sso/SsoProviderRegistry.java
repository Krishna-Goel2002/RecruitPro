package com.recruitpro.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Configured OIDC providers and their endpoints. Endpoints missing from the configuration are read once, lazily,
 * from {@code <issuer>/.well-known/openid-configuration} and cached; a failed discovery is retried on the next use.
 */
@Component
public class SsoProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(SsoProviderRegistry.class);
    private static final Pattern PROVIDER_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private final SsoProperties properties;
    private final OidcHttpClient http;
    private final ObjectMapper objectMapper;
    private final Map<String, ResolvedProvider> resolved = new ConcurrentHashMap<>();

    public SsoProviderRegistry(SsoProperties properties, OidcHttpClient http, ObjectMapper objectMapper) {
        this.properties = properties;
        this.http = http;
        this.objectMapper = objectMapper;
    }

    /** Ids and display names of the providers with client id, secret and endpoints or issuer, in configuration order. */
    public List<ProviderSummary> configured() {
        List<ProviderSummary> result = new ArrayList<>();
        for (Map.Entry<String, SsoProperties.Provider> entry : properties.getProviders().entrySet()) {
            String id = normalizeId(entry.getKey());
            if (id != null && entry.getValue() != null && entry.getValue().isConfigured()) {
                result.add(new ProviderSummary(id, displayName(id, entry.getValue())));
            }
        }
        return result;
    }

    public Optional<SsoProperties.Provider> find(String id) {
        String normalized = normalizeId(id);
        if (normalized == null) {
            return Optional.empty();
        }
        for (Map.Entry<String, SsoProperties.Provider> entry : properties.getProviders().entrySet()) {
            if (normalized.equals(normalizeId(entry.getKey())) && entry.getValue() != null && entry.getValue().isConfigured()) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /** The provider with all endpoints; {@link SsoException} {@code not_configured} when unknown or undiscoverable. */
    public ResolvedProvider resolve(String id) {
        String normalized = normalizeId(id);
        SsoProperties.Provider provider = find(normalized)
                .orElseThrow(() -> new SsoException("not_configured", "Unknown or unconfigured SSO provider"));
        ResolvedProvider cached = resolved.get(normalized);
        if (cached != null) {
            return cached;
        }
        String issuer = trim(provider.getIssuer());
        String authorizationUri = trim(provider.getAuthorizationUri());
        String tokenUri = trim(provider.getTokenUri());
        String jwksUri = trim(provider.getJwksUri());
        if (authorizationUri == null || tokenUri == null || jwksUri == null || issuer == null) {
            if (issuer == null) {
                throw new SsoException("not_configured", "SSO provider " + normalized + " has no issuer");
            }
            JsonNode discovery = discover(issuer);
            authorizationUri = authorizationUri != null ? authorizationUri : text(discovery, "authorization_endpoint");
            tokenUri = tokenUri != null ? tokenUri : text(discovery, "token_endpoint");
            jwksUri = jwksUri != null ? jwksUri : text(discovery, "jwks_uri");
            String discoveredIssuer = text(discovery, "issuer");
            if (discoveredIssuer != null) {
                issuer = discoveredIssuer;
            }
            if (authorizationUri == null || tokenUri == null || jwksUri == null) {
                throw new SsoException("not_configured", "The discovery document of " + normalized + " is incomplete");
            }
        }
        String scopes = provider.getScopes() == null || provider.getScopes().isBlank() ? "openid email profile"
                : provider.getScopes().trim();
        if (!(" " + scopes + " ").contains(" openid ")) {
            scopes = "openid " + scopes;
        }
        ResolvedProvider result = new ResolvedProvider(normalized, displayName(normalized, provider), provider.getClientId().trim(),
                provider.getClientSecret(), issuer, authorizationUri, tokenUri, jwksUri, scopes);
        resolved.put(normalized, result);
        return result;
    }

    /** Clears cached discovery results (tests). */
    public void clearCache() {
        resolved.clear();
    }

    private JsonNode discover(String issuer) {
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        try {
            return objectMapper.readTree(http.get(base + "/.well-known/openid-configuration"));
        } catch (Exception ex) {
            log.warn("OIDC discovery for {} failed: {}", issuer, ex.getClass().getSimpleName());
            throw new SsoException("not_configured", "The SSO provider could not be reached", ex);
        }
    }

    static String normalizeId(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        return PROVIDER_ID.matcher(normalized).matches() ? normalized : null;
    }

    private static String displayName(String id, SsoProperties.Provider provider) {
        if (provider.getName() != null && !provider.getName().isBlank()) {
            return provider.getName().trim();
        }
        return switch (id) {
            case "google" -> "Google";
            case "microsoft", "entra", "azure" -> "Microsoft";
            default -> Character.toUpperCase(id.charAt(0)) + id.substring(1);
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText().trim();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** {@code GET /api/v1/auth/sso/providers} item. */
    public record ProviderSummary(String id, String name) {
    }

    /** A provider with every endpoint known. {@code issuer} may contain {@code {tenantid}} (Microsoft multi-tenant). */
    public record ResolvedProvider(String id, String name, String clientId, String clientSecret, String issuer,
                                   String authorizationUri, String tokenUri, String jwksUri, String scopes) {
    }
}
