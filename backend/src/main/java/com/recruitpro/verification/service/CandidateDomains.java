package com.recruitpro.verification.service;

import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Domains the candidate proved ownership of (hosts of PROVEN, non-revoked WEBSITE sources). The agent treats a badge
 * issuer or referee employer domain equal to, or under, one of them as candidate-controlled (untrusted).
 */
final class CandidateDomains {

    static final int MAX = 50;

    private CandidateDomains() {
    }

    static List<String> proven(Collection<VerificationSource> sources) {
        Set<String> domains = new LinkedHashSet<>();
        for (VerificationSource source : sources) {
            if (source.getKind() != VerificationSourceKind.WEBSITE || !source.isOwnershipProven() || source.isRevoked()) {
                continue;
            }
            String host = DomainTrusts.normalise(source.getIdentifier());
            if (host.contains(".") && host.length() <= 253) {
                domains.add(host);
            }
            if (domains.size() >= MAX) {
                break;
            }
        }
        return new ArrayList<>(domains);
    }
}
