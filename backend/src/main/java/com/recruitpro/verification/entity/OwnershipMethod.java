package com.recruitpro.verification.entity;

/** How ownership of a source was proven. */
public enum OwnershipMethod {
    OAUTH, DNS_TXT, WELL_KNOWN_FILE, PAGE_CODE, PROFILE_BIO_CODE, ISSUER_SIGNATURE
}
