package com.recruitpro.verification;

import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.ClaimVerdictLabel;
import com.recruitpro.verification.entity.ClaimVerdictReview;
import com.recruitpro.verification.entity.EvidenceItem;
import com.recruitpro.verification.entity.EvidenceItemKind;
import com.recruitpro.verification.entity.ReferenceRelationship;
import com.recruitpro.verification.entity.ReferenceStatus;
import com.recruitpro.verification.entity.ReviewDecision;
import com.recruitpro.verification.entity.RunRequester;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationMode;
import com.recruitpro.verification.entity.VerificationOAuthState;
import com.recruitpro.verification.entity.VerificationReferenceRequest;
import com.recruitpro.verification.entity.VerificationRun;
import com.recruitpro.verification.entity.VerificationRunStatus;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.ClaimVerdictLabelRepository;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.ClaimVerdictReviewRepository;
import com.recruitpro.verification.repository.EvidenceItemRepository;
import com.recruitpro.verification.repository.OrganizationVerificationSettingsRepository;
import com.recruitpro.verification.repository.VerificationOAuthStateRepository;
import com.recruitpro.verification.repository.VerificationReferenceRequestRepository;
import com.recruitpro.verification.repository.VerificationRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V21/V22 apply on H2 (MODE=PostgreSQL) and every verification entity round-trips. */
class VerificationSchemaIntegrationTest extends VerificationIntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired VerificationRunRepository runs;
    @Autowired EvidenceItemRepository evidence;
    @Autowired ClaimVerdictRepository verdicts;
    @Autowired ClaimVerdictReviewRepository reviews;
    @Autowired ClaimVerdictLabelRepository labels;
    @Autowired OrganizationVerificationSettingsRepository settings;
    @Autowired VerificationOAuthStateRepository states;
    @Autowired VerificationReferenceRequestRepository references;

    @Test
    void migrationsCreateEveryVerificationTable() {
        for (String table : List.of("verification_sources", "verification_runs", "evidence_items", "claim_verdicts",
                "claim_verdict_reviews", "claim_verdict_labels", "organization_verification_settings",
                "verification_oauth_states", "verification_reference_requests")) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            assertEquals(0, count, table);
        }
        Integer referenceColumn = jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE LOWER(TABLE_NAME) = 'evidence_items' AND LOWER(COLUMN_NAME) = 'reference_id'", Integer.class);
        assertEquals(1, referenceColumn, "V22 adds evidence_items.reference_id");
    }

    @Test
    void entitiesRoundTripAndConstraintsHold() {
        Organization organization = fixtures.organization("Schema Org");
        User reviewer = fixtures.staff(organization, "reviewer@schema.test", Role.RECRUITER);
        CandidateProfile candidate = fixtures.candidate("schema@cand.test");
        CandidateClaim claim = verification.claim(candidate, ClaimType.SKILL, "Java");
        VerificationSource source = verification.source(candidate, VerificationSourceKind.GITHUB, "octo", true);
        assertNotNull(source.getId());
        assertNotNull(source.getCreatedAt());

        VerificationRun run = new VerificationRun();
        run.setSourceId(source.getId());
        run.setCandidateId(candidate.getId());
        run.setStatus(VerificationRunStatus.COMPLETED);
        run.setRequestedBy(RunRequester.SYSTEM);
        run.setUsageJson("{\"requests\":3}");
        run = runs.saveAndFlush(run);

        EvidenceItem item = new EvidenceItem();
        item.setRunId(run.getId());
        item.setSourceId(source.getId());
        item.setCandidateId(candidate.getId());
        item.setItemKey("repo-1");
        item.setKind(EvidenceItemKind.REPOSITORY);
        item.setUrl("https://github.com/octo/repo");
        item.setRetrievedAt(UtcTime.now());
        item.setContentHash("sha256:" + "a".repeat(64));
        item = evidence.saveAndFlush(item);
        assertNotNull(item.getCreatedAt());

        ClaimVerdict verdict = verification.verdict(source, claim, VerdictValue.VERIFIED, true, CandidateVerdictStatus.SHARED);
        ClaimVerdict loaded = verdicts.findById(verdict.getId()).orElseThrow();
        assertTrue(loaded.isCurrent());
        assertEquals("claim:" + claim.getId(), loaded.subjectRef());

        ClaimVerdictReview review = new ClaimVerdictReview();
        review.setVerdictId(verdict.getId());
        review.setOrganizationId(organization.getId());
        review.setDecision(ReviewDecision.CONFIRM);
        review.setReviewerUserId(reviewer.getId());
        assertNotNull(reviews.saveAndFlush(review).getCreatedAt());

        ClaimVerdictLabel label = new ClaimVerdictLabel();
        label.setVerdictId(verdict.getId());
        label.setOrganizationId(organization.getId());
        label.setLabellerUserId(reviewer.getId());
        label.setLabelVerdict(VerdictValue.PARTLY_VERIFIED);
        labels.saveAndFlush(label);
        ClaimVerdictLabel duplicate = new ClaimVerdictLabel();
        duplicate.setVerdictId(verdict.getId());
        duplicate.setOrganizationId(organization.getId());
        duplicate.setLabellerUserId(reviewer.getId());
        duplicate.setLabelVerdict(VerdictValue.VERIFIED);
        assertThrows(DataIntegrityViolationException.class, () -> labels.saveAndFlush(duplicate),
                "one label per verdict and labeller");

        verification.mode(organization.getId(), VerificationMode.VISIBLE);
        assertEquals(VerificationMode.VISIBLE, settings.findById(organization.getId()).orElseThrow().getMode());

        VerificationOAuthState state = new VerificationOAuthState();
        state.setStateHash("b".repeat(64));
        state.setCandidateId(candidate.getId());
        state.setProvider("GITHUB");
        state.setExpiresAt(UtcTime.now().plusMinutes(10));
        state = states.saveAndFlush(state);
        assertEquals(1, states.markUsed(state.getId(), UtcTime.now()));
        assertEquals(0, states.markUsed(state.getId(), UtcTime.now()), "a state is used once");

        VerificationReferenceRequest reference = new VerificationReferenceRequest();
        reference.setCandidateId(candidate.getId());
        reference.setRefereeName("Rita Referee");
        reference.setRefereeEmail("rita@employer.test");
        reference.setRelationship(ReferenceRelationship.MANAGER);
        reference.setClaimRefsJson("[]");
        reference.setTokenHash("c".repeat(64));
        reference.setStatus(ReferenceStatus.SENT);
        reference.setExpiresAt(UtcTime.now().plusDays(14));
        reference.setConsentedAt(UtcTime.now());
        reference = references.saveAndFlush(reference);
        assertFalse(references.findByTokenHash("c".repeat(64)).isEmpty());

        ClaimVerdict referenceVerdict = verification.verdict(source, claim, VerdictValue.NOT_FOUND, false,
                CandidateVerdictStatus.AWAITING_CANDIDATE);
        referenceVerdict.setSourceId(null);
        referenceVerdict.setReferenceId(reference.getId());
        verdicts.saveAndFlush(referenceVerdict);
        assertEquals(1, verdicts.findAllByReferenceIdOrderByIdAsc(reference.getId()).size());

        // An unknown enum value is rejected by the CHECK constraints.
        assertThrows(Exception.class, () -> jdbc.update("UPDATE claim_verdicts SET verdict = 'MAYBE' WHERE id = ?", verdict.getId()));
    }
}
