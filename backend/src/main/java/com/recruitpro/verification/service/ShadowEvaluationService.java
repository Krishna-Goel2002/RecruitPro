package com.recruitpro.verification.service;

import com.recruitpro.application.entity.Application;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.hr.service.ApplicantQueryService;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.verification.dto.LabelView;
import com.recruitpro.verification.dto.ShadowQueueItem;
import com.recruitpro.verification.dto.StaffVerdict;
import com.recruitpro.verification.dto.VerdictLabelRequest;
import com.recruitpro.verification.dto.VerificationMetricsResponse;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.ClaimVerdictLabel;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.repository.ClaimVerdictLabelRepository;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.VerificationLookupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shadow evaluation (principle 9, plan v2 section 5): ADMIN and HR_MANAGER label the shared, current verdicts of
 * candidates who applied to their organisation (unlabelled by the caller first); the metrics compare verdicts with
 * the labels and say whether the organisation is ready for VISIBLE mode. Evidence items appear only for candidates
 * with a revealed application in the organisation (blind-first).
 */
@Service
public class ShadowEvaluationService {

    static final int MAX_PAGE_SIZE = 100;

    private final SecurityUtils securityUtils;
    private final OrganizationRepository organizationRepository;
    private final ClaimVerdictRepository verdictRepository;
    private final ClaimVerdictLabelRepository labelRepository;
    private final VerificationLookupRepository lookupRepository;
    private final VerificationStaffSupport support;

    public ShadowEvaluationService(SecurityUtils securityUtils, OrganizationRepository organizationRepository,
                                   ClaimVerdictRepository verdictRepository, ClaimVerdictLabelRepository labelRepository,
                                   VerificationLookupRepository lookupRepository, VerificationStaffSupport support) {
        this.securityUtils = securityUtils;
        this.organizationRepository = organizationRepository;
        this.verdictRepository = verdictRepository;
        this.labelRepository = labelRepository;
        this.lookupRepository = lookupRepository;
        this.support = support;
    }

