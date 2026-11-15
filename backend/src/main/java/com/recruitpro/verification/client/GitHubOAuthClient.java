package com.recruitpro.verification.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * GitHub OAuth (authorization code) for connecting a candidate's account: exchanges the code at
 * {@code https://github.com/login/oauth/access_token} and reads {@code https://api.github.com/user}. URLs are
 * configurable for tests ({@code app.verification.github.token-url}, {@code api-url}); tests mock this bean.
 */
@Component
public class GitHubOAuthClient {

    private final RestClient restClient;
    private final String tokenUrl;
    private final String apiUrl;

    public GitHubOAuthClient(RestClient.Builder builder,
                             @Value("${app.verification.github.token-url:https://github.com/login/oauth/access_token}") String tokenUrl,
                             @Value("${app.verification.github.api-url:https://api.github.com}") String apiUrl,
                             @Value("${app.verification.github.timeout-ms:10000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restClient = builder.clone().requestFactory(factory).build();
        this.tokenUrl = tokenUrl;
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
    }

    /** Exchanges an authorization code; the result has {@code error} set when GitHub refused it. */
    public TokenResponse exchangeCode(String clientId, String clientSecret, String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        return restClient.post().uri(tokenUrl)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(TokenResponse.class);
    }

    /** The account the token belongs to. */
    public GitHubUser fetchUser(String accessToken) {
        return restClient.get().uri(apiUrl + "/user")
                .accept(MediaType.parseMediaType("application/vnd.github+json"))
                .header("Authorization", "Bearer " + accessToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .retrieve().body(GitHubUser.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenResponse(@JsonProperty("access_token") String accessToken,
                                @JsonProperty("scope") String scope,
                                @JsonProperty("token_type") String tokenType,
                                @JsonProperty("error") String error) {
        @Override
        public String toString() {
            return "TokenResponse[scope=" + scope + ", error=" + error + "]";
        }

        public List<String> scopes() {
            if (scope == null || scope.isBlank()) {
                return List.of();
            }
            return List.of(scope.split("[,\\s]+"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubUser(String login, Long id, String name) {
    }
}
