package com.recruitpro.ai.service;

import com.recruitpro.ai.dto.AiResumeParseResponse;
import com.recruitpro.candidate.service.ResumeIntelligenceService;
import com.recruitpro.candidate.storage.ResumeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Executor;

@Component
@ConditionalOnProperty(prefix = "app.ai-service", name = "queue-enabled", havingValue = "true", matchIfMissing = true)
public class ResumeAnalysisWorker {
    private static final Logger log = LoggerFactory.getLogger(ResumeAnalysisWorker.class);
    private final ResumeIntelligenceService intelligenceService;
    private final ResumeStorage storage;
    private final AiServiceClient aiServiceClient;
    private final Executor executor;

    public ResumeAnalysisWorker(ResumeIntelligenceService intelligenceService, ResumeStorage storage,
                                AiServiceClient aiServiceClient,
                                @Qualifier("resumeAnalysisExecutor") Executor executor) {
        this.intelligenceService = intelligenceService;
        this.storage = storage;
        this.aiServiceClient = aiServiceClient;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${app.ai-service.poll-interval-ms:2000}")
    public void dispatchPending() {
        intelligenceService.recoverStaleProcessing();
        Long id = intelligenceService.nextPendingId();
        if (id == null) return;
        try { executor.execute(() -> process(id)); }
        catch (java.util.concurrent.RejectedExecutionException ex) { log.warn("Resume analysis queue is full; item {} remains pending", id); }
    }

    private void process(Long analysisId) {
        ResumeIntelligenceService.WorkItem item;
        try { item = intelligenceService.claim(analysisId); }
        catch (RuntimeException ex) {
            log.warn("Could not claim resume analysis {}", analysisId);
            return;
        }
        if (item == null) return;
        try {
            byte[] bytes = storage.load(item.storageKey());
            String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            AiResumeParseResponse response = aiServiceClient.parse(item.filename(), item.contentType(), bytes);
            if (response == null) throw new IllegalStateException("AI service returned no response");
            intelligenceService.complete(analysisId, sha256, response);
        } catch (NoSuchFileException ex) {
            intelligenceService.fail(analysisId, "FILE_UNAVAILABLE");
        } catch (RestClientResponseException ex) {
            intelligenceService.fail(analysisId, ex.getStatusCode().is5xxServerError() ? "AI_SERVICE_UNAVAILABLE" : "AI_SERVICE_REJECTED");
        } catch (IOException ex) {
            intelligenceService.fail(analysisId, "FILE_UNAVAILABLE");
        } catch (IllegalStateException ex) {
            intelligenceService.fail(analysisId,
                    "AI service authentication is not configured".equals(ex.getMessage())
                            ? "AI_SERVICE_AUTHENTICATION_NOT_CONFIGURED" : "ANALYSIS_FAILED");
        } catch (Exception ex) {
            intelligenceService.fail(analysisId, "ANALYSIS_FAILED");
            log.warn("Resume analysis {} failed", analysisId);
        }
    }
}
