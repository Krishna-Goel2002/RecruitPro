package com.recruitpro.hr.service;

import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.exception.UnauthorizedException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.common.util.LikePatterns;
import com.recruitpro.evidence.service.MatchTriggerService;
import com.recruitpro.hr.dto.*;
import com.recruitpro.hr.entity.*;
import com.recruitpro.hr.repository.*;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Jobs and requirements. Staff see and change only their organisation's jobs (D3) and get {@link JobStaffResponse};
 * candidates see {@link JobPublicResponse} for PUBLISHED jobs with an open deadline from every organisation (M3).
 * Job fields are editable in DRAFT, PUBLISHED and ON_HOLD; requirement changes on a PUBLISHED or ON_HOLD job
 * increment {@code requirements_version}, are audited and queue re-matching of every application.
 */
@Service
@Transactional
public class JobService {
    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "title", "department", "location", "status", "deadline");
    static final int MAX_REQUIREMENTS = 100;
    static final int MAX_ALTERNATIVE_LENGTH = 200;
    public static final String CLOSED_BY_HR = "CLOSED_BY_HR";
    /** Audit action of an HR job status change (R2-M03; the admin override and the deadline closer have their own). */
    public static final String AUDIT_JOB_STATUS_CHANGED = "JOB_STATUS_CHANGED";

    private final JobRepository jobRepository;
    private final JobRequirementRepository requirementRepository;
    private final HrJobAccessService jobAccess;
    private final HrProfileService hrProfileService;
    private final UserRepository userRepository;
    private final ApplicationRepository applicationRepository;
    private final OrganizationRepository organizationRepository;
    private final AuditService auditService;
    private final MatchTriggerService matchTriggerService;

    @PersistenceContext
    private EntityManager entityManager;

    public JobService(JobRepository jobRepository, JobRequirementRepository requirementRepository,
                      HrJobAccessService jobAccess, HrProfileService hrProfileService, UserRepository userRepository,
                      ApplicationRepository applicationRepository, OrganizationRepository organizationRepository,
                      AuditService auditService, MatchTriggerService matchTriggerService) {
        this.jobRepository = jobRepository;
        this.requirementRepository = requirementRepository;
        this.jobAccess = jobAccess;
        this.hrProfileService = hrProfileService;
        this.userRepository = userRepository;
        this.applicationRepository = applicationRepository;
        this.organizationRepository = organizationRepository;
        this.auditService = auditService;
        this.matchTriggerService = matchTriggerService;
    }

    public JobStaffResponse create(JobCreateRequest request) {
        validateSalary(request.minimumSalary(), request.maximumSalary());
        validateDeadline(request.deadline());
        CurrentUser current = jobAccess.requireJobManager();
        User user = userRepository.findById(current.userId())
                .orElseThrow(() -> new UnauthorizedException("Authentication is required"));
        HRProfile profile = hrProfileService.ensureProfileFor(user).orElseThrow(() ->
                new BadRequestException("Your account does not have an HR profile. Contact an administrator to provision one."));
        Job job = new Job();
        job.setCreatedBy(profile);
        job.setOrganizationId(current.organizationId());
        apply(job, request.title(), request.description(), request.department(), request.location(), request.jobType(),
                request.experienceLevel(), request.minimumSalary(), request.maximumSalary(), request.vacancies(), request.deadline());
        return staffResponse(jobRepository.save(job));
    }

    /** Staff listing: the caller's organisation, any status (optionally filtered). */
    @Transactional(readOnly = true)
    public PageResponse<JobStaffResponse> listForStaff(int page, int size, JobStatus status, String department,
                                                       String location, JobType jobType, String search,
                                                       String sort, Sort.Direction direction) {
        validateListing(page, size, department, location, search, sort);
        Long organizationId = jobAccess.requireJobReader().organizationId();
        Page<Job> jobs = jobRepository.findAll(specification(false, organizationId, status, department, location, jobType, search),
                PageRequest.of(page, size, Sort.by(direction, sort)));
        List<Long> ids = jobs.getContent().stream().map(Job::getId).toList();
        Map<Long, List<RequirementResponse>> requirements = requirementsByJob(ids);
        Map<Long, Long> counts = applicationCounts(ids);
        List<JobStaffResponse> content = jobs.getContent().stream()
                .map(job -> JobStaffResponse.from(job, requirements.getOrDefault(job.getId(), List.of()),
                        counts.getOrDefault(job.getId(), 0L)))
                .toList();
        return page(jobs, content);
    }

    /** Candidate listing (M3, L1): PUBLISHED jobs whose deadline is open, across organisations; status is ignored. */
    @Transactional(readOnly = true)
    public PageResponse<JobPublicResponse> listForCandidate(int page, int size, String department, String location,
                                                            JobType jobType, String search, String sort,
                                                            Sort.Direction direction) {
        validateListing(page, size, department, location, search, sort);
        Page<Job> jobs = jobRepository.findAll(specification(true, null, null, department, location, jobType, search),
                PageRequest.of(page, size, Sort.by(direction, sort)));
        List<Long> ids = jobs.getContent().stream().map(Job::getId).toList();
        Map<Long, List<RequirementResponse>> requirements = requirementsByJob(ids);
        Map<Long, String> organizationNames = organizationNames(jobs.getContent().stream().map(Job::getOrganizationId).toList());
        List<JobPublicResponse> content = jobs.getContent().stream()
                .map(job -> JobPublicResponse.from(job, organizationNames.get(job.getOrganizationId()),
                        requirements.getOrDefault(job.getId(), List.of())))
                .toList();
        return page(jobs, content);
    }

    @Transactional(readOnly = true)
    public JobStaffResponse getForStaff(Long id) {
        return staffResponse(jobAccess.requireJobReadAccess(id));
    }

    /** 404 unless the job is open for applications. */
    @Transactional(readOnly = true)
    public JobPublicResponse getForCandidate(Long id) {
        Job job = jobRepository.findById(id).filter(j -> j.isOpenForApplications(UtcTime.now()))
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + id));
        String organizationName = organizationRepository.findById(job.getOrganizationId()).map(Organization::getName).orElse(null);
        return JobPublicResponse.from(job, organizationName, requirementResponses(job.getId()));
    }

    /** Text fields, salary, vacancies and deadline; DRAFT, PUBLISHED and ON_HOLD jobs. */
    public JobStaffResponse update(Long id, JobUpdateRequest request) {
        validateSalary(request.minimumSalary(), request.maximumSalary());
        // R2-M04: lock the row first; an unlocked full-row UPDATE could write PUBLISHED back over a concurrent close.
        Job job = lockedJob(id);
        requireEditable(job);
        if (!sameMillisecond(job.getDeadline(), request.deadline())) {
            validateDeadline(request.deadline());
        }
        apply(job, request.title(), request.description(), request.department(), request.location(), request.jobType(),
                request.experienceLevel(), request.minimumSalary(), request.maximumSalary(), request.vacancies(), request.deadline());
        return staffResponse(job);
    }

    public JobStaffResponse transition(Long id, JobStatus target) {
        if (target == null) throw new BadRequestException("status is required");
        // R2-M04: the deadline closer and the admin override lock the row; so does HR, so no transition is lost.
        Job job = lockedJob(id);
        JobStatus previous = job.getStatus();
        try {
            if (target == JobStatus.PUBLISHED) validatePublish(job);
            job.transitionTo(target);
        } catch (IllegalArgumentException ex) { throw new BadRequestException(ex.getMessage()); }
        if (target == JobStatus.CLOSED) {
            job.setClosedReason(CLOSED_BY_HR);
        }
        // R2-M03: HR status changes are audited like the admin override and the automatic close.
        auditService.record(AUDIT_JOB_STATUS_CHANGED, "JOB", String.valueOf(job.getId()),
                "Job #" + job.getId() + " (" + job.getTitle() + "): " + previous + " -> " + target);
        return staffResponse(job);
    }

    /**
     * DELETE /api/v1/jobs/{id}: DRAFT jobs without applications only (a draft was never published, so it has none;
     * the check guards the history anyway). Published jobs are closed instead, never deleted. Audited (JOB_DELETED);
     * requirements, work samples and the interview kit go with it (ON DELETE CASCADE).
     */
    public void delete(Long id) {
        Job job = lockedJob(id);
        if (job.getStatus() != JobStatus.DRAFT)
            throw new BadRequestException("Only draft jobs can be deleted; close the job instead so its applicants and history are kept");
        if (applicationRepository.countByJobId(job.getId()) > 0)
            throw new BadRequestException("This job has applications and cannot be deleted; close it instead");
        String title = job.getTitle();
        Long jobId = job.getId();
        jobRepository.delete(job);
        jobRepository.flush();
        auditService.record(AuditActions.JOB_DELETED, "JOB", String.valueOf(jobId), "Draft job #" + jobId + " (" + title + ") deleted");
    }

    /**
     * POST /api/v1/jobs/{id}/duplicate: a new DRAFT with the job's fields and requirements (in order), titled
     * "… (copy)". Any status can be copied (re-opening a closed role is a copy). A deadline already in the past is
     * dropped. Work samples, the interview kit and applications are not copied.
     */
    public JobStaffResponse duplicate(Long id) {
        Job source = jobAccess.requireManageAccess(id);
        CurrentUser current = jobAccess.requireJobManager();
        User user = userRepository.findById(current.userId())
                .orElseThrow(() -> new UnauthorizedException("Authentication is required"));
        HRProfile profile = hrProfileService.ensureProfileFor(user).orElseThrow(() ->
                new BadRequestException("Your account does not have an HR profile. Contact an administrator to provision one."));
        Job copy = new Job();
        copy.setCreatedBy(profile);
        copy.setOrganizationId(source.getOrganizationId());
        LocalDateTime deadline = source.getDeadline() != null && source.getDeadline().isAfter(UtcTime.now()) ? source.getDeadline() : null;
        apply(copy, copyTitle(source.getTitle()), source.getDescription(), source.getDepartment(), source.getLocation(),
                source.getJobType(), source.getExperienceLevel(), source.getMinimumSalary(), source.getMaximumSalary(),
                Math.max(1, source.getVacancies()), deadline);
        Job saved = jobRepository.save(copy);
        int position = 0;
        for (JobRequirement requirement : requirementRepository.findByJobIdOrderByDisplayOrderAscIdAsc(source.getId())) {
            JobRequirement clone = new JobRequirement();
            clone.setJob(saved);
            clone.setType(requirement.getType());
            clone.setDescription(requirement.getDescription());
            clone.setMandatory(requirement.isMandatory());
            clone.setKnockout(requirement.isKnockout());
            clone.setMinimumYears(requirement.getMinimumYears());
            clone.setAlternatives(requirement.getAlternatives() == null ? new ArrayList<>() : new ArrayList<>(requirement.getAlternatives()));
            clone.setProficiency(requirement.getProficiency());
            clone.setDisplayOrder(position++);
            requirementRepository.save(clone);
        }
        requirementRepository.flush();
        return staffResponse(saved);
    }

    /** "Title (copy)", cut so that it stays within the 255-character column. */
    static String copyTitle(String title) {
        String base = title == null || title.isBlank() ? "Untitled job" : title.trim();
        String suffix = " (copy)";
        return base.length() + suffix.length() > 255 ? base.substring(0, 255 - suffix.length()) + suffix : base + suffix;
    }

    public RequirementResponse addRequirement(Long jobId, RequirementInput request) {
        Job job = lockedEditableJob(jobId);
        if (requirementRepository.countByJobId(job.getId()) >= MAX_REQUIREMENTS) {
            throw new BadRequestException("A job can have at most " + MAX_REQUIREMENTS + " requirements");
        }
        JobRequirement requirement = new JobRequirement();
        requirement.setJob(job);
        applyRequirement(requirement, request);
        requirement.setDisplayOrder(requirementRepository.maxDisplayOrder(job.getId()) + 1);
        JobRequirement saved = requirementRepository.save(requirement);
        requirementsChanged(job, "Requirement added: " + saved.getDescription());
        return RequirementResponse.from(saved);
    }

    public RequirementResponse updateRequirement(Long jobId, Long requirementId, RequirementInput request) {
        Job job = lockedEditableJob(jobId);
        JobRequirement requirement = findRequirement(job.getId(), requirementId);
        applyRequirement(requirement, request);
        requirementsChanged(job, "Requirement " + requirement.getId() + " updated: " + requirement.getDescription());
        return RequirementResponse.from(requirement);
    }

    public void deleteRequirement(Long jobId, Long requirementId) {
        Job job = lockedEditableJob(jobId);
        JobRequirement requirement = findRequirement(job.getId(), requirementId);
        requirementRepository.delete(requirement);
        requirementsChanged(job, "Requirement " + requirementId + " deleted: " + requirement.getDescription());
    }

    /**
     * Replaces every requirement of the job. Items carrying the id of one of the job's requirements update it in
     * place; other items are created; requirements not listed are deleted. The request order is stored
     * ({@code displayOrder} 0..n-1). R6-B2 (integration pass): when the list names exactly the job's requirements
     * with unchanged content, only the order is saved: no new requirements version, no audit row, no re-match (and
     * nothing at all when the order is the same too).
     */
    public JobStaffResponse replaceRequirements(Long jobId, RequirementsReplaceRequest request) {
        if (request == null || request.requirements() == null) throw new BadRequestException("requirements is required");
        if (request.requirements().size() > MAX_REQUIREMENTS)
            throw new BadRequestException("A job can have at most " + MAX_REQUIREMENTS + " requirements");
        Job job = lockedEditableJob(jobId);
        Map<Long, JobRequirement> existing = new LinkedHashMap<>();
        for (JobRequirement requirement : requirementRepository.findByJobIdOrderByDisplayOrderAscIdAsc(job.getId())) {
            existing.put(requirement.getId(), requirement);
        }
        if (onlyOrderDiffers(existing, request.requirements())) {
            int position = 0;
            for (RequirementInput input : request.requirements()) {
                existing.get(input.id()).setDisplayOrder(position++);
            }
            requirementRepository.flush();
            return staffResponse(job);
        }
        Set<Long> kept = new HashSet<>();
        int position = 0;
        for (RequirementInput input : request.requirements()) {
            if (input == null) throw new BadRequestException("Requirement entries must not be null");
            JobRequirement requirement = input.id() == null ? null : existing.get(input.id());
            if (input.id() != null && requirement == null)
                throw new BadRequestException("Requirement " + input.id() + " does not belong to this job");
            if (requirement != null && !kept.add(requirement.getId()))
                throw new BadRequestException("Requirement " + input.id() + " is listed more than once");
            if (requirement == null) {
                requirement = new JobRequirement();
                requirement.setJob(job);
            }
            applyRequirement(requirement, input);
            requirement.setDisplayOrder(position++);
            requirementRepository.save(requirement);
        }
        int removed = 0;
        for (JobRequirement requirement : existing.values()) {
            if (!kept.contains(requirement.getId())) {
                requirementRepository.delete(requirement);
                removed++;
            }
        }
        requirementRepository.flush();
        requirementsChanged(job, "Requirements replaced: " + request.requirements().size() + " listed, " + removed + " removed");
        return staffResponse(job);
    }

    // ---- requirement helpers ----

    /**
     * True when {@code inputs} lists every one of the job's requirements exactly once, by id, each with the content it
     * already has (after the same normalisation a save applies): a pure reorder (or no change at all). Invalid input
     * fails here with the same 400 a save would give.
     */
    private boolean onlyOrderDiffers(Map<Long, JobRequirement> existing, List<RequirementInput> inputs) {
        if (inputs.size() != existing.size()) return false;
        Set<Long> seen = new HashSet<>();
        for (RequirementInput input : inputs) {
            if (input == null || input.id() == null || !seen.add(input.id())) return false;
            JobRequirement stored = existing.get(input.id());
            if (stored == null) return false;
            JobRequirement wanted = new JobRequirement();
            applyRequirement(wanted, input);
            if (wanted.getType() != stored.getType()
                    || !Objects.equals(wanted.getDescription(), stored.getDescription())
                    || wanted.isMandatory() != stored.isMandatory()
                    || wanted.isKnockout() != stored.isKnockout()
                    || !Objects.equals(wanted.getMinimumYears(), stored.getMinimumYears())
                    || !Objects.equals(wanted.getAlternatives(), stored.getAlternatives())
                    || wanted.getProficiency() != stored.getProficiency()) {
                return false;
            }
        }
        return true;
    }

    /** Loads the job for a requirement change and locks its row so that version increments do not race. */
    private Job lockedEditableJob(Long jobId) {
        Job job = lockedJob(jobId);
        requireEditable(job);
        return job;
    }

    /** The caller's (managed) job re-read with its row locked (PESSIMISTIC_WRITE). */
    private Job lockedJob(Long jobId) {
        Job job = jobAccess.requireManageAccess(jobId);
        entityManager.refresh(job, LockModeType.PESSIMISTIC_WRITE);
        return job;
    }

    private void requirementsChanged(Job job, String details) {
        if (job.getStatus() != JobStatus.PUBLISHED && job.getStatus() != JobStatus.ON_HOLD) {
            return;
        }
        job.setRequirementsVersion(job.getRequirementsVersion() + 1);
        jobRepository.save(job);
        auditService.record(AuditActions.JOB_REQUIREMENTS_CHANGED, "JOB", String.valueOf(job.getId()),
                details + " (requirements version " + job.getRequirementsVersion() + ")");
        matchTriggerService.requestMatchForJob(job.getId());
    }

    private void applyRequirement(JobRequirement requirement, RequirementInput input) {
        if (input.type() == null) throw new BadRequestException("Requirement type is required");
        if (!StringUtils.hasText(input.description())) throw new BadRequestException("Requirement description is required");
        String description = input.description().trim();
        if (description.length() > 500) throw new BadRequestException("Requirement description must be at most 500 characters");
        Integer years = input.minimumYears();
        if (years != null && (years < 0 || years > 50)) throw new BadRequestException("Minimum years must be between 0 and 50");
        boolean mandatory = input.mandatory() == null || input.mandatory();
        boolean knockout = input.knockout() == null ? mandatory : input.knockout();
        if (knockout && !mandatory) throw new BadRequestException("A knockout requirement must be mandatory");
        // R2-H01: OTHER requirements are never scored automatically (they need human review), so as a ranking gate
        // they would hold every applicant in MUST_HAVES_UNVERIFIED. They are never knockouts.
        if (input.type() == RequirementType.OTHER) knockout = false;
        requirement.setType(input.type());
        requirement.setDescription(description);
        requirement.setMandatory(mandatory);
        requirement.setKnockout(knockout);
        requirement.setMinimumYears(years);
        requirement.setAlternatives(normalizeAlternatives(input.alternatives(), description));
        requirement.setProficiency(input.proficiency());
    }

    static List<String> normalizeAlternatives(List<String> alternatives, String description) {
        List<String> result = new ArrayList<>();
        if (alternatives == null) return result;
        Set<String> seen = new HashSet<>();
        seen.add(description.toLowerCase(Locale.ROOT));
        for (String alternative : alternatives) {
            if (alternative == null || alternative.isBlank()) continue;
            String trimmed = alternative.trim();
            if (trimmed.length() > MAX_ALTERNATIVE_LENGTH)
                throw new BadRequestException("Alternatives must be at most " + MAX_ALTERNATIVE_LENGTH + " characters");
            if (seen.add(trimmed.toLowerCase(Locale.ROOT))) result.add(trimmed);
        }
        if (result.size() > 10) throw new BadRequestException("A requirement can have at most 10 alternatives");
        return result;
    }

    // ---- listing helpers ----

    private void validateListing(int page, int size, String department, String location, String search, String sort) {
        if (page < 0 || size < 1 || size > 100) throw new BadRequestException("page must be non-negative and size must be between 1 and 100");
        if (!SORT_FIELDS.contains(sort)) throw new BadRequestException("Unsupported sort field: " + sort);
        if (department != null && department.length() > 100 || location != null && location.length() > 255
                || search != null && search.length() > 255)
            throw new BadRequestException("Job filters exceed their maximum allowed length");
    }

    private Specification<Job> specification(boolean candidateOnly, Long organizationId, JobStatus status, String department,
                                             String location, JobType jobType, String search) {
        final LocalDateTime now = UtcTime.now();
        return (root, query, cb) -> {
            List<Predicate> filters = new ArrayList<>();
            if (candidateOnly) {
                filters.add(cb.equal(root.get("status"), JobStatus.PUBLISHED));
                filters.add(cb.or(cb.isNull(root.get("deadline")), cb.greaterThan(root.<LocalDateTime>get("deadline"), now)));
            } else {
                filters.add(cb.equal(root.get("organizationId"), organizationId));
                if (status != null) filters.add(cb.equal(root.get("status"), status));
            }
            if (jobType != null) filters.add(cb.equal(root.get("jobType"), jobType));
            if (StringUtils.hasText(department)) {
                // Integration pass (R5#4): candidates type the department into a free-text box, so it matches as
                // "contains" (like location); the staff list keeps the exact, case-insensitive match of its select.
                filters.add(candidateOnly
                        ? cb.like(cb.lower(root.get("department")), LikePatterns.containsLower(department), LikePatterns.ESCAPE)
                        : cb.equal(cb.lower(root.get("department")), department.trim().toLowerCase(Locale.ROOT)));
            }
            if (StringUtils.hasText(location)) filters.add(cb.like(cb.lower(root.get("location")), LikePatterns.containsLower(location), LikePatterns.ESCAPE));
            if (StringUtils.hasText(search)) filters.add(cb.like(cb.lower(root.get("title")), LikePatterns.containsLower(search), LikePatterns.ESCAPE));
            return cb.and(filters.toArray(Predicate[]::new));
        };
    }

    private Map<Long, List<RequirementResponse>> requirementsByJob(List<Long> jobIds) {
        Map<Long, List<RequirementResponse>> result = new HashMap<>();
        if (jobIds.isEmpty()) return result;
        for (JobRequirement requirement : requirementRepository.findAllByJobIdIn(jobIds)) {
            result.computeIfAbsent(requirement.getJob().getId(), id -> new ArrayList<>()).add(RequirementResponse.from(requirement));
        }
        return result;
    }

    private Map<Long, Long> applicationCounts(List<Long> jobIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (jobIds.isEmpty()) return counts;
        for (Object[] row : applicationRepository.countGroupedByJobIds(jobIds)) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    private Map<Long, String> organizationNames(List<Long> organizationIds) {
        Map<Long, String> names = new HashMap<>();
        Set<Long> ids = new HashSet<>(organizationIds);
        ids.remove(null);
        if (ids.isEmpty()) return names;
        for (Organization organization : organizationRepository.findAllById(ids)) {
            names.put(organization.getId(), organization.getName());
        }
        return names;
    }

    private static <T> PageResponse<T> page(Page<Job> jobs, List<T> content) {
        return PageResponse.<T>builder().content(content)
                .page(jobs.getNumber()).size(jobs.getSize()).totalElements(jobs.getTotalElements())
                .totalPages(jobs.getTotalPages()).first(jobs.isFirst()).last(jobs.isLast()).build();
    }

    private List<RequirementResponse> requirementResponses(Long jobId) {
        return requirementRepository.findByJobIdOrderByDisplayOrderAscIdAsc(jobId).stream().map(RequirementResponse::from).toList();
    }

    private JobStaffResponse staffResponse(Job job) {
        return JobStaffResponse.from(job, requirementResponses(job.getId()), applicationRepository.countByJobId(job.getId()));
    }

    // ---- validation ----

    private void validatePublish(Job job) {
        if (!StringUtils.hasText(job.getTitle()) || !StringUtils.hasText(job.getDescription())
                || !StringUtils.hasText(job.getDepartment()) || !StringUtils.hasText(job.getLocation())
                || job.getJobType() == null || job.getExperienceLevel() == null || job.getVacancies() < 1)
            throw new IllegalArgumentException("Job must have a title, description, department, location, job type, experience level, and positive vacancies before publishing");
        validateSalary(job.getMinimumSalary(), job.getMaximumSalary());
        if (job.getDeadline() != null && !job.getDeadline().isAfter(UtcTime.now()))
            throw new IllegalArgumentException("Job deadline must be in the future before publishing");
    }

    private void apply(Job j, String title, String description, String department, String location, JobType type,
                       ExperienceLevel level, BigDecimal min, BigDecimal max, int vacancies, LocalDateTime deadline) {
        j.setTitle(title.trim()); j.setDescription(description.trim()); j.setDepartment(department.trim()); j.setLocation(location.trim());
        j.setJobType(type); j.setExperienceLevel(level); j.setMinimumSalary(min); j.setMaximumSalary(max);
        j.setVacancies(vacancies); j.setDeadline(deadline);
    }

    private void validateSalary(BigDecimal min, BigDecimal max) {
        if (min != null && min.signum() < 0 || max != null && max.signum() < 0 || min != null && max != null && min.compareTo(max) > 0)
            throw new BadRequestException("Salary values must be non-negative and minimum salary cannot exceed maximum salary");
    }
    private void validateDeadline(LocalDateTime deadline) {
        if (deadline != null && !deadline.isAfter(UtcTime.now())) throw new BadRequestException("deadline must be in the future");
    }
    /** JSON carries milliseconds while the database keeps microseconds: compare at millisecond precision. */
    static boolean sameMillisecond(LocalDateTime a, LocalDateTime b) {
        if (a == null || b == null) return a == b;
        return a.truncatedTo(ChronoUnit.MILLIS).equals(b.truncatedTo(ChronoUnit.MILLIS));
    }

    private void requireEditable(Job job) {
        if (!job.isEditable())
            throw new BadRequestException("Only draft, published or on-hold jobs can be edited");
    }
    private JobRequirement findRequirement(Long jobId, Long id) {
        return requirementRepository.findByIdAndJobId(id, jobId).orElseThrow(() -> new ResourceNotFoundException("Requirement not found for this job: " + id));
    }
}
