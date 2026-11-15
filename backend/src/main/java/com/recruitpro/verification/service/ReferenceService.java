package com.recruitpro.verification.service;

import com.recruitpro.audit.service.AuditService;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.candidate.service.CandidateProfileService;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.exception.TooManyRequestsException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.service.BackgroundJobService;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.service.NotificationRecipient;
import com.recruitpro.notification.service.NotificationService;
import com.recruitpro.user.entity.User;
import com.recruitpro.verification.dto.PublicReferenceAnswer;
import com.recruitpro.verification.dto.PublicReferenceClaim;
import com.recruitpro.verification.dto.PublicReferenceResponse;
import com.recruitpro.verification.dto.PublicReferenceSubmitRequest;
import com.recruitpro.verification.dto.ReferenceClaim;
import com.recruitpro.verification.dto.ReferenceCreateRequest;
import com.recruitpro.verification.dto.ReferenceResponse;
import com.recruitpro.verification.dto.SubjectRefRequest;
import com.recruitpro.verification.entity.ClaimSubjectRef;
import com.recruitpro.verification.entity.ReferenceRelationship;
import com.recruitpro.verification.entity.ReferenceStatus;
import com.recruitpro.verification.entity.SubjectType;
import com.recruitpro.verification.entity.VerificationReferenceRequest;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.VerificationReferenceRequestRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reference requests (plan v2 sections 3.2 and 5). The candidate names a referee and the claims to confirm; the
 * referee gets a one-time link {@code <candidate-url>/reference/<token>} (32 random bytes, only the SHA-256 is
 * stored, 14-day expiry) and answers without an account, or declines. An answer queues VERIFY_REFERENCE.
 */
@Service
public class ReferenceService {

