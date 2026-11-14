package com.recruitpro.fairness.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * FairnessReport (plan 5.7). Per dimension and stage: for each survey group, {@code count} = applicants of the group
 * who ever reached the stage and {@code selectionRate} = that count / applicants of the group (0..1). Groups with
 * fewer than {@code minimumGroupSize} applicants are suppressed (count and rate null). {@code adverseImpactRatio} =
 * lowest / highest rate among unsuppressed groups (null with fewer than two such groups or no selections);
 * {@code flagged} when it is below 0.8. {@code surveyResponseRate} = applicants with a stored survey / all applicants
 * (0..1). Nulls are always serialised.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record FairnessReport(String scope, Long jobId, int minimumGroupSize, double surveyResponseRate,
                             List<DimensionReport> dimensions, String note) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DimensionReport(String dimension, List<StageReport> stages) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StageReport(String stage, List<GroupStat> groups, Double adverseImpactRatio, boolean flagged) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GroupStat(String group, Integer count, Double selectionRate) { }
}
