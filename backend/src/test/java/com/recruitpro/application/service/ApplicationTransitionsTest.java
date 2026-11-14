package com.recruitpro.application.service;

import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.recruitpro.application.entity.ApplicationDecisionAction.*;
import static com.recruitpro.application.entity.ApplicationStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/** Plan 3.4: the application state machine, reversible decisions and candidate withdrawal. */
class ApplicationTransitionsTest {

    @Test
    void forwardDecisionsFollowThePlanTable() {
        assertEquals(SHORTLISTED, ApplicationTransitions.target(SUBMITTED, SHORTLIST));
        assertEquals(REJECTED, ApplicationTransitions.target(SUBMITTED, REJECT));
        assertEquals(INTERVIEWING, ApplicationTransitions.target(SHORTLISTED, MOVE_TO_INTERVIEW));
        assertEquals(REJECTED, ApplicationTransitions.target(SHORTLISTED, REJECT));
        assertEquals(REJECTED, ApplicationTransitions.target(INTERVIEWING, REJECT));
        assertEquals(SELECTED, ApplicationTransitions.target(INTERVIEWING, SELECT));
    }

    @Test
    void undoAndReopenReverseDecisionsAndRequireAComment() {
        assertEquals(SUBMITTED, ApplicationTransitions.target(SHORTLISTED, UNDO_SHORTLIST));
        assertEquals(SUBMITTED, ApplicationTransitions.target(REJECTED, REOPEN));
        assertTrue(ApplicationTransitions.requiresComment(UNDO_SHORTLIST));
        assertTrue(ApplicationTransitions.requiresComment(REOPEN));
        assertFalse(ApplicationTransitions.requiresComment(SHORTLIST));
        assertFalse(ApplicationTransitions.requiresComment(REJECT));
    }

    @Test
    void everythingElseIsRejected() {
        assertNull(ApplicationTransitions.target(SUBMITTED, SELECT));
        assertNull(ApplicationTransitions.target(SUBMITTED, MOVE_TO_INTERVIEW));
        assertNull(ApplicationTransitions.target(SUBMITTED, UNDO_SHORTLIST));
        assertNull(ApplicationTransitions.target(SHORTLISTED, SHORTLIST));
        assertNull(ApplicationTransitions.target(INTERVIEWING, SHORTLIST));
        assertNull(ApplicationTransitions.target(REJECTED, REJECT));
        assertNull(ApplicationTransitions.target(SUBMITTED, REOPEN));
        for (ApplicationDecisionAction action : ApplicationDecisionAction.values()) {
            assertNull(ApplicationTransitions.target(SELECTED, action), "SELECTED is terminal: " + action);
            assertNull(ApplicationTransitions.target(WITHDRAWN, action), "WITHDRAWN is terminal: " + action);
        }
        for (ApplicationStatus status : ApplicationStatus.values()) {
            assertNull(ApplicationTransitions.target(status, COMMENT_UPDATED));
            assertNull(ApplicationTransitions.target(status, WITHDRAW));
            assertNull(ApplicationTransitions.target(status, IDENTITY_REVEALED));
        }
    }

    @Test
    void allowedDecisionsAreListedPerStatus() {
        assertEquals(List.of(SHORTLIST, REJECT), ApplicationTransitions.allowedDecisions(SUBMITTED));
        assertEquals(List.of(MOVE_TO_INTERVIEW, REJECT, UNDO_SHORTLIST), ApplicationTransitions.allowedDecisions(SHORTLISTED));
        assertEquals(List.of(SELECT, REJECT), ApplicationTransitions.allowedDecisions(INTERVIEWING));
        assertEquals(List.of(REOPEN), ApplicationTransitions.allowedDecisions(REJECTED));
        assertEquals(List.of(), ApplicationTransitions.allowedDecisions(SELECTED));
        assertEquals(List.of(), ApplicationTransitions.allowedDecisions(WITHDRAWN));
    }

    @Test
    void candidatesWithdrawOnlyFromOpenStatuses() {
        assertTrue(ApplicationTransitions.candidateMayWithdraw(SUBMITTED));
        assertTrue(ApplicationTransitions.candidateMayWithdraw(SHORTLISTED));
        assertTrue(ApplicationTransitions.candidateMayWithdraw(INTERVIEWING));
        assertFalse(ApplicationTransitions.candidateMayWithdraw(SELECTED));
        assertFalse(ApplicationTransitions.candidateMayWithdraw(REJECTED));
        assertFalse(ApplicationTransitions.candidateMayWithdraw(WITHDRAWN));
    }
}
