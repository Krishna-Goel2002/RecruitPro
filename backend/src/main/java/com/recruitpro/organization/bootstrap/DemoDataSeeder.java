package com.recruitpro.organization.bootstrap;

import com.recruitpro.auth.validation.PasswordPolicy;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.entity.ExperienceLevel;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.JobType;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.hr.repository.JobRequirementRepository;
import com.recruitpro.hr.service.HrProfileService;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.organization.service.OrganizationService;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import com.recruitpro.user.service.UserAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Idempotent demo data (app.seed.demo=true): organisation "Acme Labs" with admin@acme.test, hr@acme.test,
 * recruiter@acme.test, interviewer@acme.test, the candidate candidate@demo.test and two published jobs with
 * requirements. Existing accounts and jobs are left untouched.
 */
@Service
public class DemoDataSeeder {

    public static final String ORGANIZATION_SLUG = "acme-labs";
    public static final String ORGANIZATION_NAME = "Acme Labs";
    public static final String ADMIN_EMAIL = "admin@acme.test";
    public static final String HR_EMAIL = "hr@acme.test";
    public static final String RECRUITER_EMAIL = "recruiter@acme.test";
    public static final String INTERVIEWER_EMAIL = "interviewer@acme.test";
    public static final String CANDIDATE_EMAIL = "candidate@demo.test";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final OrganizationRepository organizationRepository;
    private final OrganizationService organizationService;
    private final UserRepository userRepository;
    private final UserAccountService userAccountService;
    private final HrProfileService hrProfileService;
    private final JobRepository jobRepository;
    private final JobRequirementRepository requirementRepository;
    /** WP-PLAT demo assessments (setter-injected to keep the constructor stable for other packages). */
    private DemoAssessmentSeeder assessmentSeeder;

    @Autowired
    public void setAssessmentSeeder(DemoAssessmentSeeder assessmentSeeder) {
        this.assessmentSeeder = assessmentSeeder;
    }

    public DemoDataSeeder(OrganizationRepository organizationRepository, OrganizationService organizationService,
                          UserRepository userRepository, UserAccountService userAccountService,
                          HrProfileService hrProfileService, JobRepository jobRepository,
                          JobRequirementRepository requirementRepository) {
        this.organizationRepository = organizationRepository;
        this.organizationService = organizationService;
        this.userRepository = userRepository;
        this.userAccountService = userAccountService;
        this.hrProfileService = hrProfileService;
        this.jobRepository = jobRepository;
        this.requirementRepository = requirementRepository;
    }

