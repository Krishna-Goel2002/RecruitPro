package com.recruitpro.verification.service;

import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.candidate.service.CandidateProfileService;
import com.recruitpro.common.crypto.SecretCipher;
import com.recruitpro.common.exception.DuplicateResourceException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.exception.TooManyRequestsException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.service.BackgroundJobService;
import com.recruitpro.user.entity.User;
import com.recruitpro.verification.client.AgentModels;
import com.recruitpro.verification.dto.RunResponse;
import com.recruitpro.verification.entity.RunRequester;
import com.recruitpro.verification.entity.VerificationRun;
import com.recruitpro.verification.entity.VerificationRunStatus;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.VerificationRunRepository;
import com.recruitpro.verification.repository.VerificationSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Verification runs (plan v2 section 5): starting one (one active run per source, at most
 * {@code app.verification.max-runs-per-day} (10) runs per candidate in 24 hours) and preparing a run's work for
 * the VERIFY_SOURCE job.
 */
@Service
public class VerificationRunService {

    static final Set<VerificationRunStatus> ACTIVE = EnumSet.of(VerificationRunStatus.PENDING, VerificationRunStatus.RUNNING);
    private static final Logger log = LoggerFactory.getLogger(VerificationRunService.class);

    private final CandidateProfileService profileService;
    private final CandidateProfileRepository profileRepository;
    private final VerificationRunRepository runRepository;
    private final VerificationSourceRepository sourceRepository;
    private final VerificationSourceService sourceService;
    private final VerificationSubjects subjects;
    private final BackgroundJobService backgroundJobService;
    private final SecretCipher cipher;
    private final int maxRunsPerDay;

    public VerificationRunService(CandidateProfileService profileService, CandidateProfileRepository profileRepository,
                                  VerificationRunRepository runRepository, VerificationSourceRepository sourceRepository,
                                  VerificationSourceService sourceService, VerificationSubjects subjects,
                                  BackgroundJobService backgroundJobService, SecretCipher cipher,
                                  @Value("${app.verification.max-runs-per-day:10}") int maxRunsPerDay) {
        this.profileService = profileService;
        this.profileRepository = profileRepository;
        this.runRepository = runRepository;
        this.sourceRepository = sourceRepository;
        this.sourceService = sourceService;
        this.subjects = subjects;
        this.backgroundJobService = backgroundJobService;
        this.cipher = cipher;
        this.maxRunsPerDay = Math.max(1, maxRunsPerDay);
    }

    /** POST /sources/{id}/runs (candidate). */
    @Transactional
    public RunResponse startMine(Long sourceId) {
        CandidateProfile me = profileService.getOrProvisionMine();
        return start(me.getId(), sourceId, RunRequester.CANDIDATE);
    }

