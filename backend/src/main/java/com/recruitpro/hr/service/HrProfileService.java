package com.recruitpro.hr.service;

import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.repository.HRProfileRepository;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * HR profiles are the job-creator identity. ADMIN, HR_MANAGER and RECRUITER users get one automatically
 * (sign-up, invitation acceptance, bootstrap, seed, role change and, as a fallback, first job creation).
 */
@Service
@RequiredArgsConstructor
public class HrProfileService {

    static final Set<Role> PROFILE_ROLES = EnumSet.of(Role.ADMIN, Role.HR_MANAGER, Role.RECRUITER);
    static final String DEFAULT_DEPARTMENT = "General";

    private final HRProfileRepository profileRepository;

    public static boolean needsProfile(Role role) {
        return role != null && PROFILE_ROLES.contains(role);
    }

    /** Returns the user's HR profile, creating it for roles that need one; empty for other roles. */
    @Transactional
    public Optional<HRProfile> ensureProfileFor(User user) {
        if (user == null || user.getId() == null || !needsProfile(user.getRole())) {
            return Optional.empty();
        }
        Optional<HRProfile> existing = profileRepository.findByUserId(user.getId());
        if (existing.isPresent()) {
            return existing;
        }
        HRProfile profile = new HRProfile();
        profile.setUser(user);
        profile.setDepartment(DEFAULT_DEPARTMENT);
        return Optional.of(profileRepository.save(profile));
    }
}
