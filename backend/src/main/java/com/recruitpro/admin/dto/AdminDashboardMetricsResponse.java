package com.recruitpro.admin.dto;

import java.util.Map;

/**
 * Organisation-scoped metrics plus real system health (M11).
 *
 * <p>{@code totalUsers}: every account of the organisation (all staff roles). {@code totalCandidates}: candidates
 * who applied to the organisation's jobs. {@code totalHrUsers}: HR_MANAGER and RECRUITER accounts only (not ADMIN
 * or INTERVIEWER; R6-B8: the name is kept for API compatibility; the HR portal's "Staff" figure uses
 * {@code totalUsers}).</p>
 */
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
