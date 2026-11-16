package com.recruitpro.assistant.tool;

import com.recruitpro.assistant.service.AssistantApplicationLookup;
import com.recruitpro.assistant.service.AssistantApplicationLookup.AppInfo;
import com.recruitpro.hr.service.HrJobAccessService;
import com.recruitpro.interview.dto.InterviewResponse;
import com.recruitpro.interview.service.InterviewService;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.user.entity.Role;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves application codes for tools with the same rules as the portal: an application of another organisation is
 * "not found"; readers (ADMIN, HR_MANAGER, RECRUITER) see their organisation's applicants
 * ({@code HrJobAccessService.requireApplicantReadAccess}), managers may change them ({@code requireManageAccess});
 * an INTERVIEWER sees only applications they interview. Every resolved candidate's name and e-mail are registered with
 * the message's {@code Deidentifier}.
 */
@Component
public class ApplicationAccess {

    private final AssistantApplicationLookup lookup;
    private final HrJobAccessService jobAccess;
    private final InterviewService interviewService;

    public ApplicationAccess(AssistantApplicationLookup lookup, HrJobAccessService jobAccess, InterviewService interviewService) {
        this.lookup = lookup;
        this.jobAccess = jobAccess;
        this.interviewService = interviewService;
    }

    /** An application whose applicant data the user may read (ADMIN, HR_MANAGER, RECRUITER). */
    public AppInfo requireReadable(ToolContext context, Long applicationId) {
        AppInfo info = inOrganization(context, applicationId);
        jobAccess.requireApplicantReadAccess(info.jobId());
        return info;
    }

    /** An application the user may decide on (ADMIN, HR_MANAGER). */
    public AppInfo requireManageable(ToolContext context, Long applicationId) {
        AppInfo info = inOrganization(context, applicationId);
        jobAccess.requireManageAccess(info.jobId());
        return info;
    }

    /** An application of the organisation (no role check; the caller checks interview assignment). */
    public AppInfo requireInOrganization(ToolContext context, Long applicationId) {
        return inOrganization(context, applicationId);
    }

    /**
     * The applications among {@code ids} the user may see in a block: readers, their organisation's; an INTERVIEWER,
     * the ones they interview. Used to enrich planner blocks (names only when revealed) and to drop the rest.
     */
    public Map<Long, AppInfo> visible(CurrentUser user, Collection<Long> ids) {
        Map<Long, AppInfo> result = new LinkedHashMap<>();
        if (user == null || user.organizationId() == null || ids.isEmpty()) {
            return result;
        }
        boolean reader = user.hasAnyRole(Role.ADMIN, Role.HR_MANAGER, Role.RECRUITER);
        Set<Long> interviewed = reader ? Set.of() : interviewedApplicationIds();
        for (AppInfo info : lookup.findAll(ids).values()) {
            if (Objects.equals(info.organizationId(), user.organizationId())
                    && (reader || interviewed.contains(info.applicationId()))) {
                result.put(info.applicationId(), info);
            }
        }
        return result;
    }

    /** Applications with an interview assigned to the current user (any status). */
    public Set<Long> interviewedApplicationIds() {
        Set<Long> ids = new HashSet<>();
        for (InterviewResponse interview : interviewService.mine()) {
            if (interview.applicationId() != null) {
                ids.add(interview.applicationId());
            }
        }
        return ids;
    }

    private AppInfo inOrganization(ToolContext context, Long applicationId) {
        if (applicationId == null) {
            throw new AssistantToolException("An application code like A-3014 is required");
        }
        AppInfo info = lookup.find(applicationId)
                .filter(found -> Objects.equals(found.organizationId(), context.user().organizationId()))
                .orElseThrow(() -> new AssistantToolException("Application " + ApplicationCodes.code(applicationId)
                        + " was not found"));
        context.deidentifier().addPerson(info.firstName(), info.lastName(), info.email());
        return info;
    }
}
