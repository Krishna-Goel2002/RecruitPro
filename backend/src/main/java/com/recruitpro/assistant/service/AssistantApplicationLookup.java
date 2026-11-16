package com.recruitpro.assistant.service;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.service.ApplicantQueryService;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.user.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Backend-only facts about applications the assistant mentions: job, organisation, status, whether the identity is
 * revealed (blind review, D4) and the candidate's name and e-mail. Names stay in the backend: they are used to strip
 * them from planner input and to show them in blocks to a user who may see them; never sent to the planner.
 * No permission check here: callers check access ({@code ApplicationAccess}).
 */
@Service
public class AssistantApplicationLookup {

    static final int MAX_IDS = 200;

    private final ApplicationRepository applicationRepository;
    private final OrganizationRepository organizationRepository;

    public AssistantApplicationLookup(ApplicationRepository applicationRepository,
                                      OrganizationRepository organizationRepository) {
        this.applicationRepository = applicationRepository;
        this.organizationRepository = organizationRepository;
    }

    /** One application. */
    public record AppInfo(Long applicationId, Long jobId, String jobTitle, Long organizationId, ApplicationStatus status,
                          boolean revealed, String firstName, String lastName, String email) {

        public String code() {
            return "A-" + applicationId;
        }

        /** The candidate's full name when the identity is revealed (or blind review is off), otherwise null. */
        public String revealedName() {
            if (!revealed) {
                return null;
            }
            String name = ((firstName == null ? "" : firstName.trim()) + " " + (lastName == null ? "" : lastName.trim())).trim();
            return name.isEmpty() ? null : name;
        }
    }

    @Transactional(readOnly = true)
    public Optional<AppInfo> find(Long applicationId) {
        return applicationId == null ? Optional.empty() : Optional.ofNullable(findAll(List.of(applicationId)).get(applicationId));
    }

    @Transactional(readOnly = true)
    public Map<Long, AppInfo> findAll(Collection<Long> applicationIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : applicationIds) {
            if (id != null && ids.size() < MAX_IDS) {
                ids.add(id);
            }
        }
        Map<Long, AppInfo> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        Map<Long, Boolean> blindByOrganization = new HashMap<>();
        List<Application> applications = new ArrayList<>(applicationRepository.findAllWithCandidateByIdIn(ids));
        for (Application application : applications) {
            Job job = application.getJob();
            Long organizationId = job.getOrganizationId();
            boolean blind = blindByOrganization.computeIfAbsent(organizationId, id -> id == null
                    || organizationRepository.findById(id).map(Organization::isBlindReview).orElse(true));
            User user = application.getCandidate() == null ? null : application.getCandidate().getUser();
            result.put(application.getId(), new AppInfo(application.getId(), job.getId(), job.getTitle(), organizationId,
                    application.getStatus(), ApplicantQueryService.isRevealed(application.getIdentityRevealedAt(), blind),
                    user == null ? null : user.getFirstName(), user == null ? null : user.getLastName(),
                    user == null ? null : user.getEmail()));
        }
        return result;
    }
}
