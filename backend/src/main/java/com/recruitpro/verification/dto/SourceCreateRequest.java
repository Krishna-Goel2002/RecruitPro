package com.recruitpro.verification.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * POST /candidates/me/verification/sources. {@code kind}: GITHUB, GITLAB, WEBSITE or OPEN_BADGE (documents are
 * uploaded with POST /sources/documents). For OPEN_BADGE, {@code identifier} is the hosted assertion URL, or
 * {@code credential} holds a pasted credential (a JWT string or a JSON object). {@code consent} must be true.
 */
public record SourceCreateRequest(String kind, String identifier, JsonNode credential, Boolean consent) {
}