    @Transactional
    public void seed(String password) {
        if (!PasswordPolicy.isValid(password)) {
            throw new IllegalStateException("app.seed.password does not meet the password policy: " + PasswordPolicy.MESSAGE);
        }
        Organization organization = organizationRepository.findBySlug(ORGANIZATION_SLUG)
                .orElseGet(() -> organizationService.createOrganization(ORGANIZATION_NAME, ORGANIZATION_SLUG));
        Long organizationId = organization.getId();

        staff(organizationId, ADMIN_EMAIL, "Ada", "Admin", Role.ADMIN, password);
        User hrManager = staff(organizationId, HR_EMAIL, "Harper", "Reyes", Role.HR_MANAGER, password);
        staff(organizationId, RECRUITER_EMAIL, "Riley", "Chen", Role.RECRUITER, password);
        staff(organizationId, INTERVIEWER_EMAIL, "Ivan", "Petrov", Role.INTERVIEWER, password);
        if (!userRepository.existsByEmail(CANDIDATE_EMAIL)) {
            userAccountService.createCandidateUser(CANDIDATE_EMAIL, "Casey", "Candidate", password, true);
        }

        Optional<HRProfile> owner = hrManager != null && organizationId.equals(hrManager.getOrganizationId())
                ? hrProfileService.ensureProfileFor(hrManager) : Optional.empty();
        if (owner.isEmpty()) {
            log.warn("Demo jobs not created: {} is not an HR manager of {}", HR_EMAIL, ORGANIZATION_NAME);
            return;
        }
        publishedJob(organizationId, owner.get(), "Backend Engineer (Java)",
                """
                Build and operate the services behind our recruiting products. You will design REST APIs with \
                Java and Spring Boot, model data in PostgreSQL, and work with product and design on features \
                from discovery to production.""",
                "Engineering", "Bengaluru (hybrid)", ExperienceLevel.MID, "1800000", "3000000", 2,
                List.of(
                        requirement(RequirementType.SKILL, "Java", true, null),
                        requirement(RequirementType.SKILL, "Spring Boot", true, null),
                        requirement(RequirementType.SKILL, "PostgreSQL", false, null),
                        requirement(RequirementType.EXPERIENCE, "Backend development", true, 3),
                        requirement(RequirementType.EDUCATION, "Bachelor's degree in computer science or a related field", false, null)));
        publishedJob(organizationId, owner.get(), "Data Analyst",
                """
                Turn hiring and product data into decisions. You will write SQL, build dashboards and \
                explain findings to non-technical colleagues.""",
                "Analytics", "Remote (India)", ExperienceLevel.ENTRY, "900000", "1500000", 1,
                List.of(
                        requirement(RequirementType.SKILL, "SQL", true, null),
                        requirement(RequirementType.SKILL, "Python", true, null),
                        requirement(RequirementType.SKILL, "Tableau or Power BI", false, null),
                        requirement(RequirementType.EXPERIENCE, "Data analysis", true, 1)));
        if (assessmentSeeder != null) {
            // WP-PLAT: kit, work sample (scored), completed + scheduled interview with a scorecard.
            assessmentSeeder.seed(organizationId);
        }
        log.info("Demo data ready: organisation '{}' with {}, {}, {}, {} and candidate {} (password from app.seed.password)",
                ORGANIZATION_NAME, ADMIN_EMAIL, HR_EMAIL, RECRUITER_EMAIL, INTERVIEWER_EMAIL, CANDIDATE_EMAIL);
    }

    private User staff(Long organizationId, String email, String firstName, String lastName, Role role, String password) {
        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            return existing.get();
        }
        return userAccountService.createStaffUser(organizationId, email, firstName, lastName, password, role, true);
    }

    private void publishedJob(Long organizationId, HRProfile owner, String title, String description, String department,
                              String location, ExperienceLevel level, String minimumSalary, String maximumSalary,
                              int vacancies, List<RequirementSpec> requirements) {
        if (jobRepository.existsByOrganizationIdAndTitle(organizationId, title)) {
            return;
        }
        LocalDateTime now = UtcTime.now();
        Job job = new Job();
        job.setOrganizationId(organizationId);
        job.setCreatedBy(owner);
        job.setTitle(title);
        job.setDescription(description);
        job.setDepartment(department);
        job.setLocation(location);
        job.setJobType(JobType.FULL_TIME);
        job.setExperienceLevel(level);
        job.setMinimumSalary(new BigDecimal(minimumSalary));
        job.setMaximumSalary(new BigDecimal(maximumSalary));
        job.setVacancies(vacancies);
        job.setStatus(JobStatus.PUBLISHED);
        job.setPublishedAt(now);
        job.setDeadline(now.plusDays(60));
        Job saved = jobRepository.save(job);
        for (RequirementSpec spec : requirements) {
            JobRequirement requirement = new JobRequirement();
            requirement.setJob(saved);
            requirement.setType(spec.type());
            requirement.setDescription(spec.description());
            requirement.setMandatory(spec.mandatory());
            requirement.setKnockout(spec.mandatory());
            requirement.setMinimumYears(spec.minimumYears());
            requirementRepository.save(requirement);
        }
    }

    private static RequirementSpec requirement(RequirementType type, String description, boolean mandatory, Integer minimumYears) {
        return new RequirementSpec(type, description, mandatory, minimumYears);
    }

    private record RequirementSpec(RequirementType type, String description, boolean mandatory, Integer minimumYears) {
    }
}
