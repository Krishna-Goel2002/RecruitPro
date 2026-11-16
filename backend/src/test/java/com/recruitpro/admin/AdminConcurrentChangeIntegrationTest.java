package com.recruitpro.admin;

import com.recruitpro.admin.service.AdminDashboardService;
import com.recruitpro.admin.service.AdminJobService;
import com.recruitpro.admin.service.AdminUserService;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug hunt R1: administrative changes re-read their target row under a lock, so a stale copy in the persistence
 * context can neither reopen a terminal job nor overwrite a concurrent role change; a status "change" to the current
 * value writes nothing; the dashboard holds no transaction across its outbound health checks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminConcurrentChangeIntegrationTest {

    @Autowired TestDataFactory fixtures;
    @Autowired AdminJobService adminJobService;
    @Autowired AdminUserService adminUserService;
    @Autowired JobRepository jobs;
    @Autowired UserRepository users;
    @Autowired AuditEventRepository auditEvents;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    private Organization organization;
    private User admin;
    private User recruiter;
    private Job job;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        organization = fixtures.organization("Concurrent Admin Org");
        admin = fixtures.staff(organization, "admin-" + suffix + "@example.test", Role.ADMIN);
        recruiter = fixtures.staff(organization, "recruiter-" + suffix + "@example.test", Role.RECRUITER);
        HRProfile owner = fixtures.hrMember(organization, "hr-" + suffix + "@example.test", Role.HR_MANAGER);
        job = fixtures.job(owner, "Platform Engineer", JobStatus.PUBLISHED);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                admin.getEmail(), "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        fixtures.cleanDatabase();
    }

    @Test
    void overrideRereadsTheJobAndNeverReopensAJobClosedMeanwhile() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        BadRequestException refused = assertThrows(BadRequestException.class, () -> tx.executeWithoutResult(status -> {
            // A stale PUBLISHED copy sits in the persistence context while the deadline closer closes the row.
            assertEquals(JobStatus.PUBLISHED, jobs.findById(job.getId()).orElseThrow().getStatus());
            jdbcTemplate.update("UPDATE jobs SET status = 'CLOSED', closed_reason = 'DEADLINE_PASSED' WHERE id = ?", job.getId());
            adminJobService.overrideJobStatus(job.getId(), JobStatus.ON_HOLD, "Compliance review");
        }));
        assertTrue(refused.getMessage().contains("from CLOSED to ON_HOLD"), refused.getMessage());
    }

    @Test
    void statusChangeDoesNotOverwriteARoleChangedMeanwhile() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            assertEquals(Role.RECRUITER, users.findById(recruiter.getId()).orElseThrow().getRole());
            jdbcTemplate.update("UPDATE users SET role = 'HR_MANAGER' WHERE id = ?", recruiter.getId());
            adminUserService.updateUserStatus(recruiter.getId(), false);
        });
        User after = users.findById(recruiter.getId()).orElseThrow();
        assertFalse(after.isEnabled());
        assertEquals(Role.HR_MANAGER, after.getRole(), "the full-row update must not restore the stale role");
    }

    @Test
    void settingTheCurrentStatusAgainIsANoOp() {
        adminUserService.updateUserStatus(recruiter.getId(), true);
        assertTrue(users.findById(recruiter.getId()).orElseThrow().isEnabled());
        assertEquals(0, auditEvents.findAllByOrganizationIdAndActionOrderByIdAsc(organization.getId(), "USER_STATUS_CHANGED").size());

        adminUserService.updateUserStatus(recruiter.getId(), false);
        adminUserService.updateUserStatus(recruiter.getId(), false);
        assertEquals(1, auditEvents.findAllByOrganizationIdAndActionOrderByIdAsc(organization.getId(), "USER_STATUS_CHANGED").size());
    }

    @Test
    void dashboardHoldsNoTransactionAcrossOutboundHealthChecks() throws Exception {
        assertFalse(AnnotatedElementUtils.hasAnnotation(AdminDashboardService.class, Transactional.class));
        assertFalse(AnnotatedElementUtils.hasAnnotation(
                AdminDashboardService.class.getMethod("getDashboardMetrics"), Transactional.class));
    }
}
