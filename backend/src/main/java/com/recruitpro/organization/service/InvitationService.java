package com.recruitpro.organization.service;

import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.auth.mfa.MfaService;
import com.recruitpro.auth.mfa.dto.MfaChallengeResponse;
import com.recruitpro.auth.service.AuthService;
import com.recruitpro.auth.service.AuthSession;
import com.recruitpro.auth.service.TokenHashing;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.DuplicateResourceException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.common.web.PortalUrls;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.service.NotificationRecipient;
import com.recruitpro.notification.service.NotificationService;
import com.recruitpro.organization.dto.AcceptInvitationRequest;
import com.recruitpro.organization.dto.InvitationCreateRequest;
import com.recruitpro.organization.dto.InvitationPreviewResponse;
import com.recruitpro.organization.dto.InvitationResponse;
import com.recruitpro.organization.entity.Invitation;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.InvitationRepository;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import com.recruitpro.user.service.UserAccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Staff invitations (D3): an organisation ADMIN invites an email address with a staff role; the emailed
 * link ({@code ${app.frontend.hr-url}/accept-invite?token=...}) is valid for {@code app.invitations.ttl-days}.
 */
@Service
public class InvitationService {

    static final Set<Role> INVITABLE_ROLES = EnumSet.of(Role.ADMIN, Role.HR_MANAGER, Role.RECRUITER, Role.INTERVIEWER);
    static final String INVITATION_NOT_FOUND = "Invitation not found or no longer valid";

    private final InvitationRepository invitationRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final UserAccountService userAccountService;
    private final AuthService authService;
    private final MfaService mfaService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final SecurityUtils securityUtils;
    private final long ttlDays;
    private final boolean exposeLink;
    private final String hrPortalUrl;

    public InvitationService(InvitationRepository invitationRepository, OrganizationRepository organizationRepository,
                             UserRepository userRepository, UserAccountService userAccountService,
                             AuthService authService, MfaService mfaService, NotificationService notificationService,
                             AuditService auditService, SecurityUtils securityUtils,
                             @Value("${app.invitations.ttl-days:7}") long ttlDays,
                             @Value("${app.invitations.expose-link:false}") boolean exposeLink,
                             @Value("${app.frontend.hr-url:http://localhost:5174}") String hrPortalUrl) {
        this.invitationRepository = invitationRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.userAccountService = userAccountService;
        this.authService = authService;
        this.mfaService = mfaService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.securityUtils = securityUtils;
        this.ttlDays = Math.max(1, ttlDays);
        this.exposeLink = exposeLink;
        this.hrPortalUrl = PortalUrls.base(hrPortalUrl);
    }

    @Transactional
    public InvitationResponse create(InvitationCreateRequest request) {
        CurrentUser inviter = securityUtils.requireOrganizationMember();
        if (request.role() == null || !INVITABLE_ROLES.contains(request.role())) {
            throw new BadRequestException("Invitations are limited to staff roles: ADMIN, HR_MANAGER, RECRUITER, INTERVIEWER");
        }
        String email = UserAccountService.normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("An account with this email address already exists");
        }
        Organization organization = organizationRepository.findById(inviter.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organisation not found"));
        LocalDateTime now = UtcTime.now();
        for (Invitation outstanding : invitationRepository
                .findAllByOrganizationIdAndEmailAndAcceptedAtIsNullAndRevokedAtIsNull(organization.getId(), email)) {
            outstanding.setRevokedAt(now);
        }

        String rawToken = TokenHashing.newToken();
        Invitation invitation = new Invitation();
        invitation.setOrganizationId(organization.getId());
        invitation.setEmail(email);
        invitation.setRole(request.role());
        invitation.setTokenHash(TokenHashing.sha256(rawToken));
        invitation.setInvitedByUserId(inviter.userId());
        invitation.setExpiresAt(now.plusDays(ttlDays));
        Invitation saved = invitationRepository.save(invitation);

        String link = hrPortalUrl + "/accept-invite?token=" + rawToken;
        String inviterName = userRepository.findById(inviter.userId()).map(User::getFullName).orElse(inviter.email());
        Map<String, String> params = new HashMap<>();
        params.put("organizationName", organization.getName());
        params.put("inviterName", inviterName);
        params.put("role", request.role().name());
        params.put("link", link);
        params.put("expiresAt", UtcTime.format(saved.getExpiresAt()));
        notificationService.notify(NotificationRecipient.email(email, organization.getId()), NotificationTemplate.INVITATION, params);
        auditService.record(AuditActions.INVITATION_CREATED, "INVITATION", String.valueOf(saved.getId()),
                "Invited " + email + " as " + request.role());
        return toResponse(saved, inviterName, exposeLink ? link : null, now);
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> listForCurrentOrganization() {
        Long organizationId = securityUtils.requireOrganizationId();
        List<Invitation> invitations = invitationRepository.findAllByOrganizationIdOrderByCreatedAtDescIdDesc(organizationId);
        Set<Long> inviterIds = invitations.stream().map(Invitation::getInvitedByUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = userRepository.findAllById(inviterIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName, (first, second) -> first));
        LocalDateTime now = UtcTime.now();
        return invitations.stream()
                .map(invitation -> toResponse(invitation, names.get(invitation.getInvitedByUserId()), null, now))
                .toList();
    }

    @Transactional
    public void revoke(Long invitationId) {
        Long organizationId = securityUtils.requireOrganizationId();
        Invitation invitation = invitationRepository.findByIdAndOrganizationId(invitationId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));
        if (invitation.getAcceptedAt() != null) {
            throw new BadRequestException("An accepted invitation cannot be revoked");
        }
        if (invitation.getRevokedAt() == null) {
            invitation.setRevokedAt(UtcTime.now());
            auditService.record(AuditActions.INVITATION_REVOKED, "INVITATION", String.valueOf(invitation.getId()),
                    "Revoked invitation for " + invitation.getEmail());
        }
    }

