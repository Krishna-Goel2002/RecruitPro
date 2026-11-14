package com.recruitpro.interview.service;

import com.recruitpro.ai.dto.AiInterviewKitResponse;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.hr.dto.RequirementEvidenceResponse;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.interview.dto.InterviewQuestion;
import com.recruitpro.interview.dto.ScorecardRating;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Focus-requirement order, scorecard rating validation and interview-kit validation (plan 5.6). */
class InterviewRulesTest {

    private static final Map<String, String> ANCHORS = Map.of("1", "vague", "2", "partial", "3", "specific", "4", "results");

    @Test
    void unverifiedRequirementsComeFirstKnockoutsAndMustHavesLeading() {
        List<RequirementEvidenceResponse> evidence = List.of(
                evidence(1L, false, false, "MATCHED", EvidenceTier.INTERVIEW_VERIFIED),
                evidence(2L, false, false, "NOT_OBSERVED", null),
                evidence(3L, true, true, "MATCHED", EvidenceTier.SELF_CLAIMED),
                evidence(4L, true, false, "UNCLEAR", EvidenceTier.RESUME_STATED),
                evidence(5L, true, true, "MATCHED", EvidenceTier.RESUME_STATED),
                evidence(6L, false, false, "MATCHED", EvidenceTier.CORROBORATED));
        List<Long> order = InterviewService.focusOrder(evidence).stream().map(RequirementEvidenceResponse::requirementId).toList();
        assertEquals(List.of(3L, 4L, 2L, 5L, 6L, 1L), order);
    }

    @Test
    void ratingsAreOneToFourWithUniqueQuestionsAndTheirLinksComeFromTheKit() {
        Map<String, Long> kit = new HashMap<>();
        kit.put("q1", 7L);
        kit.put("q2", null);
        kit.put("q4", 99L);
        List<ScorecardRating> valid = InterviewService.validateRatings(List.of(
                new ScorecardRating(" q1 ", null, 4, "  "),
                new ScorecardRating("q2", 7L, 1, "short"),
                new ScorecardRating("q3", 7L, 4, null),
                new ScorecardRating("q4", null, 4, null)), Set.of(7L), kit);
        assertEquals("q1", valid.get(0).questionId());
        assertEquals(7L, valid.get(0).requirementId(), "the kit links q1 to requirement 7");
        assertNull(valid.get(0).notes());
        assertNull(valid.get(1).requirementId(), "the client cannot link a question the kit leaves unlinked");
        assertEquals("short", valid.get(1).notes());
        assertNull(valid.get(2).requirementId(), "a question outside the kit has no link");
        assertNull(valid.get(3).requirementId(), "a link to a requirement the job no longer has is dropped");
        assertThrows(BadRequestException.class, () -> InterviewService.validateRatings(
                List.of(new ScorecardRating("q1", null, 5, null)), Set.of(), kit));
        assertThrows(BadRequestException.class, () -> InterviewService.validateRatings(
                List.of(new ScorecardRating("q1", null, 0, null)), Set.of(), kit));
        assertThrows(BadRequestException.class, () -> InterviewService.validateRatings(
                List.of(new ScorecardRating("q1", 8L, 3, null)), Set.of(7L), kit));
        assertThrows(BadRequestException.class, () -> InterviewService.validateRatings(
                List.of(new ScorecardRating("q1", null, 3, null), new ScorecardRating("q1", null, 2, null)), Set.of(), kit));
        assertThrows(BadRequestException.class, () -> InterviewService.validateRatings(
                List.of(new ScorecardRating(" ", null, 3, null)), Set.of(), kit));
        assertEquals(0, InterviewService.validateRatings(List.of(), Set.of(), kit).size());
    }

    @Test
    void editedKitsAreValidatedAndBlankIdsAreAssigned() {
        List<InterviewQuestion> kit = InterviewKitService.validate(List.of(
                new InterviewQuestion("", 7L, "Tell me about Java", List.of("Your part?", " "), ANCHORS),
                new InterviewQuestion("q1", null, "How do you explain trade-offs?", null, ANCHORS)), Set.of(7L));
        assertEquals("q2", kit.get(0).id(), "generated ids never collide with explicit ones");
        assertEquals(List.of("Your part?"), kit.get(0).followUps());
        assertEquals("q1", kit.get(1).id());
        assertThrows(BadRequestException.class, () -> InterviewKitService.validate(List.of(
                new InterviewQuestion("q1", 8L, "Q?", List.of(), ANCHORS)), Set.of(7L)));
        assertThrows(BadRequestException.class, () -> InterviewKitService.validate(List.of(
                new InterviewQuestion("q1", null, "Q?", List.of(), Map.of("1", "a"))), Set.of()));
        assertThrows(BadRequestException.class, () -> InterviewKitService.validate(List.of(
                new InterviewQuestion("q1", null, "  ", List.of(), ANCHORS)), Set.of()));
        List<InterviewQuestion> tooMany = new ArrayList<>();
        for (int i = 0; i < 61; i++) {
            tooMany.add(new InterviewQuestion("q" + i, null, "Q" + i, List.of(), ANCHORS));
        }
        assertThrows(BadRequestException.class, () -> InterviewKitService.validate(tooMany, Set.of()));
    }

    @Test
    void aiQuestionsAreMappedLeniently() {
        List<InterviewQuestion> mapped = InterviewKitService.fromAi(List.of(
                new AiInterviewKitResponse.Question("q1", 7L, "Java?", List.of("More?"), ANCHORS),
                new AiInterviewKitResponse.Question("q1", 99L, "Duplicate", null, Map.of("1", "only")),
                new AiInterviewKitResponse.Question(null, null, "", List.of(), ANCHORS)), Set.of(7L));
        assertEquals(2, mapped.size());
        assertEquals("q2", mapped.get(1).id());
        assertNull(mapped.get(1).requirementId(), "links to other jobs' requirements are dropped");
        assertEquals(InterviewKitService.MISSING_ANCHOR, mapped.get(1).anchors().get("4"));
        assertEquals(0, InterviewKitService.fromAi(null, Set.of()).size());
    }

    private static RequirementEvidenceResponse evidence(Long id, boolean mandatory, boolean knockout, String status,
                                                        EvidenceTier tier) {
        return new RequirementEvidenceResponse(id, RequirementType.SKILL, "Requirement " + id, mandatory, knockout, null,
                List.of(), status, tier, null, null, "", null, null);
    }
}
