package com.recruitpro.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.Jwks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.security.PublicKey;
import java.time.Clock;
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
    private final Clock clock;

    @Autowired
    public JwksKeyCache(OidcHttpClient http, ObjectMapper objectMapper) {
        this(http, objectMapper, Clock.systemUTC());
    }

    /** Tests: a controllable clock. */
    JwksKeyCache(OidcHttpClient http, ObjectMapper objectMapper, Clock clock) {
        this.http = http;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** The public key for {@code kid} (or the only key when the token has no kid); null when unknown. */
    public Key find(String jwksUri, String kid) {
        Instant now = clock.instant();
        Entry entry = cache.get(jwksUri);
        if (entry != null && entry.fetchedAt().plus(MAX_AGE).isAfter(now)) {
            Key key = entry.lookup(kid);
            if (key != null) {
                return key;
            }
        }
        if (entry == null || entry.attemptedAt().plus(MIN_REFRESH_INTERVAL).isBefore(now)) {
            Map<String, Key> fetched = fetch(jwksUri);
            if (fetched != null) {
                entry = new Entry(fetched, now, now);
            } else {
                // R1-L5: a failed refresh keeps the keys we already have (a provider hiccup must not log everybody
                // out of SSO); the attempt time still throttles the next try.
                entry = new Entry(entry == null ? Map.of() : entry.keys(), entry == null ? now : entry.fetchedAt(), now);
            }
            cache.put(jwksUri, entry);
        }
        return entry.lookup(kid);
    }

    public void clear() {
        cache.clear();
    }

    /** The usable signing keys of the JWKS, or null when the document could not be fetched or read. */
    private Map<String, Key> fetch(String jwksUri) {
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
            return null;
        }
        return Map.copyOf(keys);
    }

    /** {@code fetchedAt}: when {@code keys} were read; {@code attemptedAt}: the last fetch attempt (throttle). */
    record Entry(Map<String, Key> keys, Instant fetchedAt, Instant attemptedAt) {
        Key lookup(String kid) {
            if (kid == null || kid.isBlank()) {
                return keys.size() == 1 ? keys.values().iterator().next() : null;
            }
            return keys.get(kid);
        }
    }
}
