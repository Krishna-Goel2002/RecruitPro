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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-organisation verification settings (principle 9): mode SHADOW (default) or VISIBLE, and which connectors'
 * verdicts the organisation uses (GITHUB, GITLAB, WEBSITE, DOCUMENT, OPEN_BADGE, REFERENCE; all on by default).
 * A disabled connector's verdicts are left out of the staff view, the shadow queue and matching for that
 * organisation. Candidates connect sources independently of any organisation.
 * <p>
 * Trusted domains (V26): employer domains the organisation trusts for references and Open Badge issuer domains it
 * trusts. A verdict that the agent capped at PARTLY_VERIFIED only because it could not establish that domain counts
 * as VERIFIED for this organisation when the domain (or a parent domain) is listed ({@link DomainTrusts}).
 */
@Service
public class VerificationSettingsService {

    public static final String REFERENCE_CONNECTOR = "REFERENCE";
    public static final List<String> CONNECTORS = List.of("GITHUB", "GITLAB", "WEBSITE", "DOCUMENT", "OPEN_BADGE",
            REFERENCE_CONNECTOR);
    public static final int MAX_TRUSTED_DOMAINS = 200;
    /** Second-level labels of registry suffixes (co.uk, com.au, ...): never accepted as a trusted domain. */
    private static final Set<String> PUBLIC_SECOND_LEVEL = Set.of("co", "com", "net", "org", "ac", "gov", "edu", "ltd",
            "plc", "nic", "gen", "mil", "sch", "or", "ne", "go");

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
    public record Effective(VerificationMode mode, Map<String, Boolean> connectors, List<String> trustedEmployerDomains,
                            List<String> trustedBadgeIssuers) {

        public Effective(VerificationMode mode, Map<String, Boolean> connectors) {
            this(mode, connectors, List.of(), List.of());
        }

        public boolean visible() {
            return mode == VerificationMode.VISIBLE;
        }

        public boolean enabled(String connector) {
            return connector != null && Boolean.TRUE.equals(connectors.get(connector));
        }

        /** The organisation lists {@code domain} (or a parent of it) for the check EMPLOYER_DOMAIN or BADGE_ISSUER. */
        public boolean trustsDomain(String check, String domain) {
            List<String> list = DomainTrusts.EMPLOYER_DOMAIN.equals(check) ? trustedEmployerDomains
                    : DomainTrusts.BADGE_ISSUER.equals(check) ? trustedBadgeIssuers : null;
            if (list == null || domain == null) {
                return false;
            }
            for (String entry : list) {
                if (DomainTrusts.within(domain, entry)) {
                    return true;
                }
            }
            return false;
        }
    }

    @Transactional(readOnly = true)
    public Effective effective(Long organizationId) {
        OrganizationVerificationSettings settings = organizationId == null ? null : repository.findById(organizationId).orElse(null);
        return new Effective(settings == null ? VerificationMode.SHADOW : settings.getMode(),
                connectors(settings == null ? null : settings.getConnectorsJson()),
                domains(settings == null ? null : settings.getTrustedEmployerDomainsJson()),
                domains(settings == null ? null : settings.getTrustedBadgeIssuersJson()));
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
        List<String> previousEmployers = domains(settings == null ? null : settings.getTrustedEmployerDomainsJson());
        List<String> previousIssuers = domains(settings == null ? null : settings.getTrustedBadgeIssuersJson());
        List<String> employers = request.trustedEmployerDomains() == null ? previousEmployers
                : validDomains("trustedEmployerDomains", request.trustedEmployerDomains());
        List<String> issuers = request.trustedBadgeIssuers() == null ? previousIssuers
                : validDomains("trustedBadgeIssuers", request.trustedBadgeIssuers());
        if (settings == null) {
            settings = new OrganizationVerificationSettings();
            settings.setOrganizationId(caller.organizationId());
        }
        settings.setMode(mode);
        settings.setConnectorsJson(views.write(connectors));
        settings.setTrustedEmployerDomainsJson(views.write(employers));
        settings.setTrustedBadgeIssuersJson(views.write(issuers));
        settings.setUpdatedBy(caller.userId());
        settings.setUpdatedAt(UtcTime.now());
        OrganizationVerificationSettings saved = repository.save(settings);
        auditService.record(VerificationAuditActions.SETTINGS_CHANGED, "ORGANIZATION", String.valueOf(caller.organizationId()),
                "Verification mode " + previousMode + " -> " + mode + "; connectors " + previousConnectors + " -> " + connectors
                        + "; trusted employer domains " + previousEmployers + " -> " + employers
                        + "; trusted badge issuers " + previousIssuers + " -> " + issuers);
        if (previousMode != mode || !previousConnectors.equals(connectors) || !previousEmployers.equals(employers)
                || !previousIssuers.equals(issuers)) {
            for (Long candidateId : reviewRepository.findReviewedCandidateIds(caller.organizationId())) {
                matchTriggerService.requestMatchForCandidateInOrganization(candidateId, caller.organizationId());
            }
        }
        return response(saved);
    }

    private VerificationSettingsResponse response(OrganizationVerificationSettings settings) {
        if (settings == null) {
            return new VerificationSettingsResponse(VerificationMode.SHADOW.name(), connectors(null), null, null,
                    List.of(), List.of());
        }
        String updatedByName = settings.getUpdatedBy() == null ? null : userRepository.findById(settings.getUpdatedBy())
                .map(user -> (user.getFirstName() + " " + user.getLastName()).trim()).orElse(null);
        return new VerificationSettingsResponse(settings.getMode().name(), connectors(settings.getConnectorsJson()),
                settings.getUpdatedAt(), updatedByName, domains(settings.getTrustedEmployerDomainsJson()),
                domains(settings.getTrustedBadgeIssuersJson()));
    }

    /** A stored JSON array of domains; unusable entries are skipped (never throws). */
    List<String> domains(String json) {
        List<String> domains = new ArrayList<>();
        JsonNode stored = views.json(json);
        if (stored != null && stored.isArray()) {
            for (JsonNode value : stored) {
                String domain = value.isTextual() ? DomainTrusts.normalise(value.asText()) : "";
                if (!domain.isEmpty() && !domains.contains(domain) && domains.size() < MAX_TRUSTED_DOMAINS) {
                    domains.add(domain);
                }
            }
        }
        return domains;
    }

    /** Normalised, de-duplicated domains of a settings request; 400 for anything that is not a registrable domain. */
    static List<String> validDomains(String field, List<String> values) {
        Set<String> domains = new LinkedHashSet<>();
        for (String value : values) {
            String domain = DomainTrusts.normalise(value);
            String[] labels = domain.split("\\.");
            if (domain.isEmpty() || !ReferenceService.DOMAIN.matcher(domain).matches()
                    || (labels.length == 2 && PUBLIC_SECOND_LEVEL.contains(labels[0]))) {
                throw new BadRequestException(field + ": '" + value + "' is not a domain name such as example.com");
            }
            domains.add(domain);
        }
        if (domains.size() > MAX_TRUSTED_DOMAINS) {
            throw new BadRequestException(field + " can hold at most " + MAX_TRUSTED_DOMAINS + " domains");
        }
        return new ArrayList<>(domains);
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
