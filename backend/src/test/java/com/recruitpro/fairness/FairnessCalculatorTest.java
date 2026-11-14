package com.recruitpro.fairness;

import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.fairness.dto.FairnessReport;
import com.recruitpro.fairness.service.FairnessCalculator;
import com.recruitpro.fairness.service.FairnessCalculator.Stage;
import com.recruitpro.fairness.service.FairnessCalculator.Subject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan 5.7 arithmetic: selection rates, suppression below 5, exclusions, adverse-impact ratio and the 0.8 flag. */
class FairnessCalculatorTest {

    @Test
    void selectionRatesSuppressionAndTheFourFifthsFlag() {
        List<Subject> subjects = new ArrayList<>();
        // 10 women: 5 shortlisted, 2 of them interviewed, 1 selected.
        subjects.addAll(people("WOMAN", 10, 5, 2, 1));
        // 10 men: 8 shortlisted, 4 interviewed, 2 selected.
        subjects.addAll(people("MAN", 10, 8, 4, 2));
        // 4 non-binary applicants: below the minimum group size.
        subjects.addAll(people("NON_BINARY", 4, 4, 4, 4));
        // Excluded: 6 PREFER_NOT and 7 non-respondents (all shortlisted, which must not change any rate).
        subjects.addAll(people("PREFER_NOT", 6, 6, 6, 6));
        for (int i = 0; i < 7; i++) {
            subjects.add(new Subject(false, Map.of(), Stage.SELECTED));
        }

        FairnessReport report = FairnessCalculator.report("JOB", 42L, subjects);

        assertEquals("JOB", report.scope());
        assertEquals(42L, report.jobId());
        assertEquals(5, report.minimumGroupSize());
        assertEquals(0.8108, report.surveyResponseRate(), 1e-9, "30 of 37 applicants answered the survey");
        assertEquals(1, report.dimensions().size(), "dimensions nobody answered are left out");
        FairnessReport.DimensionReport gender = report.dimensions().get(0);
        assertEquals("gender", gender.dimension());
        assertEquals(List.of("APPLIED", "SHORTLISTED", "INTERVIEWING", "SELECTED"),
                gender.stages().stream().map(FairnessReport.StageReport::stage).toList());

        FairnessReport.StageReport applied = gender.stages().get(0);
        assertEquals(List.of("WOMAN", "MAN", "NON_BINARY", "SELF_DESCRIBE"),
                applied.groups().stream().map(FairnessReport.GroupStat::group).toList(), "PREFER_NOT is never a group");
        assertEquals(1.0, applied.adverseImpactRatio());
        assertFalse(applied.flagged());

        FairnessReport.StageReport shortlisted = gender.stages().get(1);
        FairnessReport.GroupStat women = shortlisted.groups().get(0);
        FairnessReport.GroupStat men = shortlisted.groups().get(1);
        FairnessReport.GroupStat nonBinary = shortlisted.groups().get(2);
        FairnessReport.GroupStat selfDescribe = shortlisted.groups().get(3);
        assertEquals(5, women.count());
        assertEquals(0.5, women.selectionRate());
        assertEquals(8, men.count());
        assertEquals(0.8, men.selectionRate());
        assertNull(nonBinary.count(), "groups smaller than 5 are suppressed");
        assertNull(nonBinary.selectionRate());
        assertNull(selfDescribe.count(), "an empty group is suppressed too");
        assertEquals(0.625, shortlisted.adverseImpactRatio(), 1e-9);
        assertTrue(shortlisted.flagged(), "0.625 is below the four-fifths threshold");

        FairnessReport.StageReport selected = gender.stages().get(3);
        assertEquals(0.5, selected.adverseImpactRatio(), 1e-9);
        assertTrue(selected.flagged());
    }

    @Test
    void ratiosNeedTwoUnsuppressedGroupsAndAtLeastOneSelection() {
        List<Subject> subjects = new ArrayList<>();
        subjects.addAll(people("WOMAN", 6, 0, 0, 0));
        subjects.addAll(people("MAN", 6, 0, 0, 0));
        subjects.addAll(people("NON_BINARY", 2, 2, 2, 2));
        FairnessReport report = FairnessCalculator.report("ORGANIZATION", null, subjects);
        FairnessReport.StageReport shortlisted = report.dimensions().get(0).stages().get(1);
        assertNull(shortlisted.adverseImpactRatio(), "no unsuppressed group was shortlisted: no ratio");
        assertFalse(shortlisted.flagged());

        List<Subject> single = new ArrayList<>(people("WOMAN", 9, 3, 1, 0));
        single.addAll(people("MAN", 3, 3, 3, 3));
        FairnessReport oneGroup = FairnessCalculator.report("JOB", 1L, single);
        FairnessReport.StageReport stage = oneGroup.dimensions().get(0).stages().get(1);
        assertNull(stage.adverseImpactRatio(), "one unsuppressed group cannot be compared");
        assertFalse(stage.flagged());
        assertEquals(1.0, oneGroup.surveyResponseRate());

        FairnessReport empty = FairnessCalculator.report("JOB", 1L, List.of());
        assertEquals(0.0, empty.surveyResponseRate());
        assertTrue(empty.dimensions().isEmpty());
    }

    @Test
    void equalRatesAreNotFlaggedAndExactlyFourFifthsIsNotFlagged() {
        List<Subject> subjects = new ArrayList<>(people("WOMAN", 10, 8, 0, 0));
        subjects.addAll(people("MAN", 10, 10, 0, 0));
        FairnessReport.StageReport shortlisted = FairnessCalculator.report("JOB", 1L, subjects)
                .dimensions().get(0).stages().get(1);
        assertEquals(0.8, shortlisted.adverseImpactRatio(), 1e-9);
        assertFalse(shortlisted.flagged(), "the flag is strictly below 0.8");
    }

    @Test
    void theFurthestStageComesFromTheWholeDecisionHistory() {
        assertEquals(Stage.APPLIED, FairnessCalculator.furthestStage(ApplicationStatus.SUBMITTED, null));
        assertEquals(Stage.SHORTLISTED, FairnessCalculator.furthestStage(ApplicationStatus.REJECTED,
                List.of(ApplicationStatus.SUBMITTED, ApplicationStatus.SHORTLISTED, ApplicationStatus.SHORTLISTED,
                        ApplicationStatus.REJECTED)));
        assertEquals(Stage.INTERVIEWING, FairnessCalculator.furthestStage(ApplicationStatus.WITHDRAWN,
                List.of(ApplicationStatus.INTERVIEWING, ApplicationStatus.WITHDRAWN)));
        assertEquals(Stage.SELECTED, FairnessCalculator.furthestStage(ApplicationStatus.SELECTED, List.of()));
        assertEquals(Stage.SHORTLISTED, FairnessCalculator.furthestStage(ApplicationStatus.SUBMITTED,
                List.of(ApplicationStatus.SHORTLISTED, ApplicationStatus.SUBMITTED)), "an undone shortlist still counts");
    }

    /** {@code total} respondents of one gender group: the first {@code shortlisted} reached SHORTLISTED, and so on. */
    private static List<Subject> people(String gender, int total, int shortlisted, int interviewing, int selected) {
        List<Subject> subjects = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            Stage stage = i < selected ? Stage.SELECTED : i < interviewing ? Stage.INTERVIEWING
                    : i < shortlisted ? Stage.SHORTLISTED : Stage.APPLIED;
            Map<String, String> answers = new HashMap<>();
            answers.put("gender", gender);
            answers.put("ageBand", null);
            subjects.add(new Subject(true, answers, stage));
        }
        return subjects;
    }
}
