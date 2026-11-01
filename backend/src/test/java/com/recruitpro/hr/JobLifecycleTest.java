package com.recruitpro.hr;

import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JobLifecycleTest {
    @Test
    void permitsOnlyDocumentedTransitions() {
        Job job = new Job();
        job.setStatus(JobStatus.DRAFT);
        job.transitionTo(JobStatus.PUBLISHED);
        assertNotNull(job.getPublishedAt());
        job.transitionTo(JobStatus.ON_HOLD);
        job.transitionTo(JobStatus.PUBLISHED);
        job.transitionTo(JobStatus.CLOSED);
        assertThrows(IllegalArgumentException.class, () -> job.transitionTo(JobStatus.PUBLISHED));
    }

    @Test
    void draftMayBeCancelledButClosedAndCancelledAreTerminal() {
        Job job = new Job();
        job.setStatus(JobStatus.DRAFT);
        job.transitionTo(JobStatus.CANCELLED);
        assertThrows(IllegalArgumentException.class, () -> job.transitionTo(JobStatus.PUBLISHED));
        assertThrows(IllegalArgumentException.class, () -> job.transitionTo(JobStatus.CLOSED));
    }
}
