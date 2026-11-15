package com.recruitpro.ai.service;

import com.recruitpro.ai.dto.AiCompileRequest;
import com.recruitpro.ai.dto.AiCompileResponse;
import com.recruitpro.ai.dto.AiInterviewKitRequest;
import com.recruitpro.ai.dto.AiInterviewKitResponse;
import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.ai.dto.AiMatchResponse;
import com.recruitpro.ai.dto.AiResumeParseResponse;
import com.recruitpro.ai.dto.AiWorkSampleRequest;
import com.recruitpro.ai.dto.AiWorkSampleResponse;
import com.recruitpro.observability.ObservabilityConfig;
import com.recruitpro.observability.RequestIdClientInterceptor;
import com.recruitpro.observability.TimedClientInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Internal client of the AI service (plan 6.1). Every call carries {@code X-Internal-Service-Token}; a missing
 * token raises {@link IllegalStateException} with {@link #NOT_CONFIGURED_MESSAGE}. Only background jobs and
 * explicit HR actions (requirement compiling, work-sample and interview-kit drafting) call it; GET endpoints never do (H6, H8).
 */
@Component
public class AiServiceClient {
    /** Message of the IllegalStateException thrown when no service token is configured. */
    public static final String NOT_CONFIGURED_MESSAGE = "AI service authentication is not configured";

    private final RestClient restClient;
    private final String serviceToken;
    /** Set by Spring when Micrometer is present (timer recruitpro.ai.call); null in plain unit tests. */
    private volatile MeterRegistry meterRegistry;

    public AiServiceClient(RestClient.Builder builder,
                           @Value("${app.ai-service.base-url}") String baseUrl,
                           @Value("${app.ai-service.service-token:}") String serviceToken,
                           @Value("${app.ai-service.timeout-ms:30000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        // WP-PLAT: X-Request-Id propagation (MDC "requestId") and the recruitpro.ai.call timer.
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(new RequestIdClientInterceptor())
                .requestInterceptor(new TimedClientInterceptor(ObservabilityConfig.AI_TIMER, () -> this.meterRegistry))
                .build();
        this.serviceToken = serviceToken;
    }

    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public AiResumeParseResponse parse(String filename, String contentType, byte[] bytes) {
        requireToken();
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

    /** Evidence-ladder matching of one application (MATCH_APPLICATION background job). */
    public AiMatchResponse match(AiMatchRequest request) {
        requireToken();
        return restClient.post().uri("/api/v1/match")
                .header("X-Internal-Service-Token", serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve().body(AiMatchResponse.class);
    }

    /** Drafts requirements and wording warnings from a job description; nothing is persisted. */
    public AiCompileResponse compileRequirements(AiCompileRequest request) {
        requireToken();
        return restClient.post().uri("/api/v1/requirements/compile")
                .header("X-Internal-Service-Token", serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve().body(AiCompileResponse.class);
    }

    /** Drafts a work sample with an anchored rubric (HR action; persisted by the caller as DRAFT). */
    public AiWorkSampleResponse generateWorkSample(AiWorkSampleRequest request) {
        requireToken();
        return restClient.post().uri("/api/v1/work-samples/generate")
                .header("X-Internal-Service-Token", serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve().body(AiWorkSampleResponse.class);
    }

    /** Drafts an interview kit (HR action; persisted by the caller). */
    public AiInterviewKitResponse generateInterviewKit(AiInterviewKitRequest request) {
        requireToken();
        return restClient.post().uri("/api/v1/interview-kits/generate")
                .header("X-Internal-Service-Token", serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve().body(AiInterviewKitResponse.class);
    }

    private void requireToken() {
        if (serviceToken == null || serviceToken.isBlank()) throw new IllegalStateException(NOT_CONFIGURED_MESSAGE);
    }
}
