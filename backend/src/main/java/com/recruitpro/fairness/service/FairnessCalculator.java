package com.recruitpro.fairness.service;

import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.fairness.dto.FairnessReport;
import com.recruitpro.fairness.entity.EqualOpportunityValues;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Adverse-impact arithmetic (plan 5.7), pure and unit-tested. Selection rate of group g at stage S = applicants of g
 * who ever reached S / applicants of g. Groups with fewer than {@link #MINIMUM_GROUP_SIZE} applicants are suppressed;
 * "PREFER_NOT" answers and non-respondents are excluded. The adverse-impact ratio is the lowest / highest rate among
 * unsuppressed groups and is flagged below {@link #FOUR_FIFTHS}.
 */
public final class FairnessCalculator {

    public static final int MINIMUM_GROUP_SIZE = 5;
    public static final double FOUR_FIFTHS = 0.8;
    public static final String NOTE = "Selection rate = share of a group's applicants who ever reached the stage (from the "
            + "decision history); count = how many of them reached it. Groups with fewer than " + MINIMUM_GROUP_SIZE
            + " applicants are suppressed. 'Prefer not to say' answers and applicants without a survey response are "
            + "excluded, so the report only describes respondents. An adverse-impact ratio below 0.80 (four-fifths rule) "
            + "is flagged for review; it is a reason to examine how decisions were made, not proof of discrimination.";

    /** Funnel stages in order; reaching a later stage implies every earlier one. */
    public enum Stage { APPLIED, SHORTLISTED, INTERVIEWING, SELECTED }

    /**
     * One application: whether the candidate has a stored survey, the answers by dimension name (missing or null:
     * not answered) and the furthest stage the application ever reached.
     */
    public record Subject(boolean responded, Map<String, String> answers, Stage reached) { }

    private FairnessCalculator() {
    }

    /** Furthest stage from the current status and every status of the decision history (previous and new). */
    public static Stage furthestStage(ApplicationStatus current, Collection<ApplicationStatus> history) {
        Stage furthest = stageOf(current);
        if (history != null) {
            for (ApplicationStatus status : history) {
                Stage stage = stageOf(status);
                if (stage.ordinal() > furthest.ordinal()) {
                    furthest = stage;
                }
            }
        }
        return furthest;
    }

    static Stage stageOf(ApplicationStatus status) {
        if (status == null) {
            return Stage.APPLIED;
        }
        return switch (status) {
            case SHORTLISTED -> Stage.SHORTLISTED;
            case INTERVIEWING -> Stage.INTERVIEWING;
            case SELECTED -> Stage.SELECTED;
            case SUBMITTED, REJECTED, WITHDRAWN -> Stage.APPLIED;
        };
    }

    public static FairnessReport report(String scope, Long jobId, List<Subject> subjects) {
        int total = subjects.size();
        long respondents = subjects.stream().filter(Subject::responded).count();
        double responseRate = total == 0 ? 0.0 : round((double) respondents / total, 4);
        List<FairnessReport.DimensionReport> dimensions = new ArrayList<>();
        for (String dimension : EqualOpportunityValues.DIMENSION_ORDER) {
            List<String> groups = new ArrayList<>(EqualOpportunityValues.DIMENSIONS.get(dimension));
            groups.remove(EqualOpportunityValues.PREFER_NOT);
            int[] applicants = new int[groups.size()];
            int[][] reached = new int[groups.size()][Stage.values().length];
            int answered = 0;
            for (Subject subject : subjects) {
                if (!subject.responded() || subject.answers() == null) {
                    continue;
                }
                int index = groups.indexOf(subject.answers().get(dimension));
                if (index < 0) {
                    continue;
                }
                answered++;
                applicants[index]++;
                Stage stage = subject.reached() == null ? Stage.APPLIED : subject.reached();
                for (int s = 0; s <= stage.ordinal(); s++) {
                    reached[index][s]++;
                }
            }
            if (answered == 0) {
                continue;
            }
            List<FairnessReport.StageReport> stages = new ArrayList<>();
            for (Stage stage : Stage.values()) {
                List<FairnessReport.GroupStat> stats = new ArrayList<>(groups.size());
                double lowest = Double.MAX_VALUE;
                double highest = -1;
                int unsuppressed = 0;
                for (int g = 0; g < groups.size(); g++) {
                    if (applicants[g] < MINIMUM_GROUP_SIZE) {
                        stats.add(new FairnessReport.GroupStat(groups.get(g), null, null));
                        continue;
                    }
                    double rate = (double) reached[g][stage.ordinal()] / applicants[g];
                    stats.add(new FairnessReport.GroupStat(groups.get(g), reached[g][stage.ordinal()], round(rate, 4)));
                    lowest = Math.min(lowest, rate);
                    highest = Math.max(highest, rate);
                    unsuppressed++;
                }
                Double ratio = null;
                boolean flagged = false;
                if (unsuppressed >= 2 && highest > 0) {
                    double raw = lowest / highest;
                    ratio = round(raw, 3);
                    flagged = raw < FOUR_FIFTHS;
                }
                stages.add(new FairnessReport.StageReport(stage.name(), stats, ratio, flagged));
            }
            dimensions.add(new FairnessReport.DimensionReport(dimension, stages));
        }
        return new FairnessReport(scope, jobId, MINIMUM_GROUP_SIZE, responseRate, dimensions, NOTE);
    }

    static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