    /** GET /api/v1/auth/invitations/{token}: public preview of a pending invitation. */
    @Transactional(readOnly = true)
    public InvitationPreviewResponse preview(String rawToken) {
        Invitation invitation = findPending(rawToken, false);
        Organization organization = organizationRepository.findById(invitation.getOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException(INVITATION_NOT_FOUND));
        return new InvitationPreviewResponse(organization.getName(), invitation.getEmail(), invitation.getRole(),
                invitation.getExpiresAt());
    }

    /**
     * POST /api/v1/auth/accept-invite: creates the staff account and signs it in. When the organisation requires staff
     * MFA, the sign-in stops at the enrolment challenge exactly like {@code /auth/login} (no session, no cookie); the
     * client enrols with the returned {@code mfaToken}.
     */
    @Transactional
    public AuthSession accept(AcceptInvitationRequest request) {
        Invitation invitation = findPending(request.token(), true);
        if (!organizationRepository.existsById(invitation.getOrganizationId())) {
            throw new ResourceNotFoundException(INVITATION_NOT_FOUND);
        }
        User user = userAccountService.createStaffUser(invitation.getOrganizationId(), invitation.getEmail(),
                request.firstName(), request.lastName(), request.password(), invitation.getRole(), true);
        invitation.setAcceptedAt(UtcTime.now());
        invitationRepository.save(invitation);
        auditService.recordFor(invitation.getOrganizationId(), user.getId(), user.getEmail(),
                AuditActions.INVITATION_ACCEPTED, "INVITATION", String.valueOf(invitation.getId()),
                "Joined as " + invitation.getRole());
        Optional<MfaChallengeResponse> challenge = mfaService.challengeFor(user);
        if (challenge.isPresent()) {
            return AuthSession.challenge(challenge.get());
        }
        return authService.createSession(user);
    }

    private Invitation findPending(String rawToken, boolean forUpdate) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 256) {
            throw new ResourceNotFoundException(INVITATION_NOT_FOUND);
        }
        String hash = TokenHashing.sha256(rawToken.trim());
        Invitation invitation = (forUpdate ? invitationRepository.findByTokenHashForUpdate(hash)
                : invitationRepository.findByTokenHash(hash))
                .orElseThrow(() -> new ResourceNotFoundException(INVITATION_NOT_FOUND));
        if (!invitation.isPending(UtcTime.now())) {
            throw new ResourceNotFoundException(INVITATION_NOT_FOUND);
        }
        return invitation;
    }

    private InvitationResponse toResponse(Invitation invitation, String invitedByName, String inviteUrl, LocalDateTime now) {
        return new InvitationResponse(invitation.getId(), invitation.getEmail(), invitation.getRole(),
                invitation.statusAt(now), invitation.getExpiresAt(), invitation.getAcceptedAt(), invitation.getRevokedAt(),
                invitation.getCreatedAt(), invitedByName, inviteUrl);
    }
}
