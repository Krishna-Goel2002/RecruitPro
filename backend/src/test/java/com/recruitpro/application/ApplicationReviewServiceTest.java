package com.recruitpro.application;

import com.recruitpro.application.entity.*;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.hr.dto.ApplicationDecisionRequest;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.hr.service.ApplicationReviewService;
import com.recruitpro.hr.service.HrJobAccessService;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;

class ApplicationReviewServiceTest {
    private final AtomicInteger auditWrites = new AtomicInteger();
    private final AtomicBoolean failStatusSave = new AtomicBoolean();
    private Application application;
    private User actor;
    private ApplicationReviewService service;

    @BeforeEach void setup() {
        auditWrites.set(0);
        failStatusSave.set(false);
        actor = User.builder().email("hr@example.test").firstName("Taylor").lastName("HR").role(Role.HR_MANAGER).build();
        actor.setId(42L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("hr@example.test", "n/a"));
        application = new Application(); application.setId(7L); application.setStatus(ApplicationStatus.SUBMITTED);
        Job job = new Job(); job.setId(3L); application.setJob(job);

        ApplicationRepository applications = proxy(ApplicationRepository.class, (method, args) -> switch (method) {
            case "findForReview" -> Optional.of(application);
            case "findByIdAndJobId" -> Optional.of(application);
            case "saveAndFlush" -> { if (failStatusSave.get()) throw new IllegalStateException("synthetic status persistence failure"); yield args[0]; }
            default -> null;
        });
        ApplicationDecisionHistoryRepository history = proxy(ApplicationDecisionHistoryRepository.class, (method, args) -> {
            if (method.equals("saveAndFlush")) { auditWrites.incrementAndGet(); ApplicationDecisionHistory event = (ApplicationDecisionHistory) args[0]; event.setId(11L); return event; }
            return null;
        });
        UserRepository users = proxy(UserRepository.class, (method, args) -> method.equals("findByEmail") ? Optional.of(actor) : null);
        HrJobAccessService access = new HrJobAccessService(null) {
            @Override public Job requireManageAccess(Long jobId) { return application.getJob(); }
        };
        service = new ApplicationReviewService(applications, history, users, access);
    }

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test void shortlistChangesStatusAndPersistsAuditActorAndComment() {
        var response = service.decide(3L, 7L, ApplicationStatus.SHORTLISTED, new ApplicationDecisionRequest(" Strong evidence "));
        assertEquals(ApplicationStatus.SHORTLISTED, application.getStatus());
        assertEquals(ApplicationStatus.SUBMITTED, response.previousStatus());
        assertEquals(ApplicationStatus.SHORTLISTED, response.newStatus());
        assertEquals("SHORTLIST", response.actionType());
        assertEquals(42L, response.actorUserId());
        assertEquals("Strong evidence", response.comment());
        assertEquals(1, auditWrites.get());
    }

    @Test void rejectionIsRecordedAsAnAuditedDecision() {
        var response = service.decide(3L, 7L, ApplicationStatus.REJECTED, new ApplicationDecisionRequest("Missing qualification"));
        assertEquals(ApplicationStatus.REJECTED, application.getStatus());
        assertEquals("REJECT", response.actionType());
        assertEquals(1, auditWrites.get());
    }

    @Test void rejectedAndWithdrawnApplicationsCannotBeShortlistedOrRejectedAgain() {
        application.setStatus(ApplicationStatus.REJECTED);
        assertThrows(BadRequestException.class, () -> service.decide(3L, 7L, ApplicationStatus.SHORTLISTED, null));
        application.setStatus(ApplicationStatus.WITHDRAWN);
        assertThrows(BadRequestException.class, () -> service.decide(3L, 7L, ApplicationStatus.REJECTED, null));
        assertEquals(0, auditWrites.get());
    }

    @Test void failedStatusPersistenceDoesNotWriteAuditEvent() {
        failStatusSave.set(true);
        assertThrows(IllegalStateException.class, () -> service.decide(3L, 7L, ApplicationStatus.SHORTLISTED, null));
        assertEquals(0, auditWrites.get());
    }

    @Test void onlyOwningHrOrAdminCanAccessJobReviewScope() {
        JobRepository jobs = proxy(JobRepository.class, (method, args) -> method.equals("findById") ? Optional.of(jobOwnedBy("owner@example.test")) : null);
        HrJobAccessService jobAccess = new HrJobAccessService(jobs);
        SecurityContextHolder.getContext().setAuthentication(auth("other@example.test"));
        assertThrows(ForbiddenException.class, () -> jobAccess.requireManageAccess(3L));
        SecurityContextHolder.getContext().setAuthentication(auth("owner@example.test"));
        assertNotNull(jobAccess.requireManageAccess(3L));
    }

    private Job jobOwnedBy(String email) {
        User owner = User.builder().email(email).firstName("Owner").lastName("HR").role(Role.HR_MANAGER).build();
        HRProfile profile = new HRProfile(); profile.setUser(owner);
        Job job = new Job(); job.setCreatedBy(profile); return job;
    }

    private UsernamePasswordAuthenticationToken auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, "n/a", java.util.List.of(new SimpleGrantedAuthority("ROLE_HR_MANAGER")));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
                invocation.call(method.getName(), args == null ? new Object[0] : args));
    }

    @FunctionalInterface private interface Invocation { Object call(String method, Object[] args) throws Throwable; }
}
