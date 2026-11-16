package com.recruitpro.assistant.tool.write;

import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.assistant.service.AssistantApplicationLookup.AppInfo;
import com.recruitpro.assistant.tool.ActionOutcome;
import com.recruitpro.assistant.tool.ActionProposal;
import com.recruitpro.assistant.tool.ApplicationAccess;
import com.recruitpro.assistant.tool.ArgSpec;
import com.recruitpro.assistant.tool.ArgType;
import com.recruitpro.assistant.tool.AssistantToolException;
import com.recruitpro.assistant.tool.ToolArgs;
import com.recruitpro.assistant.tool.ToolCapability;
import com.recruitpro.assistant.tool.ToolContext;
import com.recruitpro.assistant.tool.Tools;
import com.recruitpro.assistant.tool.WriteTool;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.dto.InterviewerResponse;
import com.recruitpro.hr.service.StaffDirectoryService;
import com.recruitpro.interview.dto.InterviewResponse;
import com.recruitpro.interview.dto.ScheduleInterviewRequest;
import com.recruitpro.interview.service.InterviewService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * schedule_interview: schedules an interview for a shortlisted or interviewing application with a staff member of the
 * organisation (InterviewService.schedule on confirm: moves a SHORTLISTED application to INTERVIEWING, notifies the
 * candidate and the interviewer, sends calendar invitations). When the interviewer is unknown the error lists the
 * organisation's staff (user ids and names of staff, never candidates).
 */
@Component
public class ScheduleInterviewTool implements WriteTool {

    static final int DEFAULT_DURATION = 45;

    private final ApplicationAccess access;
    private final InterviewService interviewService;
    private final StaffDirectoryService staffDirectoryService;

    public ScheduleInterviewTool(ApplicationAccess access, InterviewService interviewService,
                                 StaffDirectoryService staffDirectoryService) {
        this.access = access;
        this.interviewService = interviewService;
        this.staffDirectoryService = staffDirectoryService;
    }

    @Override
    public String name() {
        return "schedule_interview";
    }

    @Override
    public String description() {
        return "Schedule an interview for a shortlisted or interviewing applicant with a staff member (interviewerUserId) "
                + "at a future time; moves a shortlisted application to interviewing.";
    }

    @Override
    public List<ArgSpec> args() {
        return List.of(
                ArgSpec.required("applicationCode", ArgType.APPLICATION_CODE),
                ArgSpec.required("interviewerUserId", ArgType.INTEGER).range(1, Long.MAX_VALUE),
                ArgSpec.required("scheduledAt", ArgType.DATE_TIME).hint("in the future"),
                ArgSpec.optional("durationMinutes", ArgType.INTEGER).range(5, 480).hint("default 45"),
                ArgSpec.optional("location", ArgType.STRING).maxLength(500));
    }

    @Override
    public ToolCapability capability() {
        return ToolCapability.JOBS_MANAGE;
    }

    @Override
    public ActionProposal prepare(ToolContext context, ToolArgs args) {
        AppInfo info = access.requireManageable(context, args.getApplicationId("applicationCode"));
        if (!InterviewService.SCHEDULABLE.contains(info.status())) {
            throw new AssistantToolException(info.code() + " is " + info.status()
                    + "; interviews can be scheduled for shortlisted or interviewing applications only");
        }
        LocalDateTime scheduledAt = args.getDateTime("scheduledAt");
        if (!scheduledAt.isAfter(UtcTime.now())) {
            throw new AssistantToolException("The interview time must be in the future");
        }
        List<InterviewerResponse> staff = staffDirectoryService.interviewers();
        InterviewerResponse interviewer = staff.stream()
                .filter(member -> Objects.equals(member.userId(), args.getLong("interviewerUserId")))
                .findFirst().orElseThrow(() -> new AssistantToolException("User #" + args.getLong("interviewerUserId")
                        + " is not an active staff member of your organisation" + options(staff)));
        Map<String, Object> normalised = new LinkedHashMap<>(args.values());
        normalised.putIfAbsent("durationMinutes", (long) DEFAULT_DURATION);
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("applicationId", info.applicationId());
        preview.put("applicationCode", info.code());
        preview.put("revealedName", info.revealedName());
        preview.put("jobId", info.jobId());
        preview.put("jobTitle", info.jobTitle());
        preview.put("interviewerUserId", interviewer.userId());
        preview.put("interviewerName", interviewer.name());
        preview.put("scheduledAt", Tools.time(scheduledAt));
        preview.put("durationMinutes", normalised.get("durationMinutes"));
        preview.put("location", args.getString("location"));
        preview.put("notes", info.status() == ApplicationStatus.SHORTLISTED
                ? "The application moves to INTERVIEWING. The candidate and the interviewer are notified."
                : "The candidate and the interviewer are notified.");
        preview.put("link", Tools.applicantLink(info.jobId(), info.applicationId()));
        return new ActionProposal("Schedule an interview with " + info.code() + " (" + interviewer.name() + ", "
                + Tools.time(scheduledAt) + ")", normalised, preview, List.of());
    }

    @Override
    public ActionOutcome execute(ToolContext context, ToolArgs args) {
        AppInfo info = access.requireManageable(context, args.getApplicationId("applicationCode"));
        Integer duration = args.has("durationMinutes") ? args.getInt("durationMinutes") : DEFAULT_DURATION;
        InterviewResponse interview = interviewService.schedule(info.jobId(), info.applicationId(),
                new ScheduleInterviewRequest(args.getLong("interviewerUserId"), args.getDateTime("scheduledAt"), duration,
                        args.getString("location")));
        return new ActionOutcome("interview", interview.id(), Tools.interviewLink(interview.id()),
                "Scheduled the interview with " + info.code() + " for " + Tools.time(interview.scheduledAt()) + ".",
                "INTERVIEW", String.valueOf(interview.id()), "Interview #" + interview.id() + " scheduled for application #"
                        + info.applicationId());
    }

    private static String options(List<InterviewerResponse> staff) {
        if (staff.isEmpty()) return "";
        StringBuilder text = new StringBuilder(" (staff: ");
        for (int i = 0; i < staff.size() && i < 15; i++) {
            if (i > 0) text.append(", ");
            text.append('#').append(staff.get(i).userId()).append(' ').append(staff.get(i).name())
                    .append(" (").append(staff.get(i).role()).append(')');
        }
        return text.append(')').toString();
    }
}
