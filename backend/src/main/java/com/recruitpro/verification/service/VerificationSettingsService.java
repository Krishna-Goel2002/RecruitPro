package com.recruitpro.verification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.service.MatchTriggerService;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.repository.UserRepository;
import com.recruitpro.verification.dto.VerificationSettingsRequest;
import com.recruitpro.verification.dto.VerificationSettingsResponse;
import com.recruitpro.verification.entity.OrganizationVerificationSettings;
import com.recruitpro.verification.entity.VerificationMode;
import com.recruitpro.verification.repository.ClaimVerdictReviewRepository;
import com.recruitpro.verification.repository.OrganizationVerificationSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-organisation verification settings (principle 9): mode SHADOW (default) or VISIBLE, and which connectors'
 * verdicts the organisation uses (GITHUB, GITLAB, WEBSITE, DOCUMENT, OPEN_BADGE, REFERENCE; all on by default).
 * A disabled connector's verdicts are left out of the staff view, the shadow queue and matching for that
 * organisation. Candidates connect sources independently of any organisation.
 */
@Service
public class VerificationSettingsService {

    public static final String REFERENCE_CONNECTOR = "REFERENCE";
    public static final List<String> CONNECTORS = List.of("GITHUB", "GITLAB", "WEBSITE", "DOCUMENT", "OPEN_BADGE",
            REFERENCE_CONNECTOR);

    private final OrganizationVerificationSettingsRepository repository;
    private final ClaimVerdictReviewRepository reviewRepository;
    private final SecurityUtils securityUtils;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final MatchTriggerService matchTriggerService;
    private final VerificationViews views;

    public VerificationSettingsService(OrganizationVerificationSettingsRepository repository,
                                       ClaimVerdictReviewRepository reviewRepository, SecurityUtils securityUtils,
                                       UserRepository userRepository, AuditService auditService,
                                       MatchTriggerService matchTriggerService, VerificationViews views) {
        this.repository = repository;
        this.reviewRepository = reviewRepository;
        this.securityUtils = securityUtils;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.matchTriggerService = matchTriggerService;
        this.views = views;
    }

    /** Effective settings of an organisation (defaults when it has no row). */
    public record Effective(VerificationMode mode, Map<String, Boolean> connectors) {
        public boolean visible() {
            return mode == VerificationMode.VISIBLE;
        }

        public boolean enabled(String connector) {
            return connector != null && Boolean.TRUE.equals(connectors.get(connector));
        }
    }

    @Transactional(readOnly = true)
    public Effective effective(Long organizationId) {
        OrganizationVerificationSettings settings = organizationId == null ? null : repository.findById(organizationId).orElse(null);
        return new Effective(settings == null ? VerificationMode.SHADOW : settings.getMode(),
                connectors(settings == null ? null : settings.getConnectorsJson()));
    }

    /** GET /api/v1/admin/verification/settings (ADMIN). */
    @Transactional(readOnly = true)
    public VerificationSettingsResponse getForCurrentOrganization() {
        CurrentUser caller = securityUtils.requireOrganizationMember();
        return response(repository.findById(caller.organizationId()).orElse(null));
    }

    /**
     * PUT /api/v1/admin/verification/settings (ADMIN). Audited. Changing the mode re-matches the organisation's
     * applications of candidates whose verdicts it reviewed (confirmations count only in VISIBLE mode).
     */
    @Transactional
    public VerificationSettingsResponse update(VerificationSettingsRequest request) {
        CurrentUser caller = securityUtils.requireOrganizationMember();
        if (request == null || request.mode() == null || request.mode().isBlank()) {
            throw new BadRequestException("mode is required (SHADOW or VISIBLE)");
        }
        VerificationMode mode;
        try {
            mode = VerificationMode.valueOf(request.mode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("mode must be SHADOW or VISIBLE");
        }
        OrganizationVerificationSettings settings = repository.findById(caller.organizationId()).orElse(null);
        VerificationMode previousMode = settings == null ? VerificationMode.SHADOW : settings.getMode();
        Map<String, Boolean> connectors = connectors(settings == null ? null : settings.getConnectorsJson());
        Map<String, Boolean> previousConnectors = new LinkedHashMap<>(connectors);
        if (request.connectors() != null) {
            for (Map.Entry<String, Boolean> entry : request.connectors().entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().trim().toUpperCase(Locale.ROOT);
                if (!CONNECTORS.contains(key)) {
                    throw new BadRequestException("Unknown connector '" + entry.getKey() + "'");
                }
                if (entry.getValue() == null) {
                    throw new BadRequestException("Connector '" + key + "' must be true or false");
                }
                connectors.put(key, entry.getValue());
            }
        }
        if (settings == null) {
            settings = new OrganizationVerificationSettings();
            settings.setOrganizationId(caller.organizationId());
        }
        settings.setMode(mode);
        settings.setConnectorsJson(views.write(connectors));
        settings.setUpdatedBy(caller.userId());
        settings.setUpdatedAt(UtcTime.now());
        OrganizationVerificationSettings saved = repository.save(settings);
        auditService.record(VerificationAuditActions.SETTINGS_CHANGED, "ORGANIZATION", String.valueOf(caller.organizationId()),
                "Verification mode " + previousMode + " -> " + mode + "; connectors " + previousConnectors + " -> " + connectors);
        if (previousMode != mode || !previousConnectors.equals(connectors)) {
            for (Long candidateId : reviewRepository.findReviewedCandidateIds(caller.organizationId())) {
                matchTriggerService.requestMatchForCandidateInOrganization(candidateId, caller.organizationId());
            }
        }
        return response(saved);
    }

    private VerificationSettingsResponse response(OrganizationVerificationSettings settings) {
        if (settings == null) {
            return new VerificationSettingsResponse(VerificationMode.SHADOW.name(), connectors(null), null, null);
        }
        String updatedByName = settings.getUpdatedBy() == null ? null : userRepository.findById(settings.getUpdatedBy())
                .map(user -> (user.getFirstName() + " " + user.getLastName()).trim()).orElse(null);
        return new VerificationSettingsResponse(settings.getMode().name(), connectors(settings.getConnectorsJson()),
                settings.getUpdatedAt(), updatedByName);
    }

    /** Stored connectors over the defaults (every connector enabled). */
    Map<String, Boolean> connectors(String json) {
        Map<String, Boolean> connectors = new LinkedHashMap<>();
        for (String connector : CONNECTORS) {
            connectors.put(connector, Boolean.TRUE);
        }
        JsonNode stored = views.json(json);
        if (stored != null && stored.isObject()) {
            for (String connector : CONNECTORS) {
                JsonNode value = stored.get(connector);
                if (value != null && value.isBoolean()) {
                    connectors.put(connector, value.asBoolean());
                }
            }
        }
        return connectors;
    }
}
