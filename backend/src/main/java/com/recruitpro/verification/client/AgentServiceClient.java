package com.recruitpro.verification.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Internal client of the agent service (plan v2 sections 4 and 5). Every call carries
 * {@code X-Internal-Service-Token} and, when the current thread has one in the MDC ({@code requestId}), the
 * {@code X-Request-Id} header. A missing token raises {@link IllegalStateException} with
 * {@link #NOT_CONFIGURED_MESSAGE}; a non-2xx answer raises {@link AgentServiceException} carrying
 * {@code detail.code}; connection problems and time-outs surface as Spring's {@code RestClientException}.
 *
 * <p>Only background jobs (VERIFY_SOURCE, VERIFY_REFERENCE) and the candidate's explicit ownership check call it,
 * always outside database transactions.</p>
 */
@Component
public class AgentServiceClient {

    public static final String NOT_CONFIGURED_MESSAGE = "Agent service authentication is not configured";
    static final String TOKEN_HEADER = "X-Internal-Service-Token";
    static final String REQUEST_ID_HEADER = "X-Request-Id";
    static final String REQUEST_ID_MDC_KEY = "requestId";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    private final RestClient restClient;
    private final String serviceToken;
    private final ObjectMapper objectMapper;

    public AgentServiceClient(RestClient.Builder builder, ObjectMapper objectMapper,
                              @Value("${app.agent-service.base-url:http://localhost:8100}") String baseUrl,
                              @Value("${app.agent-service.service-token:}") String serviceToken,
                              @Value("${app.agent-service.timeout-ms:60000}") int timeoutMs,
                              @Value("${app.agent-service.connect-timeout-ms:5000}") int connectTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restClient = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return serviceToken != null && !serviceToken.isBlank();
    }

    public AgentModels.OwnershipCheckResponse checkOwnership(AgentModels.OwnershipCheckRequest request) {
        return postJson("/api/v1/ownership/check", request, AgentModels.OwnershipCheckResponse.class);
    }

    public AgentModels.VerifyResponse verifyCode(AgentModels.CodeVerifyRequest request) {
        return postJson("/api/v1/verify/code", request, AgentModels.VerifyResponse.class);
    }

    public AgentModels.VerifyResponse verifyWeb(AgentModels.WebVerifyRequest request) {
        return postJson("/api/v1/verify/web", request, AgentModels.VerifyResponse.class);
    }

    public AgentModels.VerifyResponse verifyBadge(AgentModels.BadgeVerifyRequest request) {
        return postJson("/api/v1/verify/badge", request, AgentModels.VerifyResponse.class);
    }

    public AgentModels.VerifyResponse verifyReference(AgentModels.ReferenceVerifyRequest request) {
        return postJson("/api/v1/verify/reference", request, AgentModels.VerifyResponse.class);
    }

    /** Multipart: {@code file} plus the {@code request} form field holding the JSON request. */
    public AgentModels.VerifyResponse verifyDocument(String filename, byte[] content, AgentModels.DocumentVerifyRequest request) {
        requireToken();
        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(request);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialise the document verification request", ex);
        }
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);
        body.add("request", requestJson);
        try {
            return restClient.post().uri("/api/v1/verify/document")
                    .headers(headers -> {
                        headers.set(TOKEN_HEADER, serviceToken);
                        String requestId = currentRequestId();
                        if (requestId != null) {
                            headers.set(REQUEST_ID_HEADER, requestId);
                        }
                    })
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve().body(AgentModels.VerifyResponse.class);
        } catch (RestClientResponseException ex) {
            throw translate(ex);
        }
    }

    private <T> T postJson(String path, Object request, Class<T> type) {
        requireToken();
        try {
            return restClient.post().uri(path)
                    .headers(headers -> {
                        headers.set(TOKEN_HEADER, serviceToken);
                        String requestId = currentRequestId();
                        if (requestId != null) {
                            headers.set(REQUEST_ID_HEADER, requestId);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve().body(type);
        } catch (RestClientResponseException ex) {
            throw translate(ex);
        }
    }

    /** Maps a non-2xx answer to {@link AgentServiceException} with {@code detail.code} when the body has one. */
    public AgentServiceException translate(RestClientResponseException ex) {
        String code = null;
        String message = null;
        try {
            String text = ex.getResponseBodyAsString();
            if (text != null && !text.isBlank()) {
                JsonNode detail = objectMapper.readTree(text).path("detail");
                if (detail.isObject()) {
                    code = textOrNull(detail.path("code"));
                    message = textOrNull(detail.path("message"));
                }
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // Not a JSON error body (proxy page, empty body): status only.
        }
        if (code != null && !code.matches("[A-Z0-9_]{1,60}")) {
            code = "AGENT_ERROR";
        }
        return new AgentServiceException(ex.getStatusCode().value(), code, message);
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    static String currentRequestId() {
        String value = MDC.get(REQUEST_ID_MDC_KEY);
        return value != null && SAFE_REQUEST_ID.matcher(value).matches() ? value : null;
    }

    private void requireToken() {
        if (!isConfigured()) {
            throw new IllegalStateException(NOT_CONFIGURED_MESSAGE);
        }
    }
}
