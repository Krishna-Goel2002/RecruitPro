package com.recruitpro.fairness.service;

import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.fairness.dto.FairnessReport;
import com.recruitpro.fairness.entity.EeoResponse;
import com.recruitpro.fairness.entity.EqualOpportunityValues;
import com.recruitpro.fairness.repository.ApplicantStageRow;
import com.recruitpro.fairness.repository.EeoResponseRepository;
import com.recruitpro.fairness.repository.StatusEventRow;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.service.HrJobAccessService;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fairness reports for one job (ADMIN, HR_MANAGER) or the whole organisation (ADMIN). Each application counts once
 * per report (a candidate who applied to two jobs counts twice in the organisation report). Survey answers are only
 * read here and only leave the service as suppressed aggregates (D14).
 */
@Service
@Transactional(readOnly = true)
public class FairnessReportService {

    private final HrJobAccessService jobAccess;
    private final SecurityUtils securityUtils;
    private final EeoResponseRepository repository;

    public FairnessReportService(HrJobAccessService jobAccess, SecurityUtils securityUtils, EeoResponseRepository repository) {
        this.jobAccess = jobAccess;
        this.securityUtils = securityUtils;
        this.repository = repository;
    }

    /** GET /api/v1/hr/jobs/{jobId}/fairness. */
    public FairnessReport forJob(Long jobId) {
        return forJob(jobId, null, null);
    }

    /**
     * GET /api/v1/hr/jobs/{jobId}/fairness?from&to (WP-PLAT): only applications created between {@code from} and
     * {@code to} (ISO dates, inclusive, UTC days); either bound may be omitted.
     */
    public FairnessReport forJob(Long jobId, LocalDate from, LocalDate to) {
        Job job = jobAccess.requireManageAccess(jobId);
        List<ApplicantStageRow> applicants = from == null && to == null
                ? repository.findApplicantsOfJob(job.getId())
                : repository.findApplicantsOfJobCreatedBetween(job.getId(), start(from, to), endExclusive(to));
        return build("JOB", job.getId(), applicants, repository.findStatusEventsOfJob(job.getId()),
                repository.findAllForJob(job.getId()));
    }

    /** GET /api/v1/admin/fairness. */
    public FairnessReport forOrganization() {
        return forOrganization(null, null);
    }

    /** GET /api/v1/admin/fairness?from&to (WP-PLAT), same date semantics as {@link #forJob(Long, LocalDate, LocalDate)}. */
    public FairnessReport forOrganization(LocalDate from, LocalDate to) {
        CurrentUser caller = securityUtils.requireOrganizationMember();
        if (caller.role() != Role.ADMIN) {
            throw new ForbiddenException("Only administrators can view the organisation's fairness report");
        }
        Long organizationId = caller.organizationId();
        List<ApplicantStageRow> applicants = from == null && to == null
                ? repository.findApplicantsOfOrganization(organizationId)
                : repository.findApplicantsOfOrganizationCreatedBetween(organizationId, start(from, to), endExclusive(to));
        return build("ORGANIZATION", null, applicants, repository.findStatusEventsOfOrganization(organizationId),
                repository.findAllForOrganization(organizationId));
    }

    /** Start of {@code from} (UTC); 400 when {@code from} is after {@code to}. */
    static LocalDateTime start(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("from must not be after to");
        }
        return from == null ? LocalDateTime.of(1970, 1, 1, 0, 0) : from.atStartOfDay();
    }

    /** Start of the day after {@code to} (UTC), so {@code to} is inclusive. */
    static LocalDateTime endExclusive(LocalDate to) {
        return to == null ? LocalDateTime.of(9999, 1, 1, 0, 0) : to.plusDays(1).atStartOfDay();
    }

    static FairnessReport build(String scope, Long jobId, List<ApplicantStageRow> applicants, List<StatusEventRow> events,
                                List<EeoResponse> responses) {
        Map<Long, List<ApplicationStatus>> history = new HashMap<>();
        for (StatusEventRow event : events) {
            List<ApplicationStatus> statuses = history.computeIfAbsent(event.applicationId(), id -> new ArrayList<>());
            statuses.add(event.previousStatus());
            statuses.add(event.newStatus());
        }
        Map<Long, EeoResponse> byCandidate = new HashMap<>();
        for (EeoResponse response : responses) {
            byCandidate.put(response.getCandidateId(), response);
        }
        List<FairnessCalculator.Subject> subjects = new ArrayList<>(applicants.size());
        for (ApplicantStageRow applicant : applicants) {
            EeoResponse response = byCandidate.get(applicant.candidateId());
            Map<String, String> answers = new HashMap<>();
            if (response != null) {
                for (String dimension : EqualOpportunityValues.DIMENSION_ORDER) {
                    answers.put(dimension, EqualOpportunityValues.valueOf(response, dimension));
                }
            }
            subjects.add(new FairnessCalculator.Subject(response != null, answers,
                    FairnessCalculator.furthestStage(applicant.status(), history.get(applicant.applicationId()))));
        }
        return FairnessCalculator.report(scope, jobId, subjects);
    }
}
