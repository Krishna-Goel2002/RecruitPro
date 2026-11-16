package com.recruitpro.ai;

import com.recruitpro.ai.dto.AiCompileRequest;
import com.recruitpro.ai.dto.AiResumeParseResponse;
import com.recruitpro.ai.service.AiServiceClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R2-H03: resume parsing gets its own, longer read time-out (the AI service may take its whole 60 s parse budget plus
 * queueing), while the other calls keep the general time-out. R2-L08: the parser never receives the original file
 * name (it often is the candidate's name), only "resume.&lt;extension&gt;".
 */
class AiServiceClientTimeoutTest {

    private static final int DELAY_MS = 1_500;

    private HttpServer server;
    private final AtomicReference<String> uploadedBody = new AtomicReference<>();

    @BeforeEach
    void startSlowAiService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            uploadedBody.set(new String(request, StandardCharsets.ISO_8859_1));
            try {
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"status\":\"COMPLETED\",\"parserVersion\":\"rule-parser-2\",\"facts\":[],\"flags\":[]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void parsingOutlastsTheGeneralTimeoutWhileOtherCallsDoNot() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        AiServiceClient client = new AiServiceClient(RestClient.builder(), baseUrl, "test-only-token", 500, 10_000);

        AiResumeParseResponse parsed = client.parse("Priya Sharma CV.PDF", "application/pdf",
                "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII));
        assertNotNull(parsed);
        assertEquals("COMPLETED", parsed.status(), "a slow parse is waited for, not cut off and retried");
        String upload = uploadedBody.get();
        assertTrue(upload.contains("filename=\"resume.pdf\""), upload);
        assertFalse(upload.contains("Priya"), "the original file name never reaches the AI service");

        assertThrows(ResourceAccessException.class,
                () -> client.compileRequirements(new AiCompileRequest("Role", "Description", null)),
                "the other calls keep the general time-out");
    }

    @Test
    void theFourArgumentConstructorStillGivesParsingTheLongerDefault() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        AiServiceClient client = new AiServiceClient(RestClient.builder(), baseUrl, "test-only-token", 500);
        assertEquals("COMPLETED", client.parse("cv.docx", null, new byte[] {'P', 'K', 3, 4}).status());
        assertTrue(uploadedBody.get().contains("filename=\"resume.docx\""));
    }
}