    /** GET /runs/{id} (candidate). */
    @Transactional
    public RunResponse getMine(Long runId) {
        CandidateProfile me = profileService.getOrProvisionMine();
        VerificationRun run = runRepository.findById(runId).filter(r -> r.getCandidateId().equals(me.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Verification run not found"));
        return response(run);
    }

    /**
     * Starts a run of an active source and queues VERIFY_SOURCE in the caller's transaction. 409 when a run of the
     * source is PENDING or RUNNING; 429 after {@code maxRunsPerDay} runs of the candidate in the last 24 hours.
     */
    @Transactional
    public RunResponse start(Long candidateId, Long sourceId, RunRequester requester) {
        sourceService.lock(candidateId);
        VerificationSource source = sourceService.activeSource(candidateId, sourceId);
        if (runRepository.existsBySourceIdAndStatusIn(source.getId(), ACTIVE)) {
            throw new DuplicateResourceException("A check of this source is already running");
        }
        if (runRepository.countByCandidateIdAndCreatedAtAfter(candidateId, UtcTime.now().minusHours(24)) >= maxRunsPerDay) {
            throw new TooManyRequestsException("You can run at most " + maxRunsPerDay + " checks in 24 hours. Please try again later.");
        }
        VerificationRun run = new VerificationRun();
        run.setSourceId(source.getId());
        run.setCandidateId(candidateId);
        run.setStatus(VerificationRunStatus.PENDING);
        run.setRequestedBy(requester == null ? RunRequester.CANDIDATE : requester);
        VerificationRun saved = runRepository.save(run);
        backgroundJobService.enqueue(BackgroundJobType.VERIFY_SOURCE, saved.getId());
        return response(saved);
    }

    public static RunResponse response(VerificationRun run) {
        return new RunResponse(run.getId(), run.getSourceId(), run.getStatus().name(),
                run.getRequestedBy() == null ? null : run.getRequestedBy().name(), run.getVerifier(), run.getCreatedAt(),
                run.getStartedAt(), run.getFinishedAt(), run.getErrorCode());
    }

    // ------------------------------------------------------------------------------------------ job support

    /** Everything the VERIFY_SOURCE handler needs to call the agent (read in one transaction). */
    public record WorkItem(long runId, Long sourceId, Long candidateId, VerificationSourceKind kind, String identifier,
                           boolean ownershipProven, String accessToken, String filePath, String credentialText,
                           List<AgentModels.Claim> claims, String holderName, String holderEmail) {
        @Override
        public String toString() {
            return "WorkItem[runId=" + runId + ", kind=" + kind + ", claims=" + (claims == null ? 0 : claims.size()) + "]";
        }
    }

    /**
     * Moves a PENDING (or interrupted RUNNING) run to RUNNING and returns its work; null when the run is missing or
     * finished. A run of a revoked source fails with SOURCE_REVOKED.
     */
    @Transactional
    public WorkItem claim(long runId) {
        VerificationRun run = runRepository.findById(runId).orElse(null);
        if (run == null || !run.isActive()) {
            return null;
        }
        VerificationSource source = sourceRepository.findById(run.getSourceId()).orElse(null);
        LocalDateTime now = UtcTime.now();
        if (source == null || source.isRevoked()) {
            run.setStatus(VerificationRunStatus.FAILED);
            run.setErrorCode(VerificationResultWriter.SOURCE_REVOKED);
            run.setFinishedAt(now);
            return null;
        }
        run.setStatus(VerificationRunStatus.RUNNING);
        if (run.getStartedAt() == null) {
            run.setStartedAt(now);
        }
        CandidateProfile profile = profileRepository.findById(run.getCandidateId()).orElse(null);
        User user = profile == null ? null : profile.getUser();
        String holderName = user == null ? null : (nullToEmpty(user.getFirstName()) + " " + nullToEmpty(user.getLastName())).trim();
        String holderEmail = user == null ? null : user.getEmail();
        boolean codeHost = source.getKind() == VerificationSourceKind.GITHUB || source.getKind() == VerificationSourceKind.GITLAB;
        List<AgentModels.Claim> claims = new ArrayList<>();
        for (VerificationSubjects.Subject subject : subjects.currentSubjects(run.getCandidateId())) {
            // Code hosts only evidence skills; other claim types would all come back NOT_CHECKABLE.
            if (codeHost && subject.claimType() != ClaimType.SKILL) {
                continue;
            }
            if (claims.size() >= VerificationSubjects.MAX_CLAIMS) {
                break;
            }
            claims.add(new AgentModels.Claim(subject.ref(), subject.claimType().name(), subject.value()));
        }
        return new WorkItem(run.getId(), source.getId(), run.getCandidateId(), source.getKind(), source.getIdentifier(),
                source.isOwnershipProven(), decryptToken(source), source.getFilePath(), source.getCredentialText(),
                claims, holderName == null || holderName.isBlank() ? null : holderName, holderEmail);
    }

    private String decryptToken(VerificationSource source) {
        if (source.getAccessTokenEnc() == null || !cipher.isConfigured()) {
            return null;
        }
        try {
            return cipher.decrypt(source.getAccessTokenEnc(), GitHubOAuthService.tokenContext(source.getId()));
        } catch (IllegalStateException ex) {
            log.warn("The access token of verification source {} could not be decrypted; running without it", source.getId());
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
