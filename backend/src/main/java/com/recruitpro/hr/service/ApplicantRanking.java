package com.recruitpro.hr.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Server-side ranking of a job's applicants (plan 5.4, C2, D6). Groups, in this order:
 * <ol>
 *   <li>RANKED: a current match run whose knockout requirements are all verified and which has a verified
 *       coverage; by verified coverage desc, claimed coverage desc, applied-at asc;</li>
 *   <li>MUST_HAVES_UNVERIFIED: a current match run with an unverified knockout (never an automatic
 *       rejection); same order;</li>
 *   <li>UNSCORED: no current match run (or no scorable requirement); by applied-at asc.</li>
 * </ol>
 * {@code rank} is the 1-based position over the whole list and is set only in RANKED. Pure (unit-tested).
 */
public final class ApplicantRanking {

    public enum RankGroup { RANKED, MUST_HAVES_UNVERIFIED, UNSCORED }

    /** One applicant; {@code scored} is true when a current match run exists. */
    public record Input(long applicationId, LocalDateTime appliedAt, boolean scored, Double verifiedCoverage,
                        Double claimedCoverage, Boolean knockoutsVerified) { }

    public record Ranked(Input input, RankGroup group, Integer rank) { }

    private static final Comparator<Double> DESC_NULLS_LAST = Comparator.nullsLast(Comparator.<Double>reverseOrder());
    private static final Comparator<LocalDateTime> ASC_NULLS_LAST = Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder());

    static final Comparator<Input> SCORED_ORDER = Comparator
            .comparing(Input::verifiedCoverage, DESC_NULLS_LAST)
            .thenComparing(Input::claimedCoverage, DESC_NULLS_LAST)
            .thenComparing(Input::appliedAt, ASC_NULLS_LAST)
            .thenComparingLong(Input::applicationId);

    static final Comparator<Input> UNSCORED_ORDER = Comparator
            .comparing(Input::appliedAt, ASC_NULLS_LAST)
            .thenComparingLong(Input::applicationId);

    private ApplicantRanking() {
    }

    public static RankGroup groupOf(Input input) {
        if (!input.scored()) {
            return RankGroup.UNSCORED;
        }
        if (!Boolean.TRUE.equals(input.knockoutsVerified())) {
            return RankGroup.MUST_HAVES_UNVERIFIED;
        }
        return input.verifiedCoverage() == null ? RankGroup.UNSCORED : RankGroup.RANKED;
    }

    public static List<Ranked> rank(Collection<Input> inputs) {
        List<Input> ranked = new ArrayList<>();
        List<Input> unverified = new ArrayList<>();
        List<Input> unscored = new ArrayList<>();
        for (Input input : inputs) {
            switch (groupOf(input)) {
                case RANKED -> ranked.add(input);
                case MUST_HAVES_UNVERIFIED -> unverified.add(input);
                case UNSCORED -> unscored.add(input);
            }
        }
        ranked.sort(SCORED_ORDER);
        unverified.sort(SCORED_ORDER);
        unscored.sort(UNSCORED_ORDER);
        List<Ranked> result = new ArrayList<>(inputs.size());
        for (Input input : ranked) {
            result.add(new Ranked(input, RankGroup.RANKED, result.size() + 1));
        }
        for (Input input : unverified) {
            result.add(new Ranked(input, RankGroup.MUST_HAVES_UNVERIFIED, null));
        }
        for (Input input : unscored) {
            result.add(new Ranked(input, RankGroup.UNSCORED, null));
        }
        return result;
    }
}
