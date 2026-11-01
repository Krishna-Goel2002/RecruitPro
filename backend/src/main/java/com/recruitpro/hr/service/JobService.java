package com.recruitpro.hr.service;

import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.hr.dto.*;
import com.recruitpro.hr.entity.*;
import com.recruitpro.hr.repository.*;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
public class JobService {
    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "title", "department", "location", "status", "deadline");
    private final HRProfileRepository profileRepository;
    private final JobRepository jobRepository;
    private final JobRequirementRepository requirementRepository;
    private final HrJobAccessService jobAccess;

    public JobResponse create(JobCreateRequest request) {
        validateSalary(request.minimumSalary(), request.maximumSalary());
        validateDeadline(request.deadline());
        Job job = new Job();
        job.setCreatedBy(currentProfile());
        apply(job, request.title(), request.description(), request.department(), request.location(), request.jobType(),
                request.experienceLevel(), request.minimumSalary(), request.maximumSalary(), request.vacancies(), request.deadline());
        return response(jobRepository.save(job));
    }

    @Transactional(readOnly = true)
    public PageResponse<JobResponse> list(int page, int size, JobStatus status, String department,
                                           String location, JobType jobType, String search,
                                           String sort, Sort.Direction direction, boolean candidateOnly) {
        if (page < 0 || size < 1 || size > 100) throw new BadRequestException("page must be non-negative and size must be between 1 and 100");
        if (!SORT_FIELDS.contains(sort)) throw new BadRequestException("Unsupported sort field: " + sort);
        if (department != null && department.length() > 100 || location != null && location.length() > 255
                || search != null && search.length() > 255)
            throw new BadRequestException("Job filters exceed their maximum allowed length");
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sort));
        Specification<Job> spec = (root, query, cb) -> {
            List<Predicate> filters = new ArrayList<>();
            if (candidateOnly) filters.add(cb.equal(root.get("status"), JobStatus.PUBLISHED));
            else if (status != null) filters.add(cb.equal(root.get("status"), status));
            if (jobType != null) filters.add(cb.equal(root.get("jobType"), jobType));
            if (StringUtils.hasText(department)) filters.add(cb.equal(cb.lower(root.get("department")), department.trim().toLowerCase()));
            if (StringUtils.hasText(location)) filters.add(cb.like(cb.lower(root.get("location")), "%" + location.trim().toLowerCase() + "%"));
            if (StringUtils.hasText(search)) filters.add(cb.like(cb.lower(root.get("title")), "%" + search.trim().toLowerCase() + "%"));
            return cb.and(filters.toArray(Predicate[]::new));
        };
        Page<Job> jobs = jobRepository.findAll(spec, pageable);
        return PageResponse.<JobResponse>builder().content(jobs.getContent().stream().map(this::response).toList())
                .page(jobs.getNumber()).size(jobs.getSize()).totalElements(jobs.getTotalElements())
                .totalPages(jobs.getTotalPages()).first(jobs.isFirst()).last(jobs.isLast()).build();
    }

    @Transactional(readOnly = true)
    public JobResponse get(Long id, boolean candidateOnly) {
        Job job = findJob(id);
        if (candidateOnly && job.getStatus() != JobStatus.PUBLISHED) throw new ResourceNotFoundException("Job not found: " + id);
        return response(job);
    }

    public JobResponse update(Long id, JobUpdateRequest request) {
        validateSalary(request.minimumSalary(), request.maximumSalary());
        validateDeadline(request.deadline());
        Job job = jobAccess.requireManageAccess(id);
        requireDraft(job);
        apply(job, request.title(), request.description(), request.department(), request.location(), request.jobType(),
                request.experienceLevel(), request.minimumSalary(), request.maximumSalary(), request.vacancies(), request.deadline());
        return response(job);
    }

    public JobResponse transition(Long id, JobStatus target) {
        Job job = jobAccess.requireManageAccess(id);
        try {
            if (target == JobStatus.PUBLISHED) validatePublish(job);
            job.transitionTo(target);
        } catch (IllegalArgumentException ex) { throw new BadRequestException(ex.getMessage()); }
        return response(job);
    }

    public void delete(Long id) {
        Job job = jobAccess.requireManageAccess(id);
        requireDraft(job);
        jobRepository.delete(job);
    }

    public JobRequirementResponse addRequirement(Long jobId, JobRequirementCreateRequest request) {
        Job job = jobAccess.requireManageAccess(jobId); requireDraft(job); validateRequirement(request.type(), request.minimumYears());
        JobRequirement requirement = new JobRequirement();
        requirement.setJob(job); requirement.setType(request.type()); requirement.setDescription(request.description().trim());
        requirement.setMandatory(request.mandatory() == null || request.mandatory()); requirement.setMinimumYears(request.minimumYears());
        return JobRequirementResponse.from(requirementRepository.save(requirement));
    }

    public JobRequirementResponse updateRequirement(Long jobId, Long requirementId, JobRequirementUpdateRequest request) {
        Job job = jobAccess.requireManageAccess(jobId); requireDraft(job); validateRequirement(request.type(), request.minimumYears());
        JobRequirement requirement = findRequirement(jobId, requirementId);
        requirement.setType(request.type()); requirement.setDescription(request.description().trim());
        requirement.setMandatory(request.mandatory()); requirement.setMinimumYears(request.minimumYears());
        return JobRequirementResponse.from(requirement);
    }

    public void deleteRequirement(Long jobId, Long requirementId) {
        Job job = jobAccess.requireManageAccess(jobId); requireDraft(job);
        requirementRepository.delete(findRequirement(jobId, requirementId));
    }

    private void validatePublish(Job job) {
        if (!StringUtils.hasText(job.getTitle()) || !StringUtils.hasText(job.getDescription())
                || !StringUtils.hasText(job.getDepartment()) || !StringUtils.hasText(job.getLocation())
                || job.getJobType() == null || job.getExperienceLevel() == null || job.getVacancies() < 1)
            throw new IllegalArgumentException("Job must have a title, description, department, location, job type, experience level, and positive vacancies before publishing");
        validateSalary(job.getMinimumSalary(), job.getMaximumSalary());
        if (job.getDeadline() != null && !job.getDeadline().isAfter(LocalDateTime.now()))
            throw new IllegalArgumentException("Job deadline must be in the future before publishing");
    }

    private void apply(Job j, String title, String description, String department, String location, JobType type,
                       ExperienceLevel level, java.math.BigDecimal min, java.math.BigDecimal max, int vacancies, LocalDateTime deadline) {
        j.setTitle(title.trim()); j.setDescription(description.trim()); j.setDepartment(department.trim()); j.setLocation(location.trim());
        j.setJobType(type); j.setExperienceLevel(level); j.setMinimumSalary(min); j.setMaximumSalary(max);
        j.setVacancies(vacancies); j.setDeadline(deadline);
    }

    private void validateSalary(java.math.BigDecimal min, java.math.BigDecimal max) {
        if (min != null && min.signum() < 0 || max != null && max.signum() < 0 || min != null && max != null && min.compareTo(max) > 0)
            throw new BadRequestException("Salary values must be non-negative and minimum salary cannot exceed maximum salary");
    }
    private void validateDeadline(LocalDateTime deadline) {
        if (deadline != null && !deadline.isAfter(LocalDateTime.now())) throw new BadRequestException("deadline must be in the future");
    }
    private void validateRequirement(RequirementType type, Integer years) {
        if (type == null || years != null && years < 0) throw new BadRequestException("Requirement type is required and minimum years cannot be negative");
    }
    private void requireDraft(Job job) {
        if (job.getStatus() != JobStatus.DRAFT) throw new BadRequestException("Only draft jobs can be edited or deleted");
    }
    private HRProfile currentProfile() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return profileRepository.findByUserEmailIgnoreCase(email).orElseThrow(() ->
                new BadRequestException("Your account does not have an HR profile. Contact an administrator to provision one."));
    }
    private Job findJob(Long id) { return jobRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Job not found: " + id)); }
    private JobRequirement findRequirement(Long jobId, Long id) {
        return requirementRepository.findByIdAndJobId(id, jobId).orElseThrow(() -> new ResourceNotFoundException("Requirement not found for this job: " + id));
    }
    private JobResponse response(Job job) {
        return JobResponse.from(job, requirementRepository.findByJobIdOrderById(job.getId()).stream().map(JobRequirementResponse::from).toList());
    }
}
