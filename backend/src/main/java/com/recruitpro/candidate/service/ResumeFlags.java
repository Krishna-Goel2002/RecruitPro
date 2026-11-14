package com.recruitpro.candidate.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.recruitpro.ai.dto.AiResumeParseResponse;
import com.recruitpro.candidate.dto.ResumeFlagResponse;
import com.recruitpro.common.util.JsonText;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Parser flags stored in {@code resume_analyses.flags_json} as {@code [{"code": ..., "detail": ...}]}. */
public final class ResumeFlags {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,60}");
    private static final int MAX_FLAGS = 20;
    private static final int MAX_DETAIL = 500;

    private ResumeFlags() {
    }

    /** JSON for storage; null when there are no valid flags. */
    public static String toJson(ObjectMapper mapper, List<AiResumeParseResponse.Flag> flags) {
        if (flags == null || flags.isEmpty()) {
            return null;
        }
        ArrayNode array = mapper.createArrayNode();
        for (AiResumeParseResponse.Flag flag : flags) {
            if (flag == null || flag.code() == null || !CODE.matcher(flag.code()).matches() || array.size() >= MAX_FLAGS) {
                continue;
            }
            String detail = flag.detail() == null ? "" : flag.detail().trim();
            array.addObject().put("code", flag.code()).put("detail",
                    detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail);
        }
        return array.isEmpty() ? null : array.toString();
    }

    public static List<ResumeFlagResponse> parse(ObjectMapper mapper, String json) {
        JsonNode root = JsonText.read(mapper, json);
        List<ResumeFlagResponse> flags = new ArrayList<>();
        if (root == null || !root.isArray()) {
            return flags;
        }
        for (JsonNode item : root) {
            String code = item.path("code").asText("");
            if (CODE.matcher(code).matches()) {
                flags.add(new ResumeFlagResponse(code, item.path("detail").asText("")));
            }
        }
        return flags;
    }

    public static List<String> codes(ObjectMapper mapper, String json) {
        return parse(mapper, json).stream().map(ResumeFlagResponse::code).toList();
    }
}
