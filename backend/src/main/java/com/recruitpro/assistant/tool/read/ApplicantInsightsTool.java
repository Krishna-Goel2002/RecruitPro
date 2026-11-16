package com.recruitpro.assistant.tool.read;

import com.recruitpro.assistant.service.AssistantApplicationLookup.AppInfo;
import com.recruitpro.assistant.service.Deidentifier;
import com.recruitpro.assistant.tool.ApplicationAccess;
import com.recruitpro.assistant.tool.ApplicationCodes;
import com.recruitpro.assistant.tool.ArgSpec;
import com.recruitpro.assistant.tool.ArgType;
import com.recruitpro.assistant.tool.AssistantToolException;
import com.recruitpro.assistant.tool.ReadTool;
import com.recruitpro.assistant.tool.ToolArgs;
import com.recruitpro.assistant.tool.ToolCapability;
import com.recruitpro.assistant.tool.ToolContext;
import com.recruitpro.assistant.tool.Tools;
import com.recruitpro.assessment.dto.WorkSampleAssignmentResponse;
import com.recruitpro.hr.dto.ApplicantDetailResponse;
import com.recruitpro.hr.dto.RequirementEvidenceResponse;
import com.recruitpro.hr.service.ApplicantQueryService;
import com.recruitpro.interview.dto.InterviewDetailResponse;
import com.recruitpro.interview.dto.InterviewResponse;
import com.recruitpro.interview.entity.InterviewStatus;
import com.recruitpro.interview.service.InterviewService;
import com.recruitpro.user.entity.Role;
import com.recruitpro.verification.dto.StaffClaim;
import com.recruitpro.verification.dto.StaffVerdict;
import com.recruitpro.verification.dto.StaffVerificationResponse;
import com.recruitpro.verification.service.StaffVerificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * applicant_insights: one applicant by code. Readers get the evidence per requirement (cited text quoted and
 * de-identified), ranking gates, verification verdicts, work-sample and interview results and open questions, through
 * ApplicantQueryService and StaffVerificationService as the user. An INTERVIEWER gets only applications they interview,
 * through InterviewService (focus requirements of their interview).
 */
@Component
public class ApplicantInsightsTool implements ReadTool {

    private static final Logger log = LoggerFactory.getLogger(ApplicantInsightsTool.class);

    private final ApplicationAccess access;
    private final ApplicantQueryService queryService;
    private final StaffVerificationService verificationService;
    private final InterviewService interviewService;

    public ApplicantInsightsTool(ApplicationAccess access, ApplicantQueryService queryService,
                                 StaffVerificationService verificationService, InterviewService interviewService) {
        this.access = access;
        this.queryService = queryService;
        this.verificationService = verificationService;
        this.interviewService = interviewService;
    }

    @Override
    public String name() {
        return "applicant_insights";
    }

    @Override
    public String description() {
        return "Everything about one applicant by application code: evidence per requirement (tier, status, quoted "
                + "evidence), ranking gates, verification verdicts, work sample and interview results, open questions. "
                + "Interviewers: only applicants they interview.";
    }

    @Override
    public List<ArgSpec> args() {
        return List.of(ArgSpec.required("applicationCode", ArgType.APPLICATION_CODE));
    }

    @Override
    public ToolCapability capability() {
        return ToolCapability.ANY_STAFF;
    }

    @Override
    public Map<String, Object> run(ToolContext context, ToolArgs args) {
        Long applicationId = args.getApplicationId("applicationCode");
        if (context.user().hasRole(Role.INTERVIEWER)) {
            return forInterviewer(context, applicationId);
        }
        AppInfo info = access.requireReadable(context, applicationId);
        ApplicantDetailResponse detail = queryService.detail(info.jobId(), applicationId);
        Deidentifier deid = context.deidentifier();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", info.code());
        data.put("applicationId", applicationId);
        data.put("jobId", info.jobId());
        data.put("jobTitle", info.jobTitle());
        data.put("status", detail.applicationStatus());
        data.put("identityRevealed", detail.identityRevealed());
        data.put("group", detail.rankGroup());
        data.put("rank", detail.rank());
        data.put("verifiedCoverage", Tools.round1(detail.verifiedCoverage()));
        data.put("claimedCoverage", Tools.round1(detail.claimedCoverage()));
        data.put("knockoutsVerified", detail.knockoutsVerified());
        data.put("matchStatus", detail.matchStatus());
        data.put("resumeFlags", detail.flags());
        data.put("requirements", requirements(detail.requirements(), deid));
        List<String> gates = new ArrayList<>();
        List<String> openQuestions = new ArrayList<>();
        for (RequirementEvidenceResponse requirement : detail.requirements()) {
            boolean verified = requirement.tier() != null && requirement.tier().isVerified()
                    && "MATCHED".equals(requirement.status());
            if (requirement.knockout() && !verified) {
                gates.add(requirement.description());
            }
            if (requirement.verificationHint() != null && openQuestions.size() < 8) {
                openQuestions.add(deid.quote(requirement.verificationHint()));
            }
        }
        data.put("unverifiedMustHaves", gates);
        data.put("openQuestions", openQuestions);
        data.put("verification", verification(applicationId, deid));
        data.put("workSamples", workSamples(detail.workSamples()));
        data.put("interviews", interviews(detail.interviews()));
        data.put("allowedDecisions", detail.allowedActions());
        data.put("link", Tools.applicantLink(info.jobId(), applicationId));
        return data;
    }

