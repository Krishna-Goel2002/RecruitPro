package com.recruitpro.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.Jwks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public signing keys of each JWKS URI, parsed with jjwt's JWK support. Keys are cached for 6 hours; a token with an
 * unknown {@code kid} triggers a refresh (at most one every 30 seconds per URI, so a flood of bad tokens cannot make
 * us hammer the provider). Keys with {@code use=enc} and keys jjwt cannot parse are skipped one by one.
 */
@Component
public class JwksKeyCache {

    static final Duration MAX_AGE = Duration.ofHours(6);
    static final Duration MIN_REFRESH_INTERVAL = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(JwksKeyCache.class);

    private final OidcHttpClient http;
    private final ObjectMapper objectMapper;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public JwksKeyCache(OidcHttpClient http, ObjectMapper objectMapper) {
        this.http = http;
        this.objectMapper = objectMapper;
    }

    /** The public key for {@code kid} (or the only key when the token has no kid); null when unknown. */
    public Key find(String jwksUri, String kid) {
        Instant now = Instant.now();
        Entry entry = cache.get(jwksUri);
        if (entry != null && entry.fetchedAt().plus(MAX_AGE).isAfter(now)) {
            Key key = entry.lookup(kid);
            if (key != null) {
                return key;
            }
        }
        if (entry == null || entry.fetchedAt().plus(MIN_REFRESH_INTERVAL).isBefore(now)) {
            entry = fetch(jwksUri, now);
            cache.put(jwksUri, entry);
        }
        return entry.lookup(kid);
    }

    public void clear() {
        cache.clear();
    }

    private Entry fetch(String jwksUri, Instant now) {
        Map<String, Key> keys = new HashMap<>();
        try {
            JsonNode root = objectMapper.readTree(http.get(jwksUri));
            for (JsonNode node : root.path("keys")) {
                if ("enc".equals(node.path("use").asText(""))) {
                    continue;
                }
                try {
                    Jwk<?> jwk = Jwks.parser().build().parse(objectMapper.writeValueAsString(node));
                    Key key = jwk.toKey();
                    if (key instanceof PublicKey) {
                        keys.put(jwk.getId() == null ? "" : jwk.getId(), key);
                    }
                } catch (RuntimeException ex) {
                    log.debug("Skipping a JWK of {} that could not be parsed: {}", jwksUri, ex.getClass().getSimpleName());
                }
            }
        } catch (Exception ex) {
            log.warn("Fetching JWKS {} failed: {}", jwksUri, ex.getClass().getSimpleName());
        }
        return new Entry(Map.copyOf(keys), now);
    }

    record Entry(Map<String, Key> keys, Instant fetchedAt) {
        Key lookup(String kid) {
            if (kid == null || kid.isBlank()) {
                return keys.size() == 1 ? keys.values().iterator().next() : null;
            }
            return keys.get(kid);
        }
    }
}
