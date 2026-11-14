package com.recruitpro.support;

import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.hr.entity.ExperienceLevel;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.JobType;
import com.recruitpro.hr.repository.HRProfileRepository;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

/** Test fixtures for organisations, staff, candidates and jobs (test classpath only). */
@Component
public class TestDataFactory {

    /** Placeholder hash for accounts that never sign in with a password. */
    public static final String UNUSABLE_PASSWORD_HASH = "test-only-not-a-bcrypt-hash";

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final HRProfileRepository hrProfiles;
    private final CandidateProfileRepository candidates;
    private final JobRepository jobs;
    private final JdbcTemplate jdbcTemplate;

    public TestDataFactory(OrganizationRepository organizations, UserRepository users, HRProfileRepository hrProfiles,
                           CandidateProfileRepository candidates, JobRepository jobs, JdbcTemplate jdbcTemplate) {
        this.organizations = organizations;
        this.users = users;
        this.hrProfiles = hrProfiles;
        this.candidates = candidates;
        this.jobs = jobs;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void cleanDatabase() {
        TestDatabase.clean(jdbcTemplate);
    }

    public Organization organization(String name) {
        Organization organization = new Organization();
        organization.setName(name);
        organization.setSlug(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + "-"
                + UUID.randomUUID().toString().substring(0, 8));
        return organizations.save(organization);
    }

    public User staff(Organization organization, String email, Role role) {
        return staff(organization, email, role, UNUSABLE_PASSWORD_HASH, true);
    }

    public User staff(Organization organization, String email, Role role, String passwordHash, boolean enabled) {
        return users.save(User.builder()
                .email(email)
                .passwordHash(passwordHash)
                .firstName("Test")
                .lastName("User")
                .role(role)
                .enabled(enabled)
                .organizationId(organization == null ? null : organization.getId())
                .build());
    }

    public HRProfile hrProfile(User user, String department) {
        HRProfile profile = new HRProfile();
        profile.setUser(user);
        profile.setDepartment(department);
        return hrProfiles.save(profile);
    }

    /** Staff user with an HR profile. */
    public HRProfile hrMember(Organization organization, String email, Role role) {
        return hrProfile(staff(organization, email, role), "Engineering");
    }

    public CandidateProfile candidate(String email) {
        User user = users.save(User.builder()
                .email(email)
                .passwordHash(UNUSABLE_PASSWORD_HASH)
                .firstName("Test")
                .lastName("Candidate")
                .role(Role.CANDIDATE)
                .enabled(true)
                .build());
        CandidateProfile profile = new CandidateProfile();
        profile.setUser(user);
        return candidates.save(profile);
    }

    public Job job(HRProfile owner, String title, JobStatus status) {
        Job job = new Job();
        job.setCreatedBy(owner);
        job.setOrganizationId(owner.getUser().getOrganizationId());
        job.setTitle(title);
        job.setDescription("Description for " + title);
        job.setDepartment("Engineering");
        job.setLocation("Remote");
        job.setJobType(JobType.FULL_TIME);
        job.setExperienceLevel(ExperienceLevel.MID);
        job.setMinimumSalary(new BigDecimal("50000"));
        job.setMaximumSalary(new BigDecimal("90000"));
        job.setVacancies(1);
        job.setStatus(status);
        if (status == JobStatus.PUBLISHED) {
            job.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        return jobs.save(job);
    }
}
