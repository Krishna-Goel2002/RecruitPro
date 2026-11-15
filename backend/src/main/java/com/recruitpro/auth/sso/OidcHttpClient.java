package com.recruitpro.auth.sso;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

/** Outbound HTTP to OIDC providers (discovery, JWKS, token endpoint), with timeouts. */
@Component
public class OidcHttpClient {

    private final RestClient restClient;

    public OidcHttpClient(RestClient.Builder builder, SsoProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = Math.max(1000, properties.getHttpTimeoutMs());
        factory.setConnectTimeout(Duration.ofMillis(timeout));
        factory.setReadTimeout(Duration.ofMillis(timeout));
        // No X-Request-Id towards external identity providers (it is an internal correlation id).
        this.restClient = builder.requestFactory(factory).build();
    }

    public String get(String url) {
        return restClient.get().uri(URI.create(url)).accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
    }

    public String postForm(String url, MultiValueMap<String, String> form) {
        return restClient.post().uri(URI.create(url))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve().body(String.class);
    }
}