    public static final int MAX_OPEN = 5;
    public static final int MAX_CLAIMS = 20;
    public static final Duration TTL = Duration.ofDays(14);
    static final Set<String> ANSWERS = Set.of("CONFIRM", "PARTLY", "CANNOT_CONFIRM", "DISAGREE");
    static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$");
    static final Pattern DOMAIN = Pattern.compile("^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,62}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CandidateProfileService profileService;
    private final CandidateProfileRepository profileRepository;
    private final VerificationReferenceRequestRepository referenceRepository;
    private final ClaimVerdictRepository verdictRepository;
    private final VerificationSubjects subjects;
    private final VerificationSourceService sourceService;
    private final VerificationViews views;
    private final NotificationService notificationService;
    private final BackgroundJobService backgroundJobService;
    private final AuditService auditService;
    private final String candidateUrl;

    public ReferenceService(CandidateProfileService profileService, CandidateProfileRepository profileRepository,
                            VerificationReferenceRequestRepository referenceRepository, ClaimVerdictRepository verdictRepository,
                            VerificationSubjects subjects, VerificationSourceService sourceService, VerificationViews views,
                            NotificationService notificationService, BackgroundJobService backgroundJobService,
                            AuditService auditService,
                            @Value("${app.frontend.candidate-url:http://localhost:5173}") String candidateUrl) {
        this.profileService = profileService;
        this.profileRepository = profileRepository;
        this.referenceRepository = referenceRepository;
        this.verdictRepository = verdictRepository;
        this.subjects = subjects;
        this.sourceService = sourceService;
        this.views = views;
        this.notificationService = notificationService;
        this.backgroundJobService = backgroundJobService;
        this.auditService = auditService;
        this.candidateUrl = candidateUrl.endsWith("/") ? candidateUrl.substring(0, candidateUrl.length() - 1) : candidateUrl;
    }

    // ----------------------------------------------------------------------------------------- candidate side

    @Transactional
    public List<ReferenceResponse> listMine() {
        CandidateProfile me = profileService.getOrProvisionMine();
        List<ReferenceResponse> responses = new ArrayList<>();
        LocalDateTime now = UtcTime.now();
        for (VerificationReferenceRequest reference : referenceRepository.findAllByCandidateIdOrderByCreatedAtDescIdDesc(me.getId())) {
            expireIfDue(reference, now);
            responses.add(response(reference));
        }
        return responses;
    }

    /** POST /references: consent, at most {@value #MAX_OPEN} open requests; e-mails the referee. */
    @Transactional
    public ReferenceResponse create(ReferenceCreateRequest request) {
        CandidateProfile me = profileService.getOrProvisionMine();
        if (request == null) {
            throw new BadRequestException("A request body is required");
        }
        if (!Boolean.TRUE.equals(request.consent())) {
            throw new BadRequestException("Consent is required to contact a referee");
        }
        String name = trimmed(request.refereeName());
        if (name == null || name.length() > 200) {
            throw new BadRequestException("refereeName is required (at most 200 characters)");
        }
        String email = trimmed(request.refereeEmail());
        if (email == null || email.length() > 255 || !EMAIL.matcher(email).matches()) {
            throw new BadRequestException("refereeEmail must be a valid e-mail address");
        }
        email = email.toLowerCase(Locale.ROOT);
        User user = me.getUser();
        if (user.getEmail() != null && user.getEmail().equalsIgnoreCase(email)) {
            throw new BadRequestException("You cannot be your own referee");
        }
        ReferenceRelationship relationship = parseRelationship(request.relationship());
        String employerName = trimmed(request.employerName());
        if (employerName != null && employerName.length() > 200) {
            throw new BadRequestException("employerName must be at most 200 characters");
        }
        String employerDomain = trimmed(request.employerDomain());
        if (employerDomain != null) {
            employerDomain = employerDomain.toLowerCase(Locale.ROOT);
            if (employerDomain.startsWith("www.")) {
                employerDomain = employerDomain.substring(4);
            }
            if (!DOMAIN.matcher(employerDomain).matches()) {
                throw new BadRequestException("employerDomain must be a domain name such as example.com");
            }
        }
        if (request.claims() == null || request.claims().isEmpty() || request.claims().size() > MAX_CLAIMS) {
            throw new BadRequestException("Choose 1 to " + MAX_CLAIMS + " claims for the referee to confirm");
        }
        Map<String, VerificationSubjects.Subject> current = subjects.currentSubjectsByRef(me.getId());
        List<ReferenceClaim> claims = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SubjectRefRequest ref : request.claims()) {
            SubjectType type = ref == null ? null : VerificationResultWriter.parseSubjectType(ref.subjectType());
            String key = type == null || ref.subjectId() == null ? null : ClaimSubjectRef.of(type, ref.subjectId());
            VerificationSubjects.Subject subject = key == null ? null : current.get(key);
            if (subject == null) {
                throw new BadRequestException("Every claim must be one of your current claims");
            }
            if (seen.add(key)) {
                claims.add(new ReferenceClaim(subject.subjectType().name(), subject.subjectId(), subject.claimType().name(),
                        subject.value()));
            }
        }
        sourceService.lock(me.getId());
        LocalDateTime now = UtcTime.now();
        if (referenceRepository.countByCandidateIdAndStatusAndExpiresAtAfter(me.getId(), ReferenceStatus.SENT, now) >= MAX_OPEN) {
            throw new TooManyRequestsException("You can have at most " + MAX_OPEN + " open reference requests");
        }
        String token = newToken();
        VerificationReferenceRequest reference = new VerificationReferenceRequest();
        reference.setCandidateId(me.getId());
        reference.setRefereeName(name);
        reference.setRefereeEmail(email);
        reference.setRelationship(relationship);
        reference.setEmployerName(employerName);
        reference.setEmployerDomain(employerDomain);
        reference.setClaimRefsJson(views.write(claims));
        reference.setTokenHash(sha256Hex(token));
        reference.setStatus(ReferenceStatus.SENT);
        reference.setExpiresAt(now.plus(TTL));
        reference.setConsentedAt(now);
        VerificationReferenceRequest saved = referenceRepository.save(reference);
        String candidateName = (nullToEmpty(user.getFirstName()) + " " + nullToEmpty(user.getLastName())).trim();
        Map<String, String> params = new HashMap<>();
        params.put("refereeName", name);
        params.put("candidateName", candidateName.isEmpty() ? "A RecruitPro candidate" : candidateName);
        params.put("relationship", relationship.name().toLowerCase(Locale.ROOT));
        params.put("expiresAt", UtcTime.format(saved.getExpiresAt()));
        params.put("link", candidateUrl + "/reference/" + token);
        notificationService.notify(NotificationRecipient.email(email, null), NotificationTemplate.REFERENCE_REQUEST, params);
        auditService.record(VerificationAuditActions.REFERENCE_REQUESTED, "REFERENCE_REQUEST", String.valueOf(saved.getId()),
                "Reference requested (" + relationship + ", " + claims.size() + " claim(s))");
        return response(saved);
    }

