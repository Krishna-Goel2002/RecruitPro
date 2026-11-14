package com.recruitpro.ai;

import com.recruitpro.ai.dto.AiResumeParseResponse;
import com.recruitpro.ai.service.AiServiceClient;
import com.recruitpro.ai.service.ResumeParseJobHandler;
import com.recruitpro.candidate.service.ResumeIntelligenceService;
import com.recruitpro.candidate.storage.ResumeStorage;
import com.recruitpro.jobs.entity.BackgroundJobType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PARSE_RESUME handler: transient AI failures release the analysis and throw (the job retries); permanent
 * outcomes complete or fail the analysis and return normally.
 */
class ResumeParseJobHandlerTest {

    private static final long ANALYSIS_ID = 31L;

    @Test
    void completesTheAnalysisWithTheParserResult() throws Exception {
        RecordingIntelligence intelligence = new RecordingIntelligence(true);
        AiResumeParseResponse result = new AiResumeParseResponse("COMPLETED", null, "rules-2", List.of(), List.of(), null);
        handler(intelligence, () -> result).handle(ANALYSIS_ID);
        assertEquals(List.of("claim", "complete"), intelligence.calls);
        assertEquals(64, intelligence.sha256.length());
    }

    @Test
    void nothingHappensWhenTheAnalysisIsNoLongerWaiting() throws Exception {
        RecordingIntelligence intelligence = new RecordingIntelligence(false);
        handler(intelligence, () -> { throw new AssertionError("AI service must not be called"); }).handle(ANALYSIS_ID);
        assertEquals(List.of("claim"), intelligence.calls);
    }

    @Test
    void serverErrorsAndConnectionFailuresAreRetried() {
        RecordingIntelligence unavailable = new RecordingIntelligence(true);
        assertThrows(IllegalStateException.class, () -> handler(unavailable,
                () -> { throw new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE); }).handle(ANALYSIS_ID));
        assertEquals(List.of("claim", "release:AI_SERVICE_UNAVAILABLE"), unavailable.calls);

        RecordingIntelligence refused = new RecordingIntelligence(true);
        assertThrows(IllegalStateException.class, () -> handler(refused,
                () -> { throw new ResourceAccessException("Connection refused"); }).handle(ANALYSIS_ID));
        assertEquals(List.of("claim", "release:AI_SERVICE_UNAVAILABLE"), refused.calls);

        RecordingIntelligence empty = new RecordingIntelligence(true);
        assertThrows(IllegalStateException.class, () -> handler(empty, () -> null).handle(ANALYSIS_ID));
        assertEquals(List.of("claim", "release:AI_SERVICE_UNAVAILABLE"), empty.calls);
    }

    @Test
    void rejectedInputMissingFilesAndMissingTokenFailPermanently() throws Exception {
        RecordingIntelligence rejected = new RecordingIntelligence(true);
        handler(rejected, () -> { throw new HttpClientErrorException(HttpStatus.BAD_REQUEST); }).handle(ANALYSIS_ID);
        assertEquals(List.of("claim", "fail:AI_SERVICE_REJECTED"), rejected.calls);

        RecordingIntelligence missingFile = new RecordingIntelligence(true);
        new ResumeParseJobHandler(missingFile, storage(true), client(() -> null)).handle(ANALYSIS_ID);
        assertEquals(List.of("claim", "fail:FILE_UNAVAILABLE"), missingFile.calls);

        RecordingIntelligence unconfigured = new RecordingIntelligence(true);
        handler(unconfigured, () -> { throw new IllegalStateException(AiServiceClient.NOT_CONFIGURED_MESSAGE); }).handle(ANALYSIS_ID);
        assertEquals(List.of("claim", "fail:AI_SERVICE_AUTHENTICATION_NOT_CONFIGURED"), unconfigured.calls);
    }

    @Test
    void deadLetterMarksAnUnfinishedAnalysisAsFailed() {
        RecordingIntelligence intelligence = new RecordingIntelligence(true);
        ResumeParseJobHandler handler = handler(intelligence, () -> null);
        assertEquals(BackgroundJobType.PARSE_RESUME, handler.type());
        handler.onDeadLetter(ANALYSIS_ID, "IllegalStateException: AI service unavailable");
        assertEquals(List.of("failIfUnfinished:AI_SERVICE_UNAVAILABLE"), intelligence.calls);
    }

    private ResumeParseJobHandler handler(RecordingIntelligence intelligence, Supplier<AiResumeParseResponse> ai) {
        return new ResumeParseJobHandler(intelligence, storage(false), client(ai));
    }

    private static ResumeStorage storage(boolean missing) {
        return new ResumeStorage() {
            @Override public void store(String storageKey, byte[] content) { }
            @Override public byte[] load(String storageKey) throws IOException {
                if (missing) throw new NoSuchFileException(storageKey);
                return "%PDF-1.7\nresume\n%%EOF".getBytes();
            }
            @Override public void delete(String storageKey) { }
        };
    }

    private static AiServiceClient client(Supplier<AiResumeParseResponse> response) {
        return new AiServiceClient(RestClient.builder(), "http://127.0.0.1:1", "test-only-token", 1000) {
            @Override
            public AiResumeParseResponse parse(String filename, String contentType, byte[] bytes) {
                return response.get();
            }
        };
    }

    /** Records the calls the handler makes on the resume intelligence service. */
    private static final class RecordingIntelligence extends ResumeIntelligenceService {
        private final boolean claimable;
        final List<String> calls = new ArrayList<>();
        String sha256;

        RecordingIntelligence(boolean claimable) {
            super(null, null, null, null, null, null, null, null);
            this.claimable = claimable;
        }

        @Override
        public ResumeIntelligenceService.WorkItem claimForProcessing(Long analysisId) {
            calls.add("claim");
            return claimable ? new ResumeIntelligenceService.WorkItem(analysisId, "00000000-0000-0000-0000-000000000000", "resume.pdf", "application/pdf") : null;
        }

        @Override
        public void complete(Long analysisId, String sha256, AiResumeParseResponse result) {
            calls.add("complete");
            this.sha256 = sha256;
        }

        @Override
        public void fail(Long analysisId, String errorCode) {
            calls.add("fail:" + errorCode);
        }

        @Override
        public void releaseForRetry(Long analysisId, String errorCode) {
            calls.add("release:" + errorCode);
        }

        @Override
        public void failIfUnfinished(Long analysisId, String errorCode) {
            calls.add("failIfUnfinished:" + errorCode);
        }
    }
}
