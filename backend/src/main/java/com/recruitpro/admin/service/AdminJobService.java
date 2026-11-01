package com.recruitpro.admin.service;

import com.recruitpro.admin.audit.AdminAuditService;
import com.recruitpro.admin.dto.AdminJobOverviewResponse;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.repository.JobRepository;
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
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminJobService {
    private final JobRepository jobRepository;
    private final ApplicationRepository applicationRepository;
    private final AdminAuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<AdminJobOverviewResponse> listJobs(int page, int size, JobStatus status, String department,
            String search) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BadRequestException("page must be non-negative and size between 1 and 100");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<Job> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (StringUtils.hasText(department)) {
                predicates.add(cb.equal(cb.lower(root.get("department")), department.trim().toLowerCase()));
            }
            if (StringUtils.hasText(search)) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern),
                        cb.like(cb.lower(root.get("location")), pattern)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Job> jobs = jobRepository.findAll(spec, pageable);
        List<AdminJobOverviewResponse> content = jobs.getContent().stream()
                .map(job -> new AdminJobOverviewResponse(
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
                        applicationRepository.countByJobId(job.getId())))
                .toList();

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

        // Administrative overrides support ON_HOLD, CLOSED, and CANCELLED
        if (targetStatus != JobStatus.ON_HOLD && targetStatus != JobStatus.CLOSED
                && targetStatus != JobStatus.CANCELLED) {
            throw new BadRequestException(
                    "Administrative override allows setting status to ON_HOLD, CLOSED, or CANCELLED");
        }

        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));

        JobStatus previousStatus = job.getStatus();
        if (previousStatus == targetStatus) {
            return toResponse(job);
        }

        job.setStatus(targetStatus);
        Job saved = jobRepository.saveAndFlush(job);

        String safeReason = StringUtils.hasText(reason) ? reason.trim() : "Administrative action";
        auditService.record(
                "ADMIN_JOB_STATUS_OVERRIDE",
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
}
