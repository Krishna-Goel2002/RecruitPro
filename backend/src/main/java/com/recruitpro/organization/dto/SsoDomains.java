package com.recruitpro.organization.dto;

import com.recruitpro.common.exception.BadRequestException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The organisation's SSO e-mail domains, stored comma-separated in {@code organizations.sso_allowed_domains}
 * (max 500 characters). Exact domains only: no wildcards, no sub-domain matching.
 */
public final class SsoDomains {

    public static final int MAX_DOMAINS = 20;
    public static final int MAX_STORED_LENGTH = 500;
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

    private SsoDomains() {
    }

    /** Stored text to a list of lower-case domains (unknown entries are dropped). */
    public static List<String> parse(String stored) {
        List<String> domains = new ArrayList<>();
        if (stored == null || stored.isBlank()) {
            return domains;
        }
        for (String part : stored.split(",")) {
            String domain = part.trim().toLowerCase(Locale.ROOT);
            if (!domain.isEmpty() && DOMAIN.matcher(domain).matches() && !domains.contains(domain)) {
                domains.add(domain);
            }
        }
        return domains;
    }

    /** Validates and normalises a list from the settings endpoint; null for an empty list (SSO off). */
    public static String format(List<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return null;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : domains) {
            String domain = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (domain.startsWith("@")) {
                domain = domain.substring(1);
            }
            if (domain.isEmpty()) {
                continue;
            }
            if (!DOMAIN.matcher(domain).matches()) {
                throw new BadRequestException("'" + (domain.length() > 80 ? domain.substring(0, 80) : domain)
                        + "' is not a valid e-mail domain (use e.g. example.com, no wildcards)");
            }
            normalized.add(domain);
        }
        if (normalized.size() > MAX_DOMAINS) {
            throw new BadRequestException("At most " + MAX_DOMAINS + " SSO domains are allowed");
        }
        String joined = String.join(",", normalized);
        if (joined.length() > MAX_STORED_LENGTH) {
            throw new BadRequestException("The SSO domain list is too long (at most " + MAX_STORED_LENGTH + " characters)");
        }
        return joined.isEmpty() ? null : joined;
    }

    /** Whether the e-mail address's domain is exactly one of the stored domains. */
    public static boolean allows(String stored, String email) {
        if (email == null) {
            return false;
        }
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return false;
        }
        String domain = email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        return parse(stored).contains(domain);
    }
}
