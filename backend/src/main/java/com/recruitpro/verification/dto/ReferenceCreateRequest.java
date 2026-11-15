package com.recruitpro.verification.dto;

import java.util.List;

/** POST /references. {@code consent} must be true; at most 20 claims; at most 5 open requests per candidate. */
public record ReferenceCreateRequest(String refereeName, String refereeEmail, String relationship, String employerName,
                                     String employerDomain, List<SubjectRefRequest> claims, Boolean consent) {
}
