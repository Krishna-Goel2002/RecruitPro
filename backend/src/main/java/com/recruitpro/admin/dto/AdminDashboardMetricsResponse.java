package com.recruitpro.admin.dto;

import java.util.Map;

/** Organisation-scoped metrics plus real system health (M11). */
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
        String aiServiceHealth,
        SystemHealthResponse health
) { }
