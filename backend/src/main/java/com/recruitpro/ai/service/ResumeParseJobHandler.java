package com.recruitpro.ai.service;

import com.recruitpro.ai.dto.AiResumeParseResponse;
import com.recruitpro.candidate.service.ResumeIntelligenceService;
import com.recruitpro.candidate.storage.ResumeStorage;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.service.BackgroundJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * PARSE_RESUME handler (entity id = resume_analyses.id); replaces the former in-process
 * {@code ResumeAnalysisWorker}. Transient AI-service failures (connection errors, time-outs, HTTP 5xx/429)
 * put the analysis back to PENDING and throw, so the job is retried with backoff; when retries are
 * exhausted the analysis becomes FAILED (retryable by the candidate or HR). Permanent outcomes (parser
 * results, rejected input, missing file, missing service token) complete the analysis and the job.
 */
@Component
public class ResumeParseJobHandler implements BackgroundJobHandler {

    private static final Logger log = LoggerFactory.getLogger(ResumeParseJobHandler.class);
    static final String AI_UNAVAILABLE = "AI_SERVICE_UNAVAILABLE";

    private final ResumeIntelligenceService intelligenceService;
    private final ResumeStorage storage;
    private final AiServiceClient aiServiceClient;

    public ResumeParseJobHandler(ResumeIntelligenceService intelligenceService, ResumeStorage storage,
                                 AiServiceClient aiServiceClient) {
        this.intelligenceService = intelligenceService;
        this.storage = storage;
        this.aiServiceClient = aiServiceClient;
    }

    @Override
    public BackgroundJobType type() {
        return BackgroundJobType.PARSE_RESUME;
    }

    @Override
    public void handle(long analysisId) throws Exception {
        ResumeIntelligenceService.WorkItem item = intelligenceService.claimForProcessing(analysisId);
        if (item == null) {
            log.debug("Resume analysis {} is not waiting for parsing; nothing to do", analysisId);
            return;
        }
        byte[] bytes;
        try {
            bytes = storage.load(item.storageKey());
        } catch (IOException ex) {
            intelligenceService.fail(analysisId, "FILE_UNAVAILABLE");
            return;
        }
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        AiResumeParseResponse response;
        try {
            response = aiServiceClient.parse(item.filename(), item.contentType(), bytes);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status >= 500 || status == 429 || status == 408) {
                intelligenceService.releaseForRetry(analysisId, AI_UNAVAILABLE);
                throw new IllegalStateException("AI service responded with HTTP " + status);
            }
            intelligenceService.fail(analysisId, "AI_SERVICE_REJECTED");
            return;
        } catch (RestClientException ex) {
            intelligenceService.releaseForRetry(analysisId, AI_UNAVAILABLE);
            throw new IllegalStateException("AI service unavailable (" + ex.getClass().getSimpleName() + ")");
        } catch (IllegalStateException ex) {
            if (AiServiceClient.NOT_CONFIGURED_MESSAGE.equals(ex.getMessage())) {
                intelligenceService.fail(analysisId, "AI_SERVICE_AUTHENTICATION_NOT_CONFIGURED");
                return;
            }
            intelligenceService.releaseForRetry(analysisId, AI_UNAVAILABLE);
            throw ex;
        }

        if (response == null || response.status() == null) {
            intelligenceService.releaseForRetry(analysisId, AI_UNAVAILABLE);
            throw new IllegalStateException("AI service returned an empty parse response");
        }
        intelligenceService.complete(analysisId, sha256, response);
    }

    @Override
    public void onDeadLetter(long analysisId, String lastError) {
        intelligenceService.failIfUnfinished(analysisId, AI_UNAVAILABLE);
    }
}
