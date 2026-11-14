package com.recruitpro.user.service;

import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.auth.validation.PasswordPolicy;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.DuplicateResourceException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.service.HrProfileService;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Creates accounts (candidates, organisation staff) with the password policy, normalised email and the
 * profiles each role needs, and tracks failed sign-ins for lockout (S1).
 */
@Service
public class UserAccountService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final HrProfileService hrProfileService;
    private final CandidateProfileRepository candidateProfileRepository;
    private final AuditService auditService;
    private final int maxFailedAttempts;
    private final long lockoutMinutes;

    public UserAccountService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                              HrProfileService hrProfileService, CandidateProfileRepository candidateProfileRepository,
                              AuditService auditService,
                              @Value("${app.auth.lockout.max-attempts:5}") int maxFailedAttempts,
                              @Value("${app.auth.lockout.duration-minutes:15}") long lockoutMinutes) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.hrProfileService = hrProfileService;
        this.candidateProfileRepository = candidateProfileRepository;
        this.auditService = auditService;
        this.maxFailedAttempts = Math.max(1, maxFailedAttempts);
        this.lockoutMinutes = Math.max(1, lockoutMinutes);
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Organisation staff account; ADMIN, HR_MANAGER and RECRUITER also get an HR profile. */
    @Transactional
    public User createStaffUser(Long organizationId, String email, String firstName, String lastName,
                                String rawPassword, Role role, boolean emailVerified) {
        if (role == null || role == Role.CANDIDATE) {
            throw new BadRequestException("A staff role is required");
        }
        if (organizationId == null) {
            throw new BadRequestException("Staff accounts must belong to an organisation");
        }
        User saved = createUser(email, firstName, lastName, rawPassword, role, organizationId, emailVerified);
        hrProfileService.ensureProfileFor(saved);
        return saved;
    }

    /** Candidate account with its candidate profile (candidates belong to no organisation). */
    @Transactional
    public User createCandidateUser(String email, String firstName, String lastName, String rawPassword,
                                    boolean emailVerified) {
        User saved = createUser(email, firstName, lastName, rawPassword, Role.CANDIDATE, null, emailVerified);
        CandidateProfile profile = new CandidateProfile();
        profile.setUser(saved);
        candidateProfileRepository.save(profile);
        return saved;
    }

    /** Counts a failed sign-in; the fifth consecutive failure locks the account for 15 minutes. */
    @Transactional
    public void recordFailedLogin(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            int failures = user.getFailedLoginCount() + 1;
            if (failures >= maxFailedAttempts) {
                user.setFailedLoginCount(0);
                user.setLockedUntil(UtcTime.now().plusMinutes(lockoutMinutes));
                auditService.recordFor(user.getOrganizationId(), user.getId(), user.getEmail(), AuditActions.LOGIN_LOCKED,
                        "USER", String.valueOf(user.getId()),
                        "Account locked for " + lockoutMinutes + " minutes after " + failures + " failed sign-in attempts");
            } else {
                user.setFailedLoginCount(failures);
            }
        });
    }

    @Transactional
    public void recordSuccessfulLogin(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            if (user.getFailedLoginCount() != 0 || user.getLockedUntil() != null) {
                user.setFailedLoginCount(0);
                user.setLockedUntil(null);
            }
        });
    }

    /** Sets a new password (policy checked), records the change time and clears any lockout. */
    @Transactional
    public void applyNewPassword(User user, String rawPassword) {
        PasswordPolicy.requireValid(rawPassword);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setPasswordChangedAt(UtcTime.now());
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        userRepository.save(user);
    }

    private User createUser(String email, String firstName, String lastName, String rawPassword, Role role,
                            Long organizationId, boolean emailVerified) {
        String normalized = normalizeEmail(email);
        if (normalized == null || normalized.isBlank() || normalized.length() > 255) {
            throw new BadRequestException("A valid email address is required");
        }
        if (firstName == null || firstName.isBlank() || lastName == null || lastName.isBlank()) {
            throw new BadRequestException("First and last name are required");
        }
        PasswordPolicy.requireValid(rawPassword);
        if (userRepository.existsByEmail(normalized)) {
            throw new DuplicateResourceException("Email is already registered");
        }
        User user = User.builder()
                .email(normalized)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .firstName(firstName.trim())
                .lastName(lastName.trim())
                .role(role)
                .enabled(true)
                .organizationId(organizationId)
                .emailVerifiedAt(emailVerified ? UtcTime.now() : null)
                .build();
        return userRepository.saveAndFlush(user);
    }
}