    /** GET /api/v1/admin/verification/shadow-queue?page&size. */
    @Transactional(readOnly = true)
    public PageResponse<ShadowQueueItem> queue(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("page must be non-negative and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        CurrentUser caller = securityUtils.requireOrganizationMember();
        Long organizationId = caller.organizationId();
        List<Long> candidateIds = lookupRepository.findCandidateIdsByOrganizationId(organizationId);
        List<ClaimVerdict> verdicts = candidateIds.isEmpty() ? List.of()
                : verdictRepository.findAllByCandidateIdInAndCurrentTrueAndCandidateStatusOrderByIdDesc(candidateIds,
                CandidateVerdictStatus.SHARED);
        List<Long> verdictIds = verdicts.stream().map(ClaimVerdict::getId).toList();
        Map<Long, List<ClaimVerdictLabel>> labels = new HashMap<>();
        if (!verdictIds.isEmpty()) {
            for (ClaimVerdictLabel label : labelRepository.findAllByVerdictIdInAndOrganizationId(verdictIds, organizationId)) {
                labels.computeIfAbsent(label.getVerdictId(), k -> new ArrayList<>()).add(label);
            }
        }
        List<ClaimVerdict> ordered = new ArrayList<>();
        List<ClaimVerdict> labelledByMe = new ArrayList<>();
        for (ClaimVerdict verdict : verdicts) {
            if (mine(labels.get(verdict.getId()), caller.userId()) == null) {
                ordered.add(verdict);
            } else {
                labelledByMe.add(verdict);
            }
        }
        ordered.addAll(labelledByMe);
        int total = ordered.size();
        int from = (int) Math.min((long) page * size, total);
        int to = Math.min(from + size, total);
        List<ClaimVerdict> slice = ordered.subList(from, to);

        Set<Long> sliceCandidates = new HashSet<>();
        for (ClaimVerdict verdict : slice) {
            sliceCandidates.add(verdict.getCandidateId());
        }
        boolean blind = organizationRepository.findById(organizationId).map(Organization::isBlindReview).orElse(true);
        Map<Long, Long> latestApplication = new HashMap<>();
        Set<Long> revealedCandidates = new HashSet<>();
        if (!sliceCandidates.isEmpty()) {
            for (Application application : lookupRepository.findByCandidatesAndOrganization(sliceCandidates, organizationId)) {
                Long candidateId = application.getCandidate().getId();
                latestApplication.merge(candidateId, application.getId(), Long::max);
                if (ApplicantQueryService.isRevealed(application.getIdentityRevealedAt(), blind)) {
                    revealedCandidates.add(candidateId);
                }
            }
        }
        Map<Long, String> connectors = support.connectors(slice);
        List<ShadowQueueItem> items = new ArrayList<>();
        for (ClaimVerdict verdict : slice) {
            boolean revealed = revealedCandidates.contains(verdict.getCandidateId());
            String connector = connectors.get(verdict.getId());
            StaffVerdict base = support.staffVerdict(verdict, null, Map.of(), connector, revealed);
            List<ClaimVerdictLabel> verdictLabels = labels.getOrDefault(verdict.getId(), List.of());
            ClaimVerdictLabel myLabel = mine(verdictLabels, caller.userId());
            items.add(new ShadowQueueItem(verdict.getId(), latestApplication.get(verdict.getCandidateId()), revealed,
                    verdict.getSubjectType().name(), verdict.getSubjectId(), verdict.getClaimType().name(), verdict.getClaimValue(),
                    connector, verdict.getVerifier(), verdict.getVerdict().name(), verdict.isOwnershipProven(),
                    base.reasons(), base.integrityNotes(), verdict.getProbeQuestion(), base.evidence(), base.evidenceCount(),
                    verdict.getCreatedAt(), myLabel == null ? null : labelView(myLabel), verdictLabels.size()));
        }
        int totalPages = total == 0 ? 0 : (total + size - 1) / size;
        return PageResponse.<ShadowQueueItem>builder()
                .content(items).page(page).size(size).totalElements(total).totalPages(totalPages)
                .first(page == 0).last(page >= totalPages - 1).build();
    }

    /** POST /api/v1/admin/verification/verdicts/{id}/label: one label per verdict and labeller (replaced on repeat). */
    @Transactional
    public LabelView label(Long verdictId, VerdictLabelRequest request) {
        CurrentUser caller = securityUtils.requireOrganizationMember();
        ClaimVerdict verdict = verdictId == null ? null : verdictRepository.findById(verdictId).orElse(null);
        if (verdict == null || !verdict.isCurrent() || verdict.getCandidateStatus() != CandidateVerdictStatus.SHARED
                || lookupRepository.findByCandidateAndOrganization(verdict.getCandidateId(), caller.organizationId()).isEmpty()) {
            throw new ResourceNotFoundException("Verdict not found");
        }
        VerdictValue value = request == null ? null : VerdictValue.parse(request.labelVerdict());
        if (value == null) {
            throw new BadRequestException("labelVerdict must be VERIFIED, PARTLY_VERIFIED, NOT_FOUND, CONTRADICTED or NOT_CHECKABLE");
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (note != null && note.length() > 1000) {
            throw new BadRequestException("note must be at most 1000 characters");
        }
        ClaimVerdictLabel label = labelRepository.findByVerdictIdAndLabellerUserId(verdict.getId(), caller.userId())
                .orElseGet(ClaimVerdictLabel::new);
        label.setVerdictId(verdict.getId());
        label.setOrganizationId(caller.organizationId());
        label.setLabellerUserId(caller.userId());
        label.setLabelVerdict(value);
        label.setNote(note);
        return labelView(labelRepository.saveAndFlush(label));
    }

    /** GET /api/v1/admin/verification/metrics: over every label of the organisation. */
    @Transactional(readOnly = true)
    public VerificationMetricsResponse metrics() {
        CurrentUser caller = securityUtils.requireOrganizationMember();
        List<ClaimVerdictLabel> labels = labelRepository.findAllByOrganizationIdOrderByIdAsc(caller.organizationId());
        Set<Long> verdictIds = new HashSet<>();
        for (ClaimVerdictLabel label : labels) {
            verdictIds.add(label.getVerdictId());
        }
        Map<Long, ClaimVerdict> verdicts = new HashMap<>();
        for (ClaimVerdict verdict : verdictRepository.findAllById(verdictIds)) {
            verdicts.put(verdict.getId(), verdict);
        }
        List<VerificationMetrics.Pair> pairs = new ArrayList<>();
        for (ClaimVerdictLabel label : labels) {
            ClaimVerdict verdict = verdicts.get(label.getVerdictId());
            if (verdict != null) {
                pairs.add(new VerificationMetrics.Pair(verdict.getVerifier(), verdict.getVerdict(), label.getLabelVerdict()));
            }
        }
        return VerificationMetrics.compute(pairs);
    }

    private static ClaimVerdictLabel mine(List<ClaimVerdictLabel> labels, Long userId) {
        if (labels == null) {
            return null;
        }
        for (ClaimVerdictLabel label : labels) {
            if (label.getLabellerUserId().equals(userId)) {
                return label;
            }
        }
        return null;
    }

    private static LabelView labelView(ClaimVerdictLabel label) {
        return new LabelView(label.getVerdictId(), label.getLabelVerdict().name(), label.getNote(), label.getCreatedAt());
    }
}
