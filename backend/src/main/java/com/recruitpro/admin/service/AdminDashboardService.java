package com.recruitpro.admin.service;

import com.recruitpro.admin.dto.AdminDashboardMetricsResponse;
import com.recruitpro.admin.dto.SystemHealthResponse;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Organisation-scoped dashboard metrics (D3) with real system health (M11). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminDashboardService {
    private final UserRepository userRepository;
    private final JobRepository jobRepository;
    private final ApplicationRepository applicationRepository;
    private final SystemHealthService systemHealthService;
    private final SecurityUtils securityUtils;

    public AdminDashboardMetricsResponse getDashboardMetrics() {
        Long organizationId = securityUtils.requireOrganizationId();
        long totalUsers = userRepository.countByOrganizationId(organizationId);
        long totalCandidates = applicationRepository.countDistinctCandidatesByOrganizationId(organizationId);
        long totalHrUsers = userRepository.countByOrganizationIdAndRoleIn(organizationId, List.of(Role.HR_MANAGER, Role.RECRUITER));
        long totalJobs = jobRepository.countByOrganizationId(organizationId);
        long totalApplications = applicationRepository.countByJob_OrganizationId(organizationId);

        Map<String, Long> applicationsByStatus = new LinkedHashMap<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            applicationsByStatus.put(status.name(), applicationRepository.countByJob_OrganizationIdAndStatus(organizationId, status));
        }

        Map<String, Long> jobsByStatus = new LinkedHashMap<>();
        for (JobStatus status : JobStatus.values()) {
            jobsByStatus.put(status.name(), jobRepository.countByOrganizationIdAndStatus(organizationId, status));
        }

        long recentApplications = applicationRepository.countByJob_OrganizationIdAndAppliedAtAfter(organizationId,
                UtcTime.now().minusDays(7));

        SystemHealthResponse health = systemHealthService.check();

        return new AdminDashboardMetricsResponse(
                totalUsers,
                totalCandidates,
                totalHrUsers,
                totalJobs,
                totalApplications,
                applicationsByStatus,
                jobsByStatus,
                recentApplications,
                health.backend(),
                health.database(),
                health.aiService(),
                health
        );
    }
}
