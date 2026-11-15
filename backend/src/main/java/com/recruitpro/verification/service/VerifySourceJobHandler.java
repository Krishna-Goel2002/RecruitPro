package com.recruitpro.verification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.service.BackgroundJobHandler;
import com.recruitpro.verification.client.AgentModels;
import com.recruitpro.verification.client.AgentServiceClient;
import com.recruitpro.verification.client.AgentServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.io.IOException;

/**
 * VERIFY_SOURCE handler (entity id = verification_runs.id). Reads the run's work in one transaction, calls the agent
 * service outside any transaction and stores the result in another ({@link VerificationResultWriter}).
 *
 * <ul>
 *   <li>A non-2xx answer with {@code detail.code} (e.g. URL_BLOCKED, RATE_LIMITED, TOKEN_INVALID) fails the run with
 *       that code, without retry (WP-AG note 6).</li>
 *   <li>Connection errors, time-outs and HTTP 5xx/408/429 without a code are retried with backoff; when retries are
 *       exhausted {@link #onDeadLetter} fails the run with AGENT_SERVICE_UNAVAILABLE.</li>
 *   <li>Other answers without a code (401, 403, 422 validation) fail the run with AGENT_SERVICE_REJECTED; a missing
 *       service token with AGENT_SERVICE_NOT_CONFIGURED; a missing document file with FILE_UNAVAILABLE.</li>
 * </ul>
 */
@Component
public class VerifySourceJobHandler implements BackgroundJobHandler {

    public static final String UNAVAILABLE = "AGENT_SERVICE_UNAVAILABLE";
    public static final String REJECTED = "AGENT_SERVICE_REJECTED";
    public static final String NOT_CONFIGURED = "AGENT_SERVICE_NOT_CONFIGURED";
    public static final String FILE_UNAVAILABLE = "FILE_UNAVAILABLE";
    private static final Logger log = LoggerFactory.getLogger(VerifySourceJobHandler.class);

    private final VerificationRunService runService;
    private final VerificationResultWriter resultWriter;
    private final VerificationStorage storage;
    private final AgentServiceClient agentClient;
    private final VerificationViews views;

    public VerifySourceJobHandler(VerificationRunService runService, VerificationResultWriter resultWriter,
                                  VerificationStorage storage, AgentServiceClient agentClient, VerificationViews views) {
        this.runService = runService;
        this.resultWriter = resultWriter;
        this.storage = storage;
        this.agentClient = agentClient;
        this.views = views;
    }

    @Override
    public BackgroundJobType type() {
        return BackgroundJobType.VERIFY_SOURCE;
    }

    @Override
    public void handle(long runId) {
        VerificationRunService.WorkItem work = runService.claim(runId);
        if (work == null) {
            log.debug("Verification run {} is not waiting; nothing to do", runId);
            return;
        }
        AgentModels.VerifyResponse response;
        try {
            response = call(work);
        } catch (DocumentMissing ex) {
            resultWriter.failRun(runId, FILE_UNAVAILABLE);
            return;
        } catch (AgentServiceException ex) {
            if (ex.code() != null) {
                resultWriter.failRun(runId, ex.code());
                return;
            }
            if (ex.isTransient()) {
                throw new IllegalStateException("Agent service responded with HTTP " + ex.status());
            }
            resultWriter.failRun(runId, REJECTED);
            return;
        } catch (RestClientException ex) {
            throw new IllegalStateException("Agent service unavailable (" + ex.getClass().getSimpleName() + ")");
        } catch (IllegalStateException ex) {
            if (AgentServiceClient.NOT_CONFIGURED_MESSAGE.equals(ex.getMessage())) {
                resultWriter.failRun(runId, NOT_CONFIGURED);
                return;
            }
            throw ex;
        }
        VerificationResultWriter.Outcome outcome = resultWriter.storeRunResult(runId, response);
        if (!outcome.completed()) {
            log.info("Verification run {} ended without result: {}", runId, outcome.errorCode());
        }
    }

    @Override
    public void onDeadLetter(long runId, String lastError) {
        resultWriter.failRun(runId, UNAVAILABLE);
    }

    private AgentModels.VerifyResponse call(VerificationRunService.WorkItem work) {
        return switch (work.kind()) {
            case GITHUB, GITLAB -> agentClient.verifyCode(new AgentModels.CodeVerifyRequest(
                    work.kind().name().toLowerCase(java.util.Locale.ROOT), work.identifier(), work.accessToken(),
                    work.ownershipProven(), work.claims(), null));
            case WEBSITE -> agentClient.verifyWeb(new AgentModels.WebVerifyRequest(work.identifier(),
                    work.ownershipProven(), work.claims(), work.holderName()));
            case DOCUMENT -> {
                byte[] bytes;
                try {
                    if (work.filePath() == null) {
                        throw new DocumentMissing();
                    }
                    bytes = storage.load(work.filePath());
                } catch (IOException ex) {
                    throw new DocumentMissing();
                }
                yield agentClient.verifyDocument(work.identifier(), bytes,
                        new AgentModels.DocumentVerifyRequest(work.claims(), work.holderName()));
            }
            case OPEN_BADGE -> {
                Object credential = null;
                String url = null;
                if (work.credentialText() != null) {
                    JsonNode parsed = work.credentialText().startsWith("{") ? views.json(work.credentialText()) : null;
                    credential = parsed != null && parsed.isObject() ? parsed : work.credentialText();
                } else {
                    url = work.identifier();
                }
                yield agentClient.verifyBadge(new AgentModels.BadgeVerifyRequest(credential, url, work.claims(),
                        work.holderName(), work.holderEmail(), work.candidateDomains()));
            }
        };
    }

    /** The uploaded document is no longer in storage. */
    private static final class DocumentMissing extends RuntimeException {
        DocumentMissing() {
            super("Document file is unavailable", null, false, false);
        }
    }
}
