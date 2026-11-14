package com.recruitpro.application;

import com.recruitpro.hr.controller.ApplicantController;
import com.recruitpro.hr.controller.ApplicationReviewController;
import com.recruitpro.hr.controller.HrJobToolsController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Role annotations of the HR review endpoints (plan 5.4): recruiters comment and read, managers decide. */
class ApplicationReviewControllerSecurityTest {

    @Test
    void decisionsAndRevealRequireAdminOrHrManager() {
        PreAuthorize classLevel = ApplicationReviewController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(classLevel);
        assertTrue(classLevel.value().contains("ADMIN") && classLevel.value().contains("HR_MANAGER"));
        assertFalse(classLevel.value().contains("RECRUITER"));
        for (String name : new String[]{"decide", "shortlist", "reject", "reveal"}) {
            Method method = method(ApplicationReviewController.class, name);
            assertTrue(method.getAnnotation(PreAuthorize.class) == null,
                    name + " must use the class-level ADMIN/HR_MANAGER rule");
        }
    }

    @Test
    void recruitersMayCommentReadHistoryDownloadRevealedResumesAndVerifyClaims() {
        for (String name : new String[]{"updateComment", "history", "resume", "verifyClaim"}) {
            PreAuthorize rule = method(ApplicationReviewController.class, name).getAnnotation(PreAuthorize.class);
            assertNotNull(rule, name + " needs its own rule");
            assertTrue(rule.value().contains("RECRUITER") && rule.value().contains("HR_MANAGER") && rule.value().contains("ADMIN"), name);
            assertFalse(rule.value().contains("CANDIDATE") || rule.value().contains("INTERVIEWER"), name);
        }
    }

    @Test
    void listDetailRetryRematchAndInterviewerRoutesAreProtected() {
        assertTrue(method(ApplicantController.class, "list").getAnnotation(PreAuthorize.class).value().contains("RECRUITER"));
        assertTrue(method(ApplicantController.class, "detail").getAnnotation(PreAuthorize.class).value().contains("RECRUITER"));
        assertFalse(method(ApplicantController.class, "retry").getAnnotation(PreAuthorize.class).value().contains("RECRUITER"));
        assertFalse(method(HrJobToolsController.class, "rematch").getAnnotation(PreAuthorize.class).value().contains("RECRUITER"));
        assertTrue(method(HrJobToolsController.class, "interviewers").getAnnotation(PreAuthorize.class).value().contains("INTERVIEWER"));
    }

    private static Method method(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + "." + name + " is missing"));
    }
}
