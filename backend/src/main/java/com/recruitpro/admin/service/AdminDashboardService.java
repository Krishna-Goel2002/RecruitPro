package com.recruitpro.admin.service;

import com.recruitpro.admin.dto.AdminDashboardMetricsResponse;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.repository.HRProfileRepository;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminDashboardService {
    private final UserRepository userRepository;
    private final CandidateProfileRepository candidateProfileRepository;
    private final HRProfileRepository hrProfileRepository;
    private final JobRepository jobRepository;
    private final ApplicationRepository applicationRepository;

    @Value("${app.ai-service.queue-enabled:false}")
    private boolean aiQueueEnabled;

    public AdminDashboardMetricsResponse getDashboardMetrics() {
        long totalUsers = userRepository.count();
        long totalCandidates = candidateProfileRepository.count();
        long totalHrUsers = userRepository.findAllByRoleIn(List.of(Role.HR_MANAGER, Role.RECRUITER)).size();
        long totalJobs = jobRepository.count();
        long totalApplications = applicationRepository.count();

        Map<String, Long> applicationsByStatus = new LinkedHashMap<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            applicationsByStatus.put(status.name(), applicationRepository.countByStatus(status));
        }

        Map<String, Long> jobsByStatus = new LinkedHashMap<>();
        for (JobStatus status : JobStatus.values()) {
            jobsByStatus.put(status.name(), jobRepository.countByStatus(status));
        }

        long recentApplications = applicationRepository.countByAppliedAtAfter(LocalDateTime.now().minusDays(7));

        String backendHealth = "UP";
        String databaseHealth = "UP";
        String aiServiceHealth = aiQueueEnabled ? "UP" : "DISABLED";

        return new AdminDashboardMetricsResponse(
                totalUsers,
                totalCandidates,
                totalHrUsers,
                totalJobs,
                totalApplications,
                applicationsByStatus,
                jobsByStatus,
                recentApplications,
                backendHealth,
                databaseHealth,
                aiServiceHealth
        );
    }
}