    /** POST /references/{id}/cancel: SENT requests only. */
    @Transactional
    public ReferenceResponse cancel(Long referenceId) {
        CandidateProfile me = profileService.getOrProvisionMine();
        VerificationReferenceRequest reference = referenceRepository.findByIdAndCandidateId(referenceId, me.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Reference request not found"));
        expireIfDue(reference, UtcTime.now());
        if (reference.getStatus() != ReferenceStatus.SENT) {
            throw new BadRequestException("Only a reference request that is still waiting for an answer can be cancelled");
        }
        reference.setStatus(ReferenceStatus.CANCELLED);
        return response(reference);
    }

    // -------------------------------------------------------------------------------------------- public side

    /** GET /api/v1/public/references/{token}; 404 unknown token, 410 expired, answered, declined or cancelled. */
    @Transactional
    public PublicReferenceResponse view(String token) {
        VerificationReferenceRequest reference = open(token);
        CandidateProfile profile = profileRepository.findById(reference.getCandidateId()).orElse(null);
        String firstName = profile == null || profile.getUser() == null ? null : profile.getUser().getFirstName();
        List<PublicReferenceClaim> claims = new ArrayList<>();
        for (ReferenceClaim claim : views.referenceClaims(reference.getClaimRefsJson())) {
            SubjectType type = VerificationResultWriter.parseSubjectType(claim.subjectType());
            if (type != null && claim.subjectId() != null) {
                claims.add(new PublicReferenceClaim(ClaimSubjectRef.of(type, claim.subjectId()), claim.claimType(), claim.value()));
            }
        }
        return new PublicReferenceResponse(firstName, reference.getRelationship().name(), reference.getEmployerName(),
                claims, reference.getExpiresAt());
    }

    /**
     * POST /api/v1/public/references/{token}: answers (every claim at most once, CONFIRM / PARTLY / CANNOT_CONFIRM /
     * DISAGREE, comment at most 2000) with {@code confirmIdentity: true}, or {@code decline: true}. Answered once.
     */
    @Transactional
    public void submit(String token, PublicReferenceSubmitRequest request) {
        VerificationReferenceRequest reference = open(token);
        if (request == null) {
            throw new BadRequestException("A request body is required");
        }
        LocalDateTime now = UtcTime.now();
        if (Boolean.TRUE.equals(request.decline())) {
            reference.setStatus(ReferenceStatus.DECLINED);
            reference.setAnsweredAt(now);
            notifyCandidate(reference, "They declined to give a reference. You can ask someone else.");
            return;
        }
        if (!Boolean.TRUE.equals(request.confirmIdentity())) {
            throw new BadRequestException("Please confirm that you are the person this request was sent to");
        }
        Map<String, ReferenceClaim> asked = new LinkedHashMap<>();
        for (ReferenceClaim claim : views.referenceClaims(reference.getClaimRefsJson())) {
            SubjectType type = VerificationResultWriter.parseSubjectType(claim.subjectType());
            if (type != null && claim.subjectId() != null) {
                asked.put(ClaimSubjectRef.of(type, claim.subjectId()), claim);
            }
        }
        if (request.answers() == null || request.answers().isEmpty()) {
            throw new BadRequestException("Answer at least one statement, or decline");
        }
        List<PublicReferenceAnswer> answers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PublicReferenceAnswer answer : request.answers()) {
            String claimId = answer == null ? null : trimmed(answer.claimId());
            if (claimId == null || !asked.containsKey(claimId) || !seen.add(claimId)) {
                throw new BadRequestException("Each answer must refer to a different statement of this request");
            }
            String value = answer.answer() == null ? "" : answer.answer().trim().toUpperCase(Locale.ROOT);
            if (!ANSWERS.contains(value)) {
                throw new BadRequestException("answer must be CONFIRM, PARTLY, CANNOT_CONFIRM or DISAGREE");
            }
            String comment = trimmed(answer.comment());
            if (comment != null && comment.length() > 2000) {
                throw new BadRequestException("Comments are limited to 2000 characters");
            }
            answers.add(new PublicReferenceAnswer(claimId, value, comment));
        }
        String refereeComment = trimmed(request.refereeComment());
        if (refereeComment != null && refereeComment.length() > 2000) {
            throw new BadRequestException("Comments are limited to 2000 characters");
        }
        reference.setStatus(ReferenceStatus.ANSWERED);
        reference.setAnswersJson(views.write(answers));
        reference.setRefereeComment(refereeComment);
        reference.setAnsweredAt(now);
        backgroundJobService.enqueue(BackgroundJobType.VERIFY_REFERENCE, reference.getId());
    }

