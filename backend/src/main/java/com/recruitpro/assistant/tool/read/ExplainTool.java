package com.recruitpro.assistant.tool.read;

import com.recruitpro.assistant.tool.ArgSpec;
import com.recruitpro.assistant.tool.ArgType;
import com.recruitpro.assistant.tool.ReadTool;
import com.recruitpro.assistant.tool.ToolArgs;
import com.recruitpro.assistant.tool.ToolCapability;
import com.recruitpro.assistant.tool.ToolContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** explain: short built-in help text on how RecruitPro works (every staff role). */
@Component
public class ExplainTool implements ReadTool {

    @Override
    public String name() {
        return "explain";
    }

    @Override
    public String description() {
        return "Short built-in help on how RecruitPro works (verified coverage, evidence tiers, ranking groups, "
                + "knockouts, blind review, work samples, interview kits, fairness, verification, the assistant).";
    }

    @Override
    public List<ArgSpec> args() {
        return List.of(ArgSpec.required("topic", ArgType.ENUM).oneOf(HelpTopics.names()));
    }

    @Override
    public ToolCapability capability() {
        return ToolCapability.ANY_STAFF;
    }

    @Override
    public Map<String, Object> run(ToolContext context, ToolArgs args) {
        String topic = args.getString("topic");
        HelpTopics.Topic help = HelpTopics.TOPICS.get(topic);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", topic);
        data.put("title", help.title());
        data.put("text", help.text());
        data.put("link", help.link());
        return data;
    }
}
