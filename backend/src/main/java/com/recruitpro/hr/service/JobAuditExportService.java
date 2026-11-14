package com.recruitpro.hr.service;

import com.recruitpro.application.entity.ApplicationDecisionHistory;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.common.util.Csv;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobMatchRun;
import com.recruitpro.hr.repository.JobMatchRunRepository;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GET /api/v1/hr/jobs/{jobId}/audit-export (ADMIN, HR_MANAGER): one CSV row per decision-history event of the job's
 * applications with the match run it was made on (D8; EU AI Act logging). Candidate actors (withdrawals) are shown
 * as "Candidate" without identity. Cells are formula-neutralised ({@link Csv}). The export is audited
 * (AUDIT_EXPORTED).
 */
@Service
public class JobAuditExportService {

    static final List<String> HEADER = List.of("decisionId", "timestamp", "applicationId", "actorUserId", "actorRole",
            "actorName", "actorEmail", "action", "previousStatus", "newStatus", "comment", "matchRunId",
            "algorithmVersion", "verifiedCoverage", "claimedCoverage", "knockoutsVerified");

    private final HrJobAccessService jobAccess;
    private final ApplicationDecisionHistoryRepository historyRepository;
    private final JobMatchRunRepository matchRunRepository;
    private final AuditService auditService;

    public JobAuditExportService(HrJobAccessService jobAccess, ApplicationDecisionHistoryRepository historyRepository,
                                 JobMatchRunRepository matchRunRepository, AuditService auditService) {
        this.jobAccess = jobAccess;
        this.historyRepository = historyRepository;
        this.matchRunRepository = matchRunRepository;
        this.auditService = auditService;
    }

    @Transactional
    public String exportCsv(Long jobId) {
        Job job = jobAccess.requireManageAccess(jobId);
        List<ApplicationDecisionHistory> events = historyRepository.findAllForJobExport(job.getId());
        Set<Long> runIds = new HashSet<>();
        for (ApplicationDecisionHistory event : events) {
            if (event.getMatchRunId() != null) {
                runIds.add(event.getMatchRunId());
            }
        }
        Map<Long, JobMatchRun> runs = new HashMap<>();
        if (!runIds.isEmpty()) {
            for (JobMatchRun run : matchRunRepository.findAllById(runIds)) {
                runs.put(run.getId(), run);
            }
        }
        StringBuilder csv = new StringBuilder(512 + events.size() * 160);
        csv.append(String.join(",", HEADER)).append(Csv.LINE_END);
        for (ApplicationDecisionHistory event : events) {
            User actor = event.getActor();
            boolean candidateActor = actor != null && actor.getRole() == Role.CANDIDATE;
            JobMatchRun run = event.getMatchRunId() == null ? null : runs.get(event.getMatchRunId());
            csv.append(Csv.row(List.of(
                    Csv.number(event.getId()),
                    Csv.text(UtcTime.format(event.getCreatedAt())),
                    Csv.number(event.getApplication().getId()),
                    candidateActor || actor == null ? "" : Csv.number(actor.getId()),
                    Csv.text(actor == null || actor.getRole() == null ? null : actor.getRole().name()),
                    Csv.text(actor == null ? null : candidateActor ? "Candidate" : actor.getFullName()),
                    Csv.text(actor == null || candidateActor ? null : actor.getEmail()),
                    Csv.text(event.getActionType() == null ? null : event.getActionType().name()),
                    Csv.text(event.getPreviousStatus() == null ? null : event.getPreviousStatus().name()),
                    Csv.text(event.getNewStatus() == null ? null : event.getNewStatus().name()),
                    Csv.text(event.getComment()),
                    Csv.number(event.getMatchRunId()),
                    Csv.text(run == null ? null : run.getAlgorithmVersion()),
                    Csv.number(run == null ? null : run.getVerifiedCoverage()),
                    Csv.number(run == null ? null : run.getClaimedCoverage()),
                    Csv.bool(run == null ? null : run.getKnockoutsVerified()))));
        }
        auditService.record(AuditActions.AUDIT_EXPORTED, "JOB", String.valueOf(job.getId()),
                "Decision history of job #" + job.getId() + " exported (" + events.size() + " events)");
        return csv.toString();
    }
}
