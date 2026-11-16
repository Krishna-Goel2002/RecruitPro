package com.recruitpro.assistant.tool.read;

import com.recruitpro.assistant.tool.ArgSpec;
import com.recruitpro.assistant.tool.ArgType;
import com.recruitpro.assistant.tool.ReadTool;
import com.recruitpro.assistant.tool.ToolArgs;
import com.recruitpro.assistant.tool.ToolCapability;
import com.recruitpro.assistant.tool.ToolContext;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.interview.dto.InterviewResponse;
import com.recruitpro.interview.entity.InterviewStatus;
import com.recruitpro.interview.service.InterviewService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * my_interviews: interviews assigned to the user (InterviewService.mine), soonest first, by application code. The
 * candidate name, interviewer name and location (often a meeting link) are left out.
 */
@Component
public class MyInterviewsTool implements ReadTool {

    static final int LIMIT = 20;

    private final InterviewService interviewService;

    public MyInterviewsTool(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    @Override
    public String name() {
        return "my_interviews";
    }

    @Override
    public String description() {
        return "Interviews assigned to me (soonest first): application code, job, time, status and whether my "
                + "scorecard is submitted.";
    }

    @Override
    public List<ArgSpec> args() {
        return List.of(ArgSpec.optional("upcomingOnly", ArgType.BOOLEAN).hint("only scheduled interviews in the future"));
    }

    @Override
    public ToolCapability capability() {
        return ToolCapability.ANY_STAFF;
    }

    @Override
    public Map<String, Object> run(ToolContext context, ToolArgs args) {
        boolean upcomingOnly = args.getBoolean("upcomingOnly", false);
        LocalDateTime now = UtcTime.now();
        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0;
        int withoutScorecard = 0;
        for (InterviewResponse interview : interviewService.mine()) {
            boolean upcoming = interview.status() == InterviewStatus.SCHEDULED && interview.scheduledAt() != null
                    && interview.scheduledAt().isAfter(now);
            if (upcomingOnly && !upcoming) {
                continue;
            }
            total++;
            if (interview.status() == InterviewStatus.SCHEDULED && !upcoming && interview.scorecard() == null) {
                withoutScorecard++;
            }
            if (items.size() < LIMIT) {
                items.add(AttentionDigestTool.interviewItem(interview));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("pastWithoutScorecard", withoutScorecard);
        data.put("interviews", items);
        data.put("link", "/interviews");
        return data;
    }
}
