package com.recruitpro.assessment.service;

import com.recruitpro.common.exception.BadRequestException;

/** A submission arrived after the time limit plus the grace period (400; the assignment is marked EXPIRED). */
public class WorkSampleExpiredException extends BadRequestException {

    public WorkSampleExpiredException() {
        super("The time limit for this work sample (plus a 5-minute grace period) has passed; the submission was not accepted");
    }
}
