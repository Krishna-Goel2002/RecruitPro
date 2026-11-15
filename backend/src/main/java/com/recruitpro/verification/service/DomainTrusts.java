package com.recruitpro.verification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.common.util.JsonText;
import com.recruitpro.verification.dto.DomainTrustView;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.VerdictValue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Domain trust of verdicts (V26, trust fixes of 16 Nov 2026). The agent checks a reference's employer domain and a
 * badge's issuer domain and stores the result per verdict ({@code claim_verdicts.domain_trust_json}); a verdict that
 * was VERIFIED but whose domain the agent did not trust comes back PARTLY_VERIFIED with {@code cappedFrom = VERIFIED}.
 * Verdicts are shared with every organisation, so an organisation's own trusted lists are applied here, when that
 * organisation looks at a verdict: for that organisation (and only that one) such a verdict counts as VERIFIED.
 */
public final class DomainTrusts {

    public static final String EMPLOYER_DOMAIN = "EMPLOYER_DOMAIN";
    public static final String BADGE_ISSUER = "BADGE_ISSUER";
    public static final String TRUSTED = "TRUSTED";
    public static final String AGENT = "AGENT";
    public static final String ORGANIZATION = "ORGANIZATION";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DomainTrusts() {
    }

    /** The stored trust object, or null. */
    public static JsonNode read(ClaimVerdict verdict) {
        JsonNode node = verdict == null ? null : JsonText.read(MAPPER, verdict.getDomainTrustJson());
        return node != null && node.isObject() ? node : null;
    }

    /** Lower-case host without scheme, path, port, trailing dot or leading {@code www.}; "" when unusable. */
    public static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.contains("://")) {
            try {
                String host = URI.create(text).getHost();
                text = host == null ? "" : host;
            } catch (IllegalArgumentException ex) {
                return "";
            }
        }
        int slash = text.indexOf('/');
        if (slash >= 0) {
            text = text.substring(0, slash);
        }
        int colon = text.indexOf(':');
        if (colon >= 0) {
            text = text.substring(0, colon);
        }
        while (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.startsWith("www.") ? text.substring(4) : text;
    }

    /** {@code domain} equals {@code parent} or is a subdomain of it. */
    public static boolean within(String domain, String parent) {
        String child = normalise(domain);
        String root = normalise(parent);
        return !child.isEmpty() && !root.isEmpty() && (child.equals(root) || child.endsWith("." + root));
    }

    /** The organisation lists the domain of this trust object for its check. */
    static boolean listedByOrganization(JsonNode trust, VerificationSettingsService.Effective settings) {
        if (trust == null || settings == null) {
            return false;
        }
        return settings.trustsDomain(text(trust, "check"), text(trust, "domain"));
    }

    /**
     * The verdict as it counts for an organisation: the stored verdict, except that a PARTLY_VERIFIED verdict capped
     * only by domain trust ({@code cappedFrom = VERIFIED}) is VERIFIED when the organisation lists the domain.
     */
    public static VerdictValue effectiveVerdict(ClaimVerdict verdict, VerificationSettingsService.Effective settings) {
        if (verdict == null) {
            return null;
        }
        if (verdict.getVerdict() != VerdictValue.PARTLY_VERIFIED) {
            return verdict.getVerdict();
        }
        JsonNode trust = read(verdict);
        if (trust != null && VerdictValue.VERIFIED.name().equals(text(trust, "cappedFrom"))
                && listedByOrganization(trust, settings)) {
            return VerdictValue.VERIFIED;
        }
        return verdict.getVerdict();
    }

    /** Staff view of the verdict's domain trust for an organisation; null when no domain check applied. */
    public static DomainTrustView view(ClaimVerdict verdict, VerificationSettingsService.Effective settings, boolean revealed) {
        JsonNode trust = read(verdict);
        if (trust == null) {
            return null;
        }
        String agentStatus = text(trust, "status");
        String agentBy = text(trust, "trustedBy");
        boolean listed = listedByOrganization(trust, settings);
        boolean agentTrusted = TRUSTED.equals(agentStatus);
        String status = agentTrusted || listed ? TRUSTED : agentStatus;
        String trustedBy = agentTrusted ? (agentBy == null ? AGENT : agentBy) : listed ? ORGANIZATION : null;
        List<String> reasons = new ArrayList<>();
        JsonNode stored = trust.get("reasons");
        if (stored != null && stored.isArray()) {
            for (JsonNode reason : stored) {
                if (reason.isTextual() && reasons.size() < 20) {
                    reasons.add(reason.asText());
                }
            }
        }
        if (listed && !agentTrusted) {
            reasons.add("Your organisation lists this domain as trusted.");
        }
        boolean lifted = verdict.getVerdict() == VerdictValue.PARTLY_VERIFIED
                && effectiveVerdict(verdict, settings) == VerdictValue.VERIFIED;
        JsonNode controlled = trust.get("candidateControlled");
        return new DomainTrustView(text(trust, "check"), revealed ? text(trust, "domain") : null, status, trustedBy,
                agentStatus, reasons, controlled != null && controlled.asBoolean(false), lifted);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
