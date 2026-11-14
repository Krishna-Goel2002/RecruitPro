package com.recruitpro.assessment.service;

import com.recruitpro.assessment.dto.CriterionScore;
import com.recruitpro.assessment.dto.RubricCriterion;
import com.recruitpro.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Rubric validation and score matching (plan 5.6: 1-4 per criterion; the requirement link comes from the rubric). */
class WorkSampleRulesTest {

    private static final Map<String, String> ANCHORS = Map.of("1", "poor", "2", "basic", "3", "solid", "4", "excellent");
    private static final List<RubricCriterion> RUBRIC = List.of(
            new RubricCriterion(7L, "Java design", ANCHORS),
            new RubricCriterion(null, "Communication", ANCHORS));

    @Test
    void scoresArePairedWithTheRubricByCriterionAndKeepTheRubricLinks() {
        List<CriterionScore> scores = WorkSampleService.matchScores(RUBRIC, List.of(
                new CriterionScore(null, " communication ", 2, "  "),
                new CriterionScore(99L, "JAVA DESIGN", 4, " clean layering ")));
        assertEquals(2, scores.size());
        assertEquals("Java design", scores.get(0).criterion());
        assertEquals(7L, scores.get(0).requirementId(), "the client cannot re-link a criterion");
        assertEquals(4, scores.get(0).score());
        assertEquals("clean layering", scores.get(0).note());
        assertNull(scores.get(1).requirementId());
        assertNull(scores.get(1).note(), "blank notes are dropped");
    }

    @Test
    void everyCriterionNeedsExactlyOneScoreBetweenOneAndFour() {
        assertThrows(BadRequestException.class, () -> WorkSampleService.matchScores(RUBRIC,
                List.of(new CriterionScore(null, "Java design", 3, null))));
        assertThrows(BadRequestException.class, () -> WorkSampleService.matchScores(RUBRIC, List.of(
                new CriterionScore(null, "Java design", 3, null), new CriterionScore(null, "Java design", 3, null))));
        assertThrows(BadRequestException.class, () -> WorkSampleService.matchScores(RUBRIC, List.of(
                new CriterionScore(null, "Java design", 0, null), new CriterionScore(null, "Communication", 3, null))));
        assertThrows(BadRequestException.class, () -> WorkSampleService.matchScores(RUBRIC, List.of(
                new CriterionScore(null, "Java design", 3, null), new CriterionScore(null, "Communication", null, null))));
        assertThrows(BadRequestException.class, () -> WorkSampleService.matchScores(RUBRIC, List.of(
                new CriterionScore(null, "Java design", 3, "x".repeat(2001)), new CriterionScore(null, "Communication", 3, null))));
        assertThrows(BadRequestException.class, () -> WorkSampleService.matchScores(List.of(), List.of(
                new CriterionScore(null, "Java design", 3, null))));
    }

    @Test
    void rubricsNeedNamedCriteriaCompleteAnchorsAndRequirementsOfTheJob() {
        List<RubricCriterion> valid = WorkSampleService.validateRubric(RUBRIC, Set.of(7L));
        assertEquals(List.of("1", "2", "3", "4"), List.copyOf(valid.get(0).anchors().keySet()));
        assertThrows(BadRequestException.class, () -> WorkSampleService.validateRubric(RUBRIC, Set.of(8L)));
        assertEquals(2, WorkSampleService.validateRubric(RUBRIC, null).size(), "null skips the link check");
        assertThrows(BadRequestException.class, () -> WorkSampleService.validateRubric(List.of(), Set.of()));
        assertThrows(BadRequestException.class, () -> WorkSampleService.validateRubric(
                List.of(new RubricCriterion(null, " ", ANCHORS)), Set.of()));
        assertThrows(BadRequestException.class, () -> WorkSampleService.validateRubric(
                List.of(new RubricCriterion(null, "Clarity", Map.of("1", "a", "2", "b", "3", "c"))), Set.of()));
        assertThrows(BadRequestException.class, () -> WorkSampleService.validateRubric(
                List.of(new RubricCriterion(null, "Clarity", Map.of("1", "a", "2", "b", "3", "c",
                        "4", WorkSampleService.MISSING_ANCHOR))), Set.of()));
    }
}
