package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Domain trust behind a verdict as staff of one organisation see it (V26): the employer domain of a reference
 * ({@code check = EMPLOYER_DOMAIN}) or a badge's issuer domain ({@code BADGE_ISSUER}).
 * <ul>
 *   <li>{@code status}: TRUSTED | WEAK | UNTRUSTED for this organisation (TRUSTED when the agent trusted it or the
 *       organisation lists the domain).</li>
 *   <li>{@code trustedBy}: AGENT (registration age, MX and home page, or the built-in issuer list), ORGANIZATION (this
 *       organisation's list), or null when not trusted.</li>
 *   <li>{@code agentStatus}: the agent's own result, before this organisation's list.</li>
 *   <li>{@code domain}: null until the application's identity is revealed (blind-first).</li>
 *   <li>{@code liftedByOrganization}: the verdict was PARTLY_VERIFIED only because of the domain and is VERIFIED for
 *       this organisation because it trusts the domain.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DomainTrustView(String check, String domain, String status, String trustedBy, String agentStatus,
                              List<String> reasons, boolean candidateControlled, boolean liftedByOrganization) {
}
