package com.recruitpro.verification.service;

import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import com.recruitpro.verification.dto.EvidenceItemResponse;
import com.recruitpro.verification.dto.ReviewView;
import com.recruitpro.verification.dto.StaffVerdict;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.ClaimVerdictReview;
import com.recruitpro.verification.entity.EvidenceItem;
import com.recruitpro.verification.entity.ReviewDecision;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.repository.ClaimVerdictReviewRepository;
import com.recruitpro.verification.repository.EvidenceItemRepository;
import com.recruitpro.verification.repository.VerificationSourceRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared staff-side pieces: the connector of a verdict, an organisation's latest reviews, the tier rule (plan v2
 * 3.5) and the blind-first StaffVerdict view. Callers provide the transaction.
 */
@Component
public class VerificationStaffSupport {

    private final VerificationSourceRepository sourceRepository;
    private final ClaimVerdictReviewRepository reviewRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final UserRepository userRepository;
    private final VerificationViews views;
    private final VerificationSubjects subjects;

    public VerificationStaffSupport(VerificationSourceRepository sourceRepository, ClaimVerdictReviewRepository reviewRepository,
                                    EvidenceItemRepository evidenceRepository, UserRepository userRepository,
                                    VerificationViews views, VerificationSubjects subjects) {
        this.sourceRepository = sourceRepository;
        this.reviewRepository = reviewRepository;
        this.evidenceRepository = evidenceRepository;
        this.userRepository = userRepository;
        this.views = views;
        this.subjects = subjects;
    }

    /**
     * The verdicts whose claim subject is still current (a fact of the resume's current analysis, or a claim that
     * still exists), in their order. A recruiter's rejection is per organisation and is applied by the caller where it
     * matters (matching: {@link VerificationEvidenceContributor}; staff view: the claim's tier for the organisation). Orphaned verdicts, e.g. of facts of a replaced
     * analysis, are left out of the staff view, the shadow queue and matching (review fix 16 Nov 2026).
     */
    public List<ClaimVerdict> withCurrentSubjects(Collection<ClaimVerdict> verdicts) {
        Map<Long, Set<String>> refsByCandidate = new HashMap<>();
        List<ClaimVerdict> kept = new ArrayList<>();
        for (ClaimVerdict verdict : verdicts) {
            Set<String> refs = refsByCandidate.computeIfAbsent(verdict.getCandidateId(),
                    id -> subjects.currentSubjectsByRef(id).keySet());
            if (refs.contains(verdict.subjectRef())) {
                kept.add(verdict);
            }
        }
        return kept;
    }

    /**
     * Tier rule (plan v2 3.5): the verdict lifts its claim to CORROBORATED for the organisation only when it is
     * VERIFIED with proven ownership (references: independent referee), current and SHARED by the candidate, the
     * organisation is VISIBLE with the verdict's connector enabled, and the organisation's latest review CONFIRMs it.
     * "VERIFIED" is the verdict as it counts for the organisation ({@link DomainTrusts#effectiveVerdict}): a verdict
     * capped only by an employer / issuer domain the agent could not establish counts when the organisation lists it.
     */
    public static boolean lifts(ClaimVerdict verdict, ClaimVerdictReview latestReview,
                                VerificationSettingsService.Effective settings, String connector) {
        return verdict != null && DomainTrusts.effectiveVerdict(verdict, settings) == VerdictValue.VERIFIED
                && verdict.isOwnershipProven()
                && verdict.isCurrent() && verdict.getCandidateStatus() == CandidateVerdictStatus.SHARED
                && settings != null && settings.visible() && settings.enabled(connector)
                && latestReview != null && latestReview.getDecision() == ReviewDecision.CONFIRM;
    }

