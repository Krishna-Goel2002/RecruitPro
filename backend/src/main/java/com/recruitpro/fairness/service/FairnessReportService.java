package com.recruitpro.fairness.service;

import com.recruitpro.application.entity.ApplicationStatus;
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
        Job job = jobAccess.requireManageAccess(jobId);
        return build("JOB", job.getId(), repository.findApplicantsOfJob(job.getId()),
                repository.findStatusEventsOfJob(job.getId()), repository.findAllForJob(job.getId()));
    }

    /** GET /api/v1/admin/fairness. */
    public FairnessReport forOrganization() {
        CurrentUser caller = securityUtils.requireOrganizationMember();
        if (caller.role() != Role.ADMIN) {
            throw new ForbiddenException("Only administrators can view the organisation's fairness report");
        }
        Long organizationId = caller.organizationId();
        return build("ORGANIZATION", null, repository.findApplicantsOfOrganization(organizationId),
                repository.findStatusEventsOfOrganization(organizationId), repository.findAllForOrganization(organizationId));
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
