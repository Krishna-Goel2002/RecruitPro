package com.recruitpro.admin.service;

import com.recruitpro.admin.dto.AdminJobOverviewResponse;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.util.LikePatterns;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.security.SecurityUtils;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Organisation-scoped job overview and the administrative status override (H7). */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminJobService {

    /**
     * H7: the only transitions an administrator may force. CLOSED and CANCELLED are terminal for everyone.
     */
    static final Map<JobStatus, Set<JobStatus>> OVERRIDE_TRANSITIONS = overrideTransitions();

    /** closed_reason of a job closed by the administrative override. */
    public static final String ADMIN_OVERRIDE = "ADMIN_OVERRIDE";

    private final JobRepository jobRepository;
    private final ApplicationRepository applicationRepository;
    private final AuditService auditService;
    private final SecurityUtils securityUtils;

    public static boolean isOverrideAllowed(JobStatus from, JobStatus to) {
        return from != null && to != null && OVERRIDE_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminJobOverviewResponse> listJobs(int page, int size, JobStatus status, String department,
            String search) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BadRequestException("page must be non-negative and size between 1 and 100");
        }
        Long organizationId = securityUtils.requireOrganizationId();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<Job> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (StringUtils.hasText(department)) {
                predicates.add(cb.equal(cb.lower(root.get("department")), department.trim().toLowerCase(Locale.ROOT)));
            }
            if (StringUtils.hasText(search)) {
                String pattern = LikePatterns.containsLower(search);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern, LikePatterns.ESCAPE),
                        cb.like(cb.lower(root.get("location")), pattern, LikePatterns.ESCAPE)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Job> jobs = jobRepository.findAll(spec, pageable);
        List<AdminJobOverviewResponse> content = jobs.getContent().stream().map(this::toResponse).toList();

        return PageResponse.<AdminJobOverviewResponse>builder()
                .content(content)
                .page(jobs.getNumber())
                .size(jobs.getSize())
                .totalElements(jobs.getTotalElements())
                .totalPages(jobs.getTotalPages())
                .first(jobs.isFirst())
                .last(jobs.isLast())
                .build();
    }

    public AdminJobOverviewResponse overrideJobStatus(Long jobId, JobStatus targetStatus, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BadRequestException("Reason is required for administrative status override");
        }
        if (targetStatus != JobStatus.ON_HOLD && targetStatus != JobStatus.CLOSED
                && targetStatus != JobStatus.CANCELLED) {
            throw new BadRequestException(
                    "Administrative override allows setting status to ON_HOLD, CLOSED, or CANCELLED");
        }
        Long organizationId = securityUtils.requireOrganizationId();
        Job job = jobRepository.findByIdAndOrganizationId(jobId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));

        JobStatus previousStatus = job.getStatus();
        if (previousStatus == targetStatus) {
            return toResponse(job);
        }
        if (!isOverrideAllowed(previousStatus, targetStatus)) {
            throw new BadRequestException("Administrative override cannot move a job from " + previousStatus
                    + " to " + targetStatus);
        }

        job.transitionTo(targetStatus);
        if (targetStatus == JobStatus.CLOSED) {
            job.setClosedReason(ADMIN_OVERRIDE);
        }
        Job saved = jobRepository.saveAndFlush(job);

        String safeReason = reason.trim();
        auditService.record(
                AuditActions.ADMIN_JOB_STATUS_OVERRIDE,
                "JOB",
                String.valueOf(job.getId()),
                String.format("Job #%d (%s) status changed from %s to %s. Reason: %s",
                        job.getId(), job.getTitle(), previousStatus, targetStatus, safeReason));

        return toResponse(saved);
    }

    private AdminJobOverviewResponse toResponse(Job job) {
        return new AdminJobOverviewResponse(
                job.getId(),
                job.getTitle(),
                job.getDepartment(),
                job.getLocation(),
                job.getJobType().name(),
                job.getExperienceLevel().name(),
                job.getStatus(),
                job.getVacancies(),
                job.getCreatedBy() != null && job.getCreatedBy().getUser() != null
                        ? job.getCreatedBy().getUser().getEmail()
                        : "Unknown",
                job.getCreatedAt(),
                applicationRepository.countByJobId(job.getId()));
    }

    private static Map<JobStatus, Set<JobStatus>> overrideTransitions() {
        Map<JobStatus, Set<JobStatus>> transitions = new EnumMap<>(JobStatus.class);
        transitions.put(JobStatus.DRAFT, EnumSet.of(JobStatus.CANCELLED));
        transitions.put(JobStatus.PUBLISHED, EnumSet.of(JobStatus.ON_HOLD, JobStatus.CLOSED));
        transitions.put(JobStatus.ON_HOLD, EnumSet.of(JobStatus.CLOSED));
        transitions.put(JobStatus.CLOSED, EnumSet.noneOf(JobStatus.class));
        transitions.put(JobStatus.CANCELLED, EnumSet.noneOf(JobStatus.class));
        return transitions;
    }
}
