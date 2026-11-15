package com.recruitpro.interview.repository;

/** Number of stored revisions of one scorecard. */
public record RevisionCountRow(Long scorecardId, Long count) {
}
