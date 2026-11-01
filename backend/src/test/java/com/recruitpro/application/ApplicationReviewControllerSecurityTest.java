package com.recruitpro.application;

import com.recruitpro.hr.controller.ApplicationReviewController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationReviewControllerSecurityTest {
    @Test void everyHrDecisionAndHistoryRouteRequiresAdminOrHrManager() {
        PreAuthorize authorization = ApplicationReviewController.class.getAnnotation(PreAuthorize.class);
        assertTrue(authorization != null && authorization.value().contains("ADMIN") && authorization.value().contains("HR_MANAGER"));
        for (String method : new String[]{"shortlist", "reject", "updateComment", "history"}) {
            boolean secured = java.util.Arrays.stream(ApplicationReviewController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(method))
                    .anyMatch(candidate -> candidate.getAnnotation(PreAuthorize.class) != null || authorization != null);
            assertTrue(secured, method + " must be protected by controller role authorization");
        }
    }
}
