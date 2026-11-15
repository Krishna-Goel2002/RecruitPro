package com.recruitpro.verification.dto;

/** A claim on the public referee page; {@code id} is {@code fact:<n>} or {@code claim:<n>}. */
public record PublicReferenceClaim(String id, String claimType, String value) {
}
