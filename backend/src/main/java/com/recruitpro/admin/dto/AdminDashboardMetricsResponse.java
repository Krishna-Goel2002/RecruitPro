package com.recruitpro.admin.dto;

import java.util.Map;

public record AdminDashboardMetricsResponse(
        long totalUsers,
        long totalCandidates,
        long totalHrUsers,
        long totalJobs,
        long totalApplications,
        Map<String, Long> applicationsByStatus,
        Map<String, Long> jobsByStatus,
        long recentApplicationsCount,
        String backendHealth,
        String databaseHealth,
        String aiServiceHealth
) { }
