package com.recruitpro.ai.service;

import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.ai.dto.AiMatchResponse;
import com.recruitpro.ai.dto.AiBatchMatchRequest;
import com.recruitpro.ai.dto.AiBatchMatchResponse;
import com.recruitpro.ai.dto.AiResumeParseResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class AiServiceClient {
    private final RestClient restClient;
    private final String serviceToken;

    public AiServiceClient(RestClient.Builder builder,
                           @Value("${app.ai-service.base-url}") String baseUrl,
                           @Value("${app.ai-service.service-token:}") String serviceToken,
                           @Value("${app.ai-service.timeout-ms:30000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    public AiResumeParseResponse parse(String filename, String contentType, byte[] bytes) {
        if (serviceToken.isBlank()) throw new IllegalStateException("AI service authentication is not configured");
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);
        return restClient.post().uri("/api/v1/resume/parse")
                .header("X-Internal-Service-Token", serviceToken)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve().body(AiResumeParseResponse.class);
    }

    public AiMatchResponse match(AiMatchRequest request) {
        if (serviceToken.isBlank()) throw new IllegalStateException("AI service authentication is not configured");
        return restClient.post().uri("/api/v1/match")
                .header("X-Internal-Service-Token", serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve().body(AiMatchResponse.class);
    }

    public AiBatchMatchResponse matchBatch(AiBatchMatchRequest request) {
        if (serviceToken.isBlank()) throw new IllegalStateException("AI service authentication is not configured");
        return restClient.post().uri("/api/v1/match/batch")
                .header("X-Internal-Service-Token", serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve().body(AiBatchMatchResponse.class);
    }
}
