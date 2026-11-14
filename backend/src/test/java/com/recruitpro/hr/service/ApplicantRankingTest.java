package com.recruitpro.hr.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Plan 5.4 / D6: RANKED by verified, claimed, applied-at; then must-haves unverified; then unscored. */
class ApplicantRankingTest {

    private static final LocalDateTime T0 = LocalDateTime.parse("2026-11-01T09:00:00");

    @Test
    void groupsAndOrdersApplicantsAndNumbersOnlyTheRankedGroup() {
        List<ApplicantRanking.Input> inputs = List.of(
                input(1, 0, true, 50.0, 90.0, true),
                input(2, 1, true, 80.0, 80.0, true),
                input(3, 2, true, 80.0, 95.0, true),
                input(4, 3, true, 95.0, 95.0, false),     // a knockout is not verified
                input(5, 4, false, null, null, null),     // no match run yet
                input(6, 5, true, 80.0, 95.0, true),      // ties with 3, applied later
                input(7, -1, false, null, null, null),    // unscored, applied first
                input(8, 6, true, 10.0, 10.0, false),
                input(9, 7, true, null, null, true));     // run without scorable requirements

        List<ApplicantRanking.Ranked> ranked = ApplicantRanking.rank(inputs);

        assertEquals(List.of(3L, 6L, 2L, 1L, 4L, 8L, 7L, 5L, 9L),
                ranked.stream().map(r -> r.input().applicationId()).toList());
        assertEquals(List.of(1, 2, 3, 4), ranked.subList(0, 4).stream().map(ApplicantRanking.Ranked::rank).toList());
        assertEquals(ApplicantRanking.RankGroup.RANKED, ranked.get(3).group());
        assertEquals(ApplicantRanking.RankGroup.MUST_HAVES_UNVERIFIED, ranked.get(4).group());
        assertEquals(ApplicantRanking.RankGroup.MUST_HAVES_UNVERIFIED, ranked.get(5).group());
        assertNull(ranked.get(4).rank());
        assertEquals(ApplicantRanking.RankGroup.UNSCORED, ranked.get(6).group());
        assertEquals(ApplicantRanking.RankGroup.UNSCORED, ranked.get(8).group());
        assertNull(ranked.get(8).rank());
    }

    @Test
    void claimedCoverageNeverOutranksVerifiedCoverage() {
        List<ApplicantRanking.Ranked> ranked = ApplicantRanking.rank(List.of(
                input(1, 0, true, 40.0, 100.0, true),
                input(2, 1, true, 41.0, 41.0, true)));
        assertEquals(2L, ranked.get(0).input().applicationId());
    }

    @Test
    void legacyRunsWithoutKnockoutInformationAreNotRankedAsVerified() {
        ApplicantRanking.Input legacy = input(1, 0, true, 70.0, null, null);
        assertEquals(ApplicantRanking.RankGroup.MUST_HAVES_UNVERIFIED, ApplicantRanking.groupOf(legacy));
    }

    private static ApplicantRanking.Input input(long id, int minutes, boolean scored, Double verified, Double claimed, Boolean knockouts) {
        return new ApplicantRanking.Input(id, T0.plusMinutes(minutes), scored, verified, claimed, knockouts);
    }
}
