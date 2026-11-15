package com.recruitpro.verification.dto;

/** POST /sources/{id}/ownership-check: PROFILE_BIO_CODE (GitHub, GitLab), DNS_TXT, WELL_KNOWN_FILE or PAGE_CODE (website). */
public record OwnershipCheckRequest(String method) {
}
