package com.recruitpro.support;

import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.verification.client.AgentModels;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.OrganizationVerificationSettings;
import com.recruitpro.verification.entity.OwnershipMethod;
import com.recruitpro.verification.entity.OwnershipStatus;
import com.recruitpro.verification.entity.SubjectType;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationMode;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.OrganizationVerificationSettingsRepository;
import com.recruitpro.verification.repository.VerificationSourceRepository;
import com.recruitpro.verification.service.VerificationResultWriter;
import org.springframework.stereotype.Component;

import java.util.List;

/** Test fixtures for the verification package (test classpath only). */
@Component
public class VerificationFixtures {

    /** Base64 of 32 fixed bytes; tests only. */
    public static final String TEST_ENCRYPTION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final VerificationSourceRepository sources;
    private final ClaimVerdictRepository verdicts;
    private final OrganizationVerificationSettingsRepository settings;
    private final CandidateClaimRepository claims;

    public VerificationFixtures(VerificationSourceRepository sources, ClaimVerdictRepository verdicts,
                                OrganizationVerificationSettingsRepository settings, CandidateClaimRepository claims) {
        this.sources = sources;
        this.verdicts = verdicts;
        this.settings = settings;
        this.claims = claims;
    }

    public VerificationSource source(CandidateProfile candidate, VerificationSourceKind kind, String identifier, boolean proven) {
        VerificationSource source = new VerificationSource();
        source.setCandidateId(candidate.getId());
        source.setKind(kind);
        source.setIdentifier(identifier);
        source.setDisplayLabel(kind + ": " + identifier);
        source.setOwnershipStatus(proven ? OwnershipStatus.PROVEN : OwnershipStatus.UNPROVEN);
        source.setOwnershipMethod(proven ? OwnershipMethod.PROFILE_BIO_CODE : null);
        source.setChallengeCode("rp-test-challenge-code-0001");
        source.setConsentTextVersion("test");
        source.setConsentedAt(UtcTime.now());
        return sources.save(source);
    }

    public CandidateClaim claim(CandidateProfile candidate, ClaimType type, String value) {
        CandidateClaim claim = new CandidateClaim();
        claim.setCandidate(candidate);
        claim.setClaimType(type);
        claim.setClaimValue(value);
        claim.setTier(EvidenceTier.SELF_CLAIMED);
        return claims.save(claim);
    }

    /** A current verdict on a candidate claim from a source. */
    public ClaimVerdict verdict(VerificationSource source, CandidateClaim claim, VerdictValue value, boolean ownershipProven,
                                CandidateVerdictStatus status) {
        ClaimVerdict verdict = new ClaimVerdict();
        verdict.setCandidateId(source.getCandidateId());
        verdict.setSubjectType(SubjectType.CANDIDATE_CLAIM);
        verdict.setSubjectId(claim.getId());
        verdict.setClaimType(claim.getClaimType());
        verdict.setClaimValue(claim.getClaimValue());
        verdict.setSourceId(source.getId());
        verdict.setVerifier("code");
        verdict.setVerifierVersion("code-1.0");
        verdict.setVerdict(value);
        verdict.setOwnershipProven(ownershipProven);
        verdict.setReasonsJson("[{\"text\":\"Repository A uses it.\",\"evidenceKeys\":[\"repo-1\"],\"quote\":null}]");
        verdict.setIntegrityNotesJson("[]");
        verdict.setEvidenceItemIdsJson("[]");
        verdict.setCandidateStatus(status);
        verdict.setCurrent(true);
        return verdicts.save(verdict);
    }

    public void mode(Long organizationId, VerificationMode mode) {
        OrganizationVerificationSettings row = settings.findById(organizationId).orElseGet(OrganizationVerificationSettings::new);
        row.setOrganizationId(organizationId);
        row.setMode(mode);
        row.setUpdatedAt(UtcTime.now());
        settings.save(row);
    }

    /** An agent evidence item whose hash matches its snapshot. */
    public static AgentModels.EvidenceItem item(String key, String kind, String url, String snapshot) {
        return new AgentModels.EvidenceItem(key, kind, url, key + " title", "2026-11-15T10:00:00.000Z",
                VerificationResultWriter.sha256(snapshot), snapshot.length() > 100 ? snapshot.substring(0, 100) : snapshot,
                snapshot, null);
    }

    public static AgentModels.Verdict agentVerdict(String claimId, String type, String value, String verdict,
                                                   boolean ownershipProven, String... keys) {
        return new AgentModels.Verdict(claimId, type, value, verdict, ownershipProven,
                List.of(new AgentModels.Reason("Repository A shows it.", List.of(keys), null)),
                List.of(), "PARTLY_VERIFIED".equals(verdict) ? "Tell us about it." : null);
    }

    public static AgentModels.VerifyResponse response(String verifier, List<AgentModels.EvidenceItem> evidence,
                                                      List<AgentModels.Verdict> verdicts) {
        return new AgentModels.VerifyResponse(verifier, verifier + "-1.0", evidence, verdicts, List.of(), null, null, List.of());
    }
}
