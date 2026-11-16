package com.recruitpro.organization.service;

import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.auth.service.AuthService;
import com.recruitpro.auth.service.AuthSession;
import com.recruitpro.common.crypto.SecretCipher;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.DuplicateResourceException;
import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.organization.dto.OrganizationResponse;
import com.recruitpro.organization.dto.OrganizationSignupRequest;
import com.recruitpro.organization.dto.OrganizationUpdateRequest;
import com.recruitpro.organization.dto.SsoDomains;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import com.recruitpro.user.service.UserAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * D3: organisations. Self-service sign-up ({@code app.organizations.self-signup-enabled}), first-run
 * bootstrap, and the administrator's organisation settings.
 */
@Service
public class OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);
    private static final int MAX_SLUG_LENGTH = 80;
    /** R1-U6: sign-ups that collide on the slug (two sign-ups with the same name at once) are retried this often. */
    static final int MAX_SIGNUP_ATTEMPTS = 3;

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final UserAccountService userAccountService;
    private final AuthService authService;
    private final AuditService auditService;
    private final SecurityUtils securityUtils;
    private final SecretCipher secretCipher;
    private final boolean selfSignupEnabled;
    private final TransactionTemplate transactionTemplate;

    public OrganizationService(OrganizationRepository organizationRepository, UserRepository userRepository,
                               UserAccountService userAccountService, AuthService authService,
                               AuditService auditService, SecurityUtils securityUtils, SecretCipher secretCipher,
                               PlatformTransactionManager transactionManager,
                               @Value("${app.organizations.self-signup-enabled:true}") boolean selfSignupEnabled) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.userAccountService = userAccountService;
        this.authService = authService;
        this.auditService = auditService;
        this.securityUtils = securityUtils;
        this.secretCipher = secretCipher;
        this.selfSignupEnabled = selfSignupEnabled;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * POST /api/v1/organizations/signup: new organisation with its first ADMIN; returns a session. Each attempt runs in
     * its own transaction; when two sign-ups with the same name race for the same slug, the loser's unique violation
     * is retried in a fresh transaction (which then sees the winner's slug and picks the next one) instead of a
     * generic 409 (R1-U6). A concurrent sign-up with the same e-mail ends as "Email is already registered".
     */
    public AuthSession signup(OrganizationSignupRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> signupOnce(request));
            } catch (DataIntegrityViolationException ex) {
                if (attempt >= MAX_SIGNUP_ATTEMPTS) {
                    throw ex;
                }
                log.info("Organisation sign-up collided with a concurrent sign-up (attempt {}); retrying", attempt);
            }
        }
    }

    private AuthSession signupOnce(OrganizationSignupRequest request) {
        if (!selfSignupEnabled) {
            throw new ForbiddenException("Organisation sign-up is disabled on this installation");
        }
        String email = UserAccountService.normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("Email is already registered");
        }
        Organization organization = createOrganization(request.organizationName(), null);
        User admin = userAccountService.createStaffUser(organization.getId(), email, request.firstName(),
                request.lastName(), request.password(), Role.ADMIN, false);
        authService.sendVerificationEmail(admin);
        auditService.recordFor(organization.getId(), admin.getId(), admin.getEmail(), AuditActions.ORGANIZATION_CREATED,
                "ORGANIZATION", String.valueOf(organization.getId()), "Organisation created through self-service sign-up");
        return authService.createSession(admin);
    }

    /**
     * Creates the first organisation and administrator when no organisation exists (BootstrapRunner).
     *
     * @return true when something was created
     */
    @Transactional
    public boolean bootstrapFirstOrganization(String organizationName, String adminEmail, String adminPassword,
                                              String firstName, String lastName) {
        if (organizationRepository.count() > 0) {
            return false;
        }
        String email = UserAccountService.normalizeEmail(adminEmail);
        if (userRepository.existsByEmail(email)) {
            log.warn("Bootstrap skipped: an account with the bootstrap administrator email already exists");
            return false;
        }
        Organization organization = createOrganization(organizationName, null);
        User admin = userAccountService.createStaffUser(organization.getId(), email, firstName, lastName, adminPassword,
                Role.ADMIN, true);
        auditService.recordFor(organization.getId(), admin.getId(), admin.getEmail(), AuditActions.ORGANIZATION_CREATED,
                "ORGANIZATION", String.valueOf(organization.getId()), "Organisation created by the bootstrap runner");
        log.info("Bootstrap created organisation '{}' (id {}) with administrator {}", organization.getName(),
                organization.getId(), admin.getEmail());
        return true;
    }

    /** Whether any organisation exists (BootstrapRunner, after losing a first-start race). */
    @Transactional(readOnly = true)
    public boolean anyOrganizationExists() {
        return organizationRepository.count() > 0;
    }

    /** Creates an organisation with a unique slug derived from the name (or the preferred slug). */
    @Transactional
    public Organization createOrganization(String name, String preferredSlug) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Organisation name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 200) {
            throw new BadRequestException("Organisation name must be at most 200 characters");
        }
        Organization organization = new Organization();
        organization.setName(trimmed);
        organization.setSlug(uniqueSlug(preferredSlug == null || preferredSlug.isBlank() ? trimmed : preferredSlug));
        organization.setBlindReview(true);
        organization.setShareVerificationStatus(true);
        return organizationRepository.saveAndFlush(organization);
    }

    /** The caller's organisation entity (staff only). */
    @Transactional(readOnly = true)
    public Organization requireCurrentOrganization() {
        Long organizationId = securityUtils.requireOrganizationId();
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organisation not found"));
    }

    @Transactional(readOnly = true)
    public OrganizationResponse getCurrent() {
        return OrganizationResponse.from(requireCurrentOrganization());
    }

    @Transactional
    public OrganizationResponse updateCurrent(OrganizationUpdateRequest request) {
        Organization organization = requireCurrentOrganization();
        List<String> changes = new ArrayList<>();
        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new BadRequestException("Organisation name must not be blank");
            }
            if (!name.equals(organization.getName())) {
                changes.add("name '" + organization.getName() + "' -> '" + name + "'");
                organization.setName(name);
            }
        }
        if (request.blindReview() != null && request.blindReview() != organization.isBlindReview()) {
            changes.add("blindReview " + organization.isBlindReview() + " -> " + request.blindReview());
            organization.setBlindReview(request.blindReview());
        }
        if (request.shareVerificationStatus() != null
                && request.shareVerificationStatus() != organization.isShareVerificationStatus()) {
            changes.add("shareVerificationStatus " + organization.isShareVerificationStatus() + " -> "
                    + request.shareVerificationStatus());
            organization.setShareVerificationStatus(request.shareVerificationStatus());
        }
        if (request.requireStaffMfa() != null && request.requireStaffMfa() != organization.isRequireStaffMfa()) {
            if (request.requireStaffMfa() && !secretCipher.isConfigured()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "MFA_NOT_CONFIGURED: two-factor authentication is not available on this server");
            }
            changes.add("requireStaffMfa " + organization.isRequireStaffMfa() + " -> " + request.requireStaffMfa());
            organization.setRequireStaffMfa(request.requireStaffMfa());
        }
        if (request.ssoAllowedDomains() != null) {
            String domains = SsoDomains.format(request.ssoAllowedDomains());
            if (!Objects.equals(domains, organization.getSsoAllowedDomains())) {
                changes.add("ssoAllowedDomains '" + (organization.getSsoAllowedDomains() == null ? "" : organization.getSsoAllowedDomains())
                        + "' -> '" + (domains == null ? "" : domains) + "'");
                organization.setSsoAllowedDomains(domains);
            }
        }
        Organization saved = organizationRepository.save(organization);
        if (!changes.isEmpty()) {
            auditService.record(AuditActions.ORGANIZATION_UPDATED, "ORGANIZATION", String.valueOf(saved.getId()),
                    String.join("; ", changes));
        }
        return OrganizationResponse.from(saved);
    }

    /** Lower-case ASCII slug: letters and digits separated by single hyphens. */
    public static String slugify(String value) {
        if (value == null) {
            return "";
        }
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > MAX_SLUG_LENGTH - 6) {
            slug = slug.substring(0, MAX_SLUG_LENGTH - 6).replaceAll("-+$", "");
        }
        return slug;
    }

    private String uniqueSlug(String source) {
        String base = slugify(source);
        if (base.isEmpty()) {
            base = "organisation";
        }
        String candidate = base;
        int suffix = 2;
        while (organizationRepository.existsBySlug(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }
}
