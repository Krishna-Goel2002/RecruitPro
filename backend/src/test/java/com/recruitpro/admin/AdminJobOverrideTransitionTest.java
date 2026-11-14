package com.recruitpro.admin;

import com.recruitpro.admin.service.AdminJobService;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** H7: the administrative override follows an explicit transition table; CLOSED and CANCELLED are terminal. */
class AdminJobOverrideTransitionTest {

    private static final Set<String> ALLOWED = Set.of(
            "DRAFT->CANCELLED",
            "PUBLISHED->ON_HOLD",
            "PUBLISHED->CLOSED",
            "ON_HOLD->CLOSED");

    @Test
    void onlyTheDocumentedOverridesAreAllowed() {
        for (JobStatus from : JobStatus.values()) {
            for (JobStatus to : JobStatus.values()) {
                boolean expected = ALLOWED.contains(from + "->" + to);
                assertEquals(expected, AdminJobService.isOverrideAllowed(from, to), from + " -> " + to);
            }
        }
    }

    @Test
    void everyAllowedOverrideIsALegalJobTransition() {
        for (JobStatus from : JobStatus.values()) {
            for (JobStatus to : JobStatus.values()) {
                if (AdminJobService.isOverrideAllowed(from, to)) {
                    Job job = new Job();
                    job.setStatus(from);
                    assertDoesNotThrow(() -> job.transitionTo(to), from + " -> " + to);
                    assertEquals(to, job.getStatus());
                }
            }
        }
    }

    @Test
    void terminalStatesCannotBeOverridden() {
        for (JobStatus to : JobStatus.values()) {
            assertEquals(false, AdminJobService.isOverrideAllowed(JobStatus.CLOSED, to));
            assertEquals(false, AdminJobService.isOverrideAllowed(JobStatus.CANCELLED, to));
        }
        assertEquals(false, AdminJobService.isOverrideAllowed(null, JobStatus.CLOSED));
    }
}
