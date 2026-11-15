package com.recruitpro.verification.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Request and response shapes of the agent service (plan v2 section 4; {@code agent-service/app/models.py}).
 * JSON is camelCase; instants are ISO-8601 strings with {@code Z}. Null request fields are omitted so the agent's
 * defaults apply.
 */
public final class AgentModels {

    private AgentModels() {
    }

    // ------------------------------------------------------------------------------------------- requests

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Claim(String id, String type, String value) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OwnershipCheckRequest(String method, String target, String code) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CodeLimits(Integer maxRepos, Integer maxRequests) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CodeVerifyRequest(String provider, String login, String accessToken, boolean ownershipProven,
                                    List<Claim> claims, CodeLimits limits) {
        @Override
        public String toString() {
            // Never log the access token.
            return "CodeVerifyRequest[provider=" + provider + ", login=" + login + ", claims=" + (claims == null ? 0 : claims.size()) + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WebVerifyRequest(String url, boolean ownershipProven, List<Claim> claims, String holderName) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocumentVerifyRequest(List<Claim> claims, String holderName) {
    }

    /** {@code credential} is a JWT string or a JSON object (pasted credential); {@code url} a hosted assertion. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BadgeVerifyRequest(Object credential, String url, List<Claim> claims, String holderName,
                                     String holderEmail) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Referee(String relationship, String emailDomain, String employerDomain) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReferenceAnswer(String claimId, String answer, String comment) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReferenceVerifyRequest(List<Claim> claims, Referee referee, List<ReferenceAnswer> answers,
                                         String holderName) {
    }

    // ------------------------------------------------------------------------------------------ responses

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvidenceItem(String key, String kind, String url, String title, String retrievedAt,
                               String contentHash, String excerpt, String snapshotText, JsonNode attributes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Reason(String text, List<String> evidenceKeys, String quote) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record IntegrityNote(String code, String text, List<String> evidenceKeys) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Verdict(String claimId, String claimType, String claimValue, String verdict, boolean ownershipProven,
                          List<Reason> reasons, List<IntegrityNote> integrityNotes, String probeQuestion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerifyResponse(String verifier, String verifierVersion, List<EvidenceItem> evidence,
                                 List<Verdict> verdicts, List<Reason> summary, JsonNode skills, JsonNode usage,
                                 List<String> warnings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OwnershipCheckResponse(boolean proven, String method, String detail, EvidenceItem evidence) {
    }
}