    /** Connector of each verdict: the source kind (GITHUB, ...) or REFERENCE; null when its source is gone. */
    public Map<Long, String> connectors(Collection<ClaimVerdict> verdicts) {
        Set<Long> sourceIds = new HashSet<>();
        for (ClaimVerdict verdict : verdicts) {
            if (verdict.getSourceId() != null) {
                sourceIds.add(verdict.getSourceId());
            }
        }
        Map<Long, String> kinds = new HashMap<>();
        for (VerificationSource source : sourceRepository.findAllById(sourceIds)) {
            if (!source.isRevoked()) {
                kinds.put(source.getId(), source.getKind().name());
            }
        }
        Map<Long, String> connectors = new HashMap<>();
        for (ClaimVerdict verdict : verdicts) {
            String connector = verdict.getReferenceId() != null ? VerificationSettingsService.REFERENCE_CONNECTOR
                    : verdict.getSourceId() == null ? null : kinds.get(verdict.getSourceId());
            if (connector != null) {
                connectors.put(verdict.getId(), connector);
            }
        }
        return connectors;
    }

    /** The organisation's latest review of each verdict. */
    public Map<Long, ClaimVerdictReview> latestReviews(Collection<Long> verdictIds, Long organizationId) {
        Map<Long, ClaimVerdictReview> latest = new HashMap<>();
        if (verdictIds.isEmpty() || organizationId == null) {
            return latest;
        }
        for (ClaimVerdictReview review : reviewRepository.findAllByVerdictIdInAndOrganizationIdOrderByIdAsc(verdictIds, organizationId)) {
            latest.put(review.getVerdictId(), review);
        }
        return latest;
    }

    /** Display names of the reviewers of the given reviews. */
    public Map<Long, String> reviewerNames(Collection<ClaimVerdictReview> reviews) {
        Set<Long> ids = new HashSet<>();
        for (ClaimVerdictReview review : reviews) {
            if (review.getReviewerUserId() != null) {
                ids.add(review.getReviewerUserId());
            }
        }
        Map<Long, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(ids)) {
            names.put(user.getId(), ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                    + (user.getLastName() == null ? "" : user.getLastName())).trim());
        }
        return names;
    }

    /** Evidence items of a verdict in citation order. */
    public List<EvidenceItemResponse> evidence(ClaimVerdict verdict) {
        List<Long> ids = views.ids(verdict.getEvidenceItemIdsJson());
        List<EvidenceItemResponse> items = new ArrayList<>();
        if (ids.isEmpty()) {
            return items;
        }
        Map<Long, EvidenceItem> byId = new HashMap<>();
        for (EvidenceItem item : evidenceRepository.findAllById(ids)) {
            byId.put(item.getId(), item);
        }
        for (Long id : ids) {
            EvidenceItem item = byId.get(id);
            if (item != null) {
                items.add(views.evidence(item));
            }
        }
        return items;
    }

    /**
     * Blind-first staff view of a shared verdict: before the identity is revealed, evidence items (URLs, usernames,
     * excerpts) and the candidate's own note are withheld; {@code evidenceCount} is always given.
     */
    public StaffVerdict staffVerdict(ClaimVerdict verdict, ClaimVerdictReview review, Map<Long, String> reviewerNames,
                                     String connector, boolean revealed) {
        return staffVerdict(verdict, review, reviewerNames, connector, revealed, null);
    }

    /**
     * As above, for an organisation's settings: {@code verdict} is the verdict as it counts for the organisation and
     * {@code domainTrust} reflects its trusted-domain lists ({@link DomainTrusts}). Without settings the stored verdict
     * and the agent's own domain trust are shown.
     */
    public StaffVerdict staffVerdict(ClaimVerdict verdict, ClaimVerdictReview review, Map<Long, String> reviewerNames,
                                     String connector, boolean revealed, VerificationSettingsService.Effective settings) {
        ReviewView myReview = review == null ? null : new ReviewView(review.getDecision().name(), review.getNote(),
                review.getReviewerUserId() == null ? null : reviewerNames.get(review.getReviewerUserId()), review.getCreatedAt());
        int evidenceCount = views.ids(verdict.getEvidenceItemIdsJson()).size();
        VerdictValue value = DomainTrusts.effectiveVerdict(verdict, settings);
        return new StaffVerdict(verdict.getId(), verdict.getVerifier(), value.name(), verdict.isOwnershipProven(),
                views.reasons(verdict.getReasonsJson()), views.notes(verdict.getIntegrityNotesJson()), verdict.getProbeQuestion(),
                revealed ? verdict.getCandidateNote() : null, myReview, revealed ? evidence(verdict) : null, evidenceCount,
                connector, verdict.getCreatedAt(), DomainTrusts.view(verdict, settings, revealed));
    }
}
