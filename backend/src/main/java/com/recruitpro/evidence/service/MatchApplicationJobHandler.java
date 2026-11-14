package com.recruitpro.evidence.service;

import com.recruitpro.ai.dto.AiMatchResponse;
import com.recruitpro.ai.service.AiServiceClient;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.service.BackgroundJobHandler;
import com.recruitpro.jobs.service.NonRetryableJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * MATCH_APPLICATION handler (entity id = applications.id, D7). Reads the inputs, calls the AI service's
 * {@code /api/v1/match} outside any transaction and appends a current match run (D8).
 *
 * <p>Transient AI failures (connection errors, time-outs, empty responses, HTTP 5xx, 429, 408) throw, so the job
 * is retried with backoff; other 4xx responses and a missing service token fail the job permanently (the
 * applicant then shows matchStatus FAILED until a rematch). Nothing is written when there is nothing to match.</p>
 */
@Component
public class MatchApplicationJobHandler implements BackgroundJobHandler {

    private static final Logger log = LoggerFactory.getLogger(MatchApplicationJobHandler.class);

    private final MatchingService matchingService;
    private final AiServiceClient aiServiceClient;

    public MatchApplicationJobHandler(MatchingService matchingService, AiServiceClient aiServiceClient) {
        this.matchingService = matchingService;
        this.aiServiceClient = aiServiceClient;
    }

    @Override
    public BackgroundJobType type() {
        return BackgroundJobType.MATCH_APPLICATION;
    }

    @Override
    public void handle(long applicationId) throws Exception {
        PreparedMatch prepared = matchingService.prepare(applicationId);
        if (prepared == null) {
            log.debug("Application {} has nothing to match", applicationId);
            return;
        }
        AiMatchResponse response;
        try {
            response = aiServiceClient.match(prepared.request());
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status >= 500 || status == 429 || status == 408) {
                throw new IllegalStateException("AI service responded with HTTP " + status);
            }
            throw new NonRetryableJobException("AI service rejected the match request (HTTP " + status + ")");
        } catch (RestClientException ex) {
            throw new IllegalStateException("AI service unavailable (" + ex.getClass().getSimpleName() + ")");
        } catch (IllegalStateException ex) {
            if (AiServiceClient.NOT_CONFIGURED_MESSAGE.equals(ex.getMessage())) {
                throw new NonRetryableJobException(AiServiceClient.NOT_CONFIGURED_MESSAGE);
            }
            throw ex;
        }
        if (response == null || response.requirements() == null) {
            throw new IllegalStateException("AI service returned an empty match response");
        }
        matchingService.persist(prepared, response);
    }
}
