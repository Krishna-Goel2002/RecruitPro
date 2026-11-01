package com.recruitpro.ai.dto;

import java.util.List;

public record AiResumeParseResponse(String status, String errorCode, String parserVersion, List<Fact> facts) {
    public record Fact(String type, String value, String normalizedValue, String evidence,
                       String sourceSection, String sourceReference) { }
}