    /** The request behind a token if it can still be answered. */
    private VerificationReferenceRequest open(String token) {
        if (token == null || token.isBlank() || token.length() > 100) {
            throw new ResourceNotFoundException("Reference request not found");
        }
        VerificationReferenceRequest reference = referenceRepository.findByTokenHash(sha256Hex(token.trim()))
                .orElseThrow(() -> new ResourceNotFoundException("Reference request not found"));
        // An overdue request answers 410 at once; the expiry sweep (or the candidate's list) persists EXPIRED.
        boolean overdue = reference.getStatus() == ReferenceStatus.SENT && reference.getExpiresAt() != null
                && reference.getExpiresAt().isBefore(UtcTime.now());
        if (overdue) {
            throw new ResponseStatusException(HttpStatus.GONE, "This reference request has expired");
        }
        if (reference.getStatus() != ReferenceStatus.SENT) {
            throw new ResponseStatusException(HttpStatus.GONE, reference.getStatus() == ReferenceStatus.EXPIRED
                    ? "This reference request has expired" : "This reference request is closed");
        }
        return reference;
    }

    // ---------------------------------------------------------------------------------------------- helpers

    /** Marks a SENT request past its expiry EXPIRED; true when it changed. */
    static boolean expireIfDue(VerificationReferenceRequest reference, LocalDateTime now) {
        if (reference.getStatus() == ReferenceStatus.SENT && reference.getExpiresAt() != null && reference.getExpiresAt().isBefore(now)) {
            reference.setStatus(ReferenceStatus.EXPIRED);
            return true;
        }
        return false;
    }

    private ReferenceResponse response(VerificationReferenceRequest reference) {
        int verdicts = reference.getId() == null ? 0 : verdictRepository.findAllByReferenceIdOrderByIdAsc(reference.getId()).size();
        return new ReferenceResponse(reference.getId(), reference.getRefereeName(), reference.getRefereeEmail(),
                reference.getRelationship().name(), reference.getEmployerName(), reference.getEmployerDomain(),
                views.referenceClaims(reference.getClaimRefsJson()), reference.getStatus().name(), reference.getExpiresAt(),
                reference.getAnsweredAt(), reference.getCreatedAt(), verdicts, reference.getErrorCode());
    }

    private void notifyCandidate(VerificationReferenceRequest reference, String message) {
        CandidateProfile profile = profileRepository.findById(reference.getCandidateId()).orElse(null);
        if (profile == null || profile.getUser() == null || profile.getUser().getDeletedAt() != null) {
            return;
        }
        User user = profile.getUser();
        Map<String, String> params = new HashMap<>();
        params.put("firstName", user.getFirstName());
        params.put("refereeName", reference.getRefereeName());
        params.put("message", message);
        params.put("link", candidateUrl + "/verification");
        notificationService.notify(NotificationRecipient.of(user), NotificationTemplate.REFERENCE_ANSWERED, params);
    }

    static ReferenceRelationship parseRelationship(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("relationship is required");
        }
        try {
            return ReferenceRelationship.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("relationship must be MANAGER, PEER, REPORT, CLIENT, TEACHER or OTHER");
        }
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
