package com.recruitpro.verification.service;

import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.organization.bootstrap.DemoDataSeeder;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import com.recruitpro.verification.dto.VerdictReason;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.EvidenceItem;
import com.recruitpro.verification.entity.EvidenceItemKind;
import com.recruitpro.verification.entity.OrganizationVerificationSettings;
import com.recruitpro.verification.entity.OwnershipMethod;
import com.recruitpro.verification.entity.OwnershipStatus;
import com.recruitpro.verification.entity.RunRequester;
import com.recruitpro.verification.entity.SubjectType;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationMode;
import com.recruitpro.verification.entity.VerificationRun;
import com.recruitpro.verification.entity.VerificationRunStatus;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.EvidenceItemRepository;
import com.recruitpro.verification.repository.OrganizationVerificationSettingsRepository;
import com.recruitpro.verification.repository.VerificationRunRepository;
import com.recruitpro.verification.repository.VerificationSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demo verification data (app.seed.demo; never in prod): the demo organisation in VISIBLE mode, and for the demo
 * candidate three skill claims, a {@code demo-asha} GitHub source (ownership PROVEN by PROFILE_BIO_CODE), one completed
 * run with SYNTHETIC evidence (matching the agent service's demo fixtures) and SHARED verdicts. Written directly:
 * the agent service is never called at startup. Idempotent: nothing happens when the demo source exists.
 */
@Service
public class VerificationDemoSeeder {

    public static final String DEMO_LOGIN = "demo-asha";
    private static final Logger log = LoggerFactory.getLogger(VerificationDemoSeeder.class);

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final CandidateProfileRepository profileRepository;
    private final CandidateClaimRepository claimRepository;
    private final OrganizationVerificationSettingsRepository settingsRepository;
    private final VerificationSourceRepository sourceRepository;
    private final VerificationRunRepository runRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final ClaimVerdictRepository verdictRepository;
    private final VerificationStorage storage;
    private final VerificationViews views;

    public VerificationDemoSeeder(OrganizationRepository organizationRepository, UserRepository userRepository,
                                  CandidateProfileRepository profileRepository, CandidateClaimRepository claimRepository,
                                  OrganizationVerificationSettingsRepository settingsRepository,
                                  VerificationSourceRepository sourceRepository, VerificationRunRepository runRepository,
                                  EvidenceItemRepository evidenceRepository, ClaimVerdictRepository verdictRepository,
                                  VerificationStorage storage, VerificationViews views) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.claimRepository = claimRepository;
        this.settingsRepository = settingsRepository;
        this.sourceRepository = sourceRepository;
        this.runRepository = runRepository;
        this.evidenceRepository = evidenceRepository;
        this.verdictRepository = verdictRepository;
        this.storage = storage;
        this.views = views;
    }

    @Transactional
    public void seed() {
        Organization organization = organizationRepository.findBySlug(DemoDataSeeder.ORGANIZATION_SLUG).orElse(null);
        User user = userRepository.findByEmail(DemoDataSeeder.CANDIDATE_EMAIL).orElse(null);
        if (organization == null || user == null) {
            log.info("Verification demo data skipped: the demo organisation or candidate does not exist");
            return;
        }
        LocalDateTime now = UtcTime.now();
        if (settingsRepository.findById(organization.getId()).isEmpty()) {
            OrganizationVerificationSettings settings = new OrganizationVerificationSettings();
            settings.setOrganizationId(organization.getId());
            settings.setMode(VerificationMode.VISIBLE);
            settings.setUpdatedAt(now);
            settingsRepository.save(settings);
        }
        CandidateProfile profile = profileRepository.findByUserId(user.getId()).orElseGet(() -> {
            CandidateProfile created = new CandidateProfile();
            created.setUser(user);
            return profileRepository.save(created);
        });
        Long candidateId = profile.getId();
        boolean exists = sourceRepository.findAllByCandidateIdAndKindAndRevokedAtIsNull(candidateId, VerificationSourceKind.GITHUB)
                .stream().anyMatch(existing -> DEMO_LOGIN.equalsIgnoreCase(existing.getIdentifier()));
        if (exists) {
            return;
        }
        CandidateClaim react = claim(profile, "React");
        CandidateClaim typescript = claim(profile, "TypeScript");
        CandidateClaim python = claim(profile, "Python");

        VerificationSource source = new VerificationSource();
        source.setCandidateId(candidateId);
        source.setKind(VerificationSourceKind.GITHUB);
        source.setIdentifier(DEMO_LOGIN);
        source.setDisplayLabel("GitHub: " + DEMO_LOGIN + " (demo)");
        source.setOwnershipStatus(OwnershipStatus.PROVEN);
        source.setOwnershipMethod(OwnershipMethod.PROFILE_BIO_CODE);
        source.setOwnershipCheckedAt(now);
        source.setOwnershipDetail("SYNTHETIC demo data: the profile bio contained the challenge code");
        source.setChallengeCode(VerificationSourceService.newChallengeCode());
        source.setConsentTextVersion("demo");
        source.setConsentedAt(now);
        source = sourceRepository.save(source);

        VerificationRun run = new VerificationRun();
        run.setSourceId(source.getId());
        run.setCandidateId(candidateId);
        run.setStatus(VerificationRunStatus.COMPLETED);
        run.setRequestedBy(RunRequester.SYSTEM);
        run.setVerifier("code");
        run.setVerifierVersion("code-1.0");
        run.setStartedAt(now);
        run.setFinishedAt(now);
        run.setUsageJson("{\"requests\":0,\"llmCalls\":0,\"truncated\":false}");
        run.setSummaryJson("[]");
        run.setWarningsJson("[\"DEMO_DATA\"]");
        run = runRepository.save(run);

        Map<String, Long> ids = new HashMap<>();
        ids.put("profile", evidence(run, "profile", EvidenceItemKind.PROFILE, "https://github.com/" + DEMO_LOGIN,
                "GitHub profile (synthetic demo)", "SYNTHETIC DEMO PROFILE\nAccount created 2018-05-02. Bio: Synthetic demo account."));
        ids.put("repo-1", evidence(run, "repo-1", EvidenceItemKind.REPOSITORY, "https://github.com/" + DEMO_LOGIN + "/react-dashboard",
                "react-dashboard (synthetic demo)", "SYNTHETIC DEMO REPOSITORY react-dashboard\nLanguages: TypeScript 87%, CSS 9%, HTML 3%\n"
                        + "Dependencies: react, react-dom, redux, typescript, vite, jest\nAuthored share: 0.87; own commits 2025-01 to 2025-08"));
        ids.put("repo-2", evidence(run, "repo-2", EvidenceItemKind.REPOSITORY, "https://github.com/" + DEMO_LOGIN + "/ui-kit",
                "ui-kit (synthetic demo)", "SYNTHETIC DEMO REPOSITORY ui-kit\nLanguages: TypeScript 87%, SCSS 13%\n"
                        + "Dependencies: react (peer), typescript, sass\nAuthored share: 1.0; own commits 2024-03 to 2024-10"));
        ids.put("repo-3", evidence(run, "repo-3", EvidenceItemKind.REPOSITORY, "https://github.com/" + DEMO_LOGIN + "/data-pipeline",
                "data-pipeline (synthetic demo)", "SYNTHETIC DEMO REPOSITORY data-pipeline\nLanguages: Python 98%\n"
                        + "Dependencies: pandas, fastapi, sqlalchemy, pytest\nAuthored share: 1.0; own commits 2025-02 to 2025-03"));

        verdict(run, react, VerdictValue.VERIFIED, List.of(new VerdictReason(
                "Repository A and repository B depend on React; the account authored at least half of the commits in each, "
                        + "over more than 30 days.", List.of("repo-1", "repo-2"), null)), null, ids, now);
        verdict(run, typescript, VerdictValue.VERIFIED, List.of(new VerdictReason(
                "TypeScript is the main language of repository A and repository B, both mostly authored by the account.",
                List.of("repo-1", "repo-2"), null)), null, ids, now);
        verdict(run, python, VerdictValue.PARTLY_VERIFIED, List.of(new VerdictReason(
                "Python appears in one repository (repository C) with commits over about two months.",
                List.of("repo-3"), null)),
                "Tell us about a Python project you worked on: what did you build and which parts did you write yourself?", ids, now);
        log.info("Verification demo data ready: {} in VISIBLE mode, GitHub source {} with 3 shared verdicts (SYNTHETIC)",
                DemoDataSeeder.ORGANIZATION_NAME, DEMO_LOGIN);
    }

    private CandidateClaim claim(CandidateProfile profile, String value) {
        for (CandidateClaim existing : claimRepository.findAllByCandidateIdOrderByIdAsc(profile.getId())) {
            if (existing.getClaimType() == ClaimType.SKILL && value.equalsIgnoreCase(existing.getClaimValue())) {
                return existing;
            }
        }
        CandidateClaim claim = new CandidateClaim();
        claim.setCandidate(profile);
        claim.setClaimType(ClaimType.SKILL);
        claim.setClaimValue(value);
        claim.setTier(EvidenceTier.SELF_CLAIMED);
        return claimRepository.save(claim);
    }

    private Long evidence(VerificationRun run, String key, EvidenceItemKind kind, String url, String title, String snapshot) {
        String storageKey;
        try {
            storageKey = storage.store(snapshot.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException("Demo snapshot could not be stored", ex);
        }
        storage.deleteOnRollback(List.of(storageKey));
        EvidenceItem item = new EvidenceItem();
        item.setRunId(run.getId());
        item.setSourceId(run.getSourceId());
        item.setCandidateId(run.getCandidateId());
        item.setItemKey(key);
        item.setKind(kind);
        item.setUrl(url);
        item.setTitle(title);
        item.setRetrievedAt(run.getFinishedAt());
        item.setContentHash(VerificationResultWriter.sha256(snapshot));
        item.setSnapshotPath(storageKey);
        item.setExcerpt(snapshot.length() > 1000 ? snapshot.substring(0, 1000) : snapshot);
        item.setAttributesJson("{\"synthetic\":true}");
        return evidenceRepository.save(item).getId();
    }

    private void verdict(VerificationRun run, CandidateClaim claim, VerdictValue value, List<VerdictReason> reasons,
                         String probe, Map<String, Long> evidenceIds, LocalDateTime now) {
        List<Long> cited = new ArrayList<>();
        for (VerdictReason reason : reasons) {
            for (String key : reason.evidenceKeys()) {
                Long id = evidenceIds.get(key);
                if (id != null && !cited.contains(id)) {
                    cited.add(id);
                }
            }
        }
        ClaimVerdict verdict = new ClaimVerdict();
        verdict.setCandidateId(run.getCandidateId());
        verdict.setSubjectType(SubjectType.CANDIDATE_CLAIM);
        verdict.setSubjectId(claim.getId());
        verdict.setClaimType(claim.getClaimType());
        verdict.setClaimValue(claim.getClaimValue());
        verdict.setSourceId(run.getSourceId());
        verdict.setRunId(run.getId());
        verdict.setVerifier(run.getVerifier());
        verdict.setVerifierVersion(run.getVerifierVersion());
        verdict.setVerdict(value);
        verdict.setOwnershipProven(true);
        verdict.setReasonsJson(views.write(reasons));
        verdict.setIntegrityNotesJson("[]");
        verdict.setEvidenceItemIdsJson(views.write(cited));
        verdict.setProbeQuestion(probe);
        verdict.setCandidateStatus(CandidateVerdictStatus.SHARED);
        verdict.setCandidateRespondedAt(now);
        verdict.setCurrent(true);
        verdictRepository.save(verdict);
    }
}
