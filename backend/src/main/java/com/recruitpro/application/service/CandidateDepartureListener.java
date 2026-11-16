package com.recruitpro.application.service;

import com.recruitpro.application.entity.Application;

/**
 * Extension point (R2-M01): the candidate withdrew the application or deleted the account, so whatever is still open
 * for it must end: scheduled interviews are cancelled (the interviewer is told, with an ICS cancellation) and unfinished
 * work samples are cancelled. Implemented by the interview and work-sample modules; called through
 * {@link CandidateDepartures} inside the caller's transaction while the application row is locked.
 */
public interface CandidateDepartureListener {

    /**
     * @param application    the application (already WITHDRAWN for a withdrawal; any status for an account deletion)
     * @param accountDeleted true when the candidate deleted the account: the candidate must not be contacted any more
     */
    void candidateLeft(Application application, boolean accountDeleted);
}