    private Map<String, Object> forInterviewer(ToolContext context, Long applicationId) {
        AppInfo info = access.requireInOrganization(context, applicationId);
        InterviewResponse interview = interviewService.mine().stream()
                .filter(item -> Objects.equals(item.applicationId(), applicationId))
                .min(Comparator.comparingInt((InterviewResponse item) -> item.status() == InterviewStatus.SCHEDULED ? 0 : 1)
                        .thenComparing(InterviewResponse::scheduledAt, Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder())))
                .orElseThrow(() -> new AssistantToolException("You can see an applicant only when you interview them; "
                        + ApplicationCodes.code(applicationId) + " has no interview assigned to you"));
        InterviewDetailResponse detail = interviewService.detail(interview.id());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", info.code());
        data.put("applicationId", applicationId);
        data.put("jobId", detail.jobId());
        data.put("jobTitle", detail.jobTitle());
        data.put("status", detail.applicationStatus());
        data.put("interviewId", detail.id());
        data.put("interviewStatus", detail.status());
        data.put("scheduledAt", Tools.time(detail.scheduledAt()));
        data.put("durationMinutes", detail.durationMinutes());
        data.put("scorecardSubmitted", detail.scorecard() != null);
        data.put("scorecardEditable", detail.scorecardEditable());
        data.put("kitQuestions", detail.kit() == null ? 0 : detail.kit().size());
        data.put("focusRequirements", requirements(detail.focusRequirements(), context.deidentifier()));
        data.put("link", Tools.interviewLink(detail.id()));
        return data;
    }

    static List<Map<String, Object>> requirements(List<RequirementEvidenceResponse> requirements, Deidentifier deid) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (RequirementEvidenceResponse requirement : requirements == null ? List.<RequirementEvidenceResponse>of() : requirements) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("requirementId", requirement.requirementId());
            item.put("requirement", requirement.description());
            item.put("type", requirement.type());
            item.put("mandatory", requirement.mandatory());
            item.put("knockout", requirement.knockout());
            item.put("status", requirement.status());
            item.put("tier", requirement.tier());
            item.put("provenance", requirement.provenance());
            item.put("evidenceQuote", deid.quote(requirement.evidence()));
            item.put("explanation", deid.quote(requirement.explanation()));
            items.add(item);
        }
        return items;
    }

    private Map<String, Object> verification(Long applicationId, Deidentifier deid) {
        StaffVerificationResponse response;
        try {
            response = verificationService.view(applicationId);
        } catch (RuntimeException ex) {
            log.debug("Verification view unavailable for the assistant: {}", ex.getClass().getSimpleName());
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", response.mode());
        List<Map<String, Object>> claims = new ArrayList<>();
        for (StaffClaim claim : response.claims()) {
            if (claims.size() >= 15) break;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("claimType", claim.claimType());
            item.put("claimQuote", deid.quote(claim.value()));
            item.put("tier", claim.tier());
            List<Map<String, Object>> verdicts = new ArrayList<>();
            for (StaffVerdict verdict : claim.verdicts()) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("verdict", verdict.verdict());
                v.put("verifier", verdict.verifier());
                v.put("ownershipProven", verdict.ownershipProven());
                v.put("reviewedByMyOrganisation", verdict.myOrgReview() == null ? null : verdict.myOrgReview().decision());
                verdicts.add(v);
            }
            item.put("verdicts", verdicts);
            claims.add(item);
        }
        data.put("claims", claims);
        return data;
    }

    private static List<Map<String, Object>> workSamples(List<Object> items) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items == null ? List.of() : items) {
            if (item instanceof WorkSampleAssignmentResponse assignment) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("title", assignment.workSampleTitle());
                data.put("status", assignment.status());
                data.put("totalScore", assignment.totalScore());
                data.put("dueAt", Tools.time(assignment.dueAt()));
                data.put("submittedAt", Tools.time(assignment.submittedAt()));
                result.add(data);
            }
        }
        return result;
    }

    private static List<Map<String, Object>> interviews(List<Object> items) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items == null ? List.of() : items) {
            if (item instanceof InterviewResponse interview) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("interviewId", interview.id());
                data.put("status", interview.status());
                data.put("scheduledAt", Tools.time(interview.scheduledAt()));
                data.put("recommendation", interview.scorecard() == null ? null : interview.scorecard().recommendation());
                result.add(data);
            }
        }
        return result;
    }
}
