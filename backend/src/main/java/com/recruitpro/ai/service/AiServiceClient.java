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

    /**
     * R2-H03: read time-out of resume parsing. The AI service gives one parse up to {@code PARSE_TIMEOUT_SECONDS}
     * (60 s, LibreOffice and OCR included) plus queueing behind its worker pool, so the general 30 s time-out cut
     * slow parses off, retried them and piled abandoned parses onto the pool.
     */
    static final int DEFAULT_PARSE_TIMEOUT_MS = 120_000;

    private final RestClient restClient;
    private final RestClient parseClient;
    private final String serviceToken;
    /** Set by Spring when Micrometer is present (timer recruitpro.ai.call); null in plain unit tests. */
    private volatile MeterRegistry meterRegistry;

    public AiServiceClient(RestClient.Builder builder, String baseUrl, String serviceToken, int timeoutMs) {
        this(builder, baseUrl, serviceToken, timeoutMs, DEFAULT_PARSE_TIMEOUT_MS);
    }

    @Autowired
    public AiServiceClient(RestClient.Builder builder,
                           @Value("${app.ai-service.base-url}") String baseUrl,
                           @Value("${app.ai-service.service-token:}") String serviceToken,
                           @Value("${app.ai-service.timeout-ms:30000}") int timeoutMs,
                           @Value("${app.ai-service.parse-timeout-ms:120000}") int parseTimeoutMs) {
        this.restClient = build(builder.clone(), baseUrl, timeoutMs, timeoutMs);
        this.parseClient = build(builder.clone(), baseUrl, timeoutMs, Math.max(timeoutMs, parseTimeoutMs));
        this.serviceToken = serviceToken;
    }

    private RestClient build(RestClient.Builder builder, String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        // WP-PLAT: X-Request-Id propagation (MDC "requestId") and the recruitpro.ai.call timer.
        return builder.baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(new RequestIdClientInterceptor())
                .requestInterceptor(new TimedClientInterceptor(ObservabilityConfig.AI_TIMER, () -> this.meterRegistry))
                .build();
    }

    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public AiResumeParseResponse parse(String filename, String contentType, byte[] bytes) {
        requireToken();
        // R2-L08: only the extension matters to the parser; the original name often is the candidate's name.
        String neutralName = neutralFilename(filename);
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return neutralName; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);
        return parseClient.post().uri("/api/v1/resume/parse")
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

    /** "resume.&lt;extension&gt;" (lower case letters and digits only, at most 10), or "resume" without one. */
    static String neutralFilename(String filename) {
        String name = filename == null ? "" : filename.trim();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return extension.matches("[a-z0-9]{1,10}") ? "resume." + extension : "resume";
    }

    private void requireToken() {
        if (serviceToken == null || serviceToken.isBlank()) throw new IllegalStateException(NOT_CONFIGURED_MESSAGE);
    }
}
