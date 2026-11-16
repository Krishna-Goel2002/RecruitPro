package com.recruitpro.verification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.evidence.service.MatchTriggerService;
import com.recruitpro.notification.service.NotificationService;
import com.recruitpro.support.VerificationFixtures;
import com.recruitpro.verification.dto.VerificationMetricsResponse;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.EvidenceItem;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationMode;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.EvidenceItemRepository;
import com.recruitpro.verification.repository.VerificationCandidateLockRepository;
import com.recruitpro.verification.repository.VerificationReferenceRequestRepository;
import com.recruitpro.verification.repository.VerificationRunRepository;
import com.recruitpro.verification.repository.VerificationSourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Bug hunt R3: pure rules of the backend findings (docs/audit/BUG_HUNT_R3_VERIFICATION.md). */
class VerificationBugHuntR3RulesTest {

    // R3-B07: the agent accepts a holderName of at most 200 characters.
    @Test
    void holderNamesFitTheAgentLimit() {
        assertEquals("Asha Rao", VerificationRunService.holderName(" Asha", "Rao "));
        assertEquals("Asha", VerificationRunService.holderName("Asha", null));
        String longName = VerificationRunService.holderName("A".repeat(100), "B".repeat(100));
        assertEquals(VerificationRunService.MAX_HOLDER_NAME, longName.length(), "100 + 1 + 100 characters are cut to 200");
    }

    // R3-B09: path and query are kept raw (percent-encoding preserved); fragment and user info never kept.
    @Test
    void websiteUrlsKeepTheirEncodedPathAndQuery() {
        assertEquals("https://example.test/a%2Fb?q=a%26b",
                VerificationSourceService.parseUrl("HTTPS://Example.TEST/a%2Fb?q=a%26b#frag", "bad").toString());
        assertEquals("http://example.test:8080/x", VerificationSourceService.parseUrl("http://example.test:8080/x", "bad").toString());
        assertEquals("https://example.test", VerificationSourceService.parseUrl("https://example.test", "bad").toString());
        assertThrows(RuntimeException.class, () -> VerificationSourceService.parseUrl("https://user@example.test/", "bad"));
        assertThrows(RuntimeException.class, () -> VerificationSourceService.parseUrl("ftp://example.test/", "bad"));
    }

    // R3-B08: display labels stay within their 300-character column.
    @Test
    void displayLabelsAreCut() {
        assertEquals(300, VerificationSourceService.label("Website: " + "a".repeat(400)).length());
        assertEquals("Website: a.test", VerificationSourceService.label("Website: a.test"));
    }

    // R3-B12: readiness is decided on exact values, not on the rounded ones that are displayed.
    @Test
    void readinessIgnoresRounding() {
        List<VerificationMetrics.Pair> pairs = new ArrayList<>();
        add(pairs, 100, VerdictValue.VERIFIED, VerdictValue.VERIFIED);
        add(pairs, 440, VerdictValue.NOT_FOUND, VerdictValue.NOT_FOUND);
        add(pairs, 9, VerdictValue.VERIFIED, VerdictValue.NOT_FOUND); // 9 / 449 = 0.020045: shown as 0.02
        VerificationMetricsResponse metrics = VerificationMetrics.compute(pairs);
        assertEquals(0.02, metrics.falseCorroborationRate(), 1e-12);
        assertTrue(metrics.kappa() >= VerificationMetrics.READY_MIN_KAPPA);
        assertFalse(metrics.readyForVisible(), "an FCR above 0.02 is not ready, whatever it rounds to");

        pairs.remove(pairs.size() - 1); // 8 / 448 = 0.0179
        assertTrue(VerificationMetrics.compute(pairs).readyForVisible());
    }

    // R3-B16: an organisation's trusted domain never lifts a verdict whose ownership is not proven.
    @Test
    void trustedDomainsDoNotLiftTheOwnershipCap() {
        VerificationSettingsService.Effective trusting = new VerificationSettingsService.Effective(VerificationMode.VISIBLE,
                Map.of("OPEN_BADGE", true), List.of(), List.of("issuer.example"));
        ClaimVerdict owned = verdict(true);
        ClaimVerdict notOwned = verdict(false);
        assertEquals(VerdictValue.VERIFIED, DomainTrusts.effectiveVerdict(owned, trusting));
        assertEquals(VerdictValue.PARTLY_VERIFIED, DomainTrusts.effectiveVerdict(notOwned, trusting));
        assertFalse(DomainTrusts.view(notOwned, trusting, false).liftedByOrganization());
    }

    // R3-B05: a reference e-mail is matched to its request through the token in its link.
    @Test
    void referenceEmailsAreMatchedByTheirToken() {
        String token = ReferenceService.newToken();
        String body = "Hello,\nOpen this link:\nhttp://localhost:5173/reference/" + token + "\n";
        assertTrue(VerificationPrivacyService.carriesToken(body, ReferenceService.sha256Hex(token)));
        assertFalse(VerificationPrivacyService.carriesToken(body, ReferenceService.sha256Hex(ReferenceService.newToken())));
        assertFalse(VerificationPrivacyService.carriesToken(null, ReferenceService.sha256Hex(token)));
    }

    // R3-B20: names with control characters are refused (they end up in e-mails).
    @Test
    void controlCharactersAreDetected() {
        assertTrue(ReferenceService.hasControlCharacters("Rita\r\nBcc: x"));
        assertTrue(ReferenceService.hasControlCharacters("Rita\u0000"));
        assertFalse(ReferenceService.hasControlCharacters("Rita O'Brien-Müller"));
    }

    // R3-B10: a failing insert removes the snapshots already written.
    @Test
    void snapshotsAreRemovedWhenStoringEvidenceFails(@TempDir Path root) throws IOException {
        VerificationStorage storage = new VerificationStorage(root.toString());
        EvidenceItemRepository evidence = mock(EvidenceItemRepository.class);
        when(evidence.save(any(EvidenceItem.class))).thenThrow(new IllegalStateException("database unavailable"));
        VerificationResultWriter writer = new VerificationResultWriter(mock(VerificationRunRepository.class),
                mock(VerificationSourceRepository.class), mock(VerificationReferenceRequestRepository.class), evidence,
                mock(ClaimVerdictRepository.class), mock(CandidateProfileRepository.class), mock(VerificationSubjects.class),
                storage, new VerificationViews(new ObjectMapper()), mock(NotificationService.class),
                mock(MatchTriggerService.class), mock(VerificationCandidateLockRepository.class), "http://localhost:5173");

        assertThrows(IllegalStateException.class, () -> writer.storeOwnershipProof(
                VerificationFixtures.item("ownership-1", "OWNERSHIP_PROOF", "https://site.example/", "rp-code"), 1L, 2L));
        try (Stream<Path> files = Files.list(root)) {
            assertEquals(0, files.count(), "no orphaned snapshot file");
        }
    }

    private static void add(List<VerificationMetrics.Pair> pairs, int times, VerdictValue predicted, VerdictValue gold) {
        for (int i = 0; i < times; i++) {
            pairs.add(new VerificationMetrics.Pair("code", predicted, gold));
        }
    }

    private static ClaimVerdict verdict(boolean ownershipProven) {
        ClaimVerdict verdict = new ClaimVerdict();
        verdict.setVerdict(VerdictValue.PARTLY_VERIFIED);
        verdict.setOwnershipProven(ownershipProven);
        verdict.setCandidateStatus(CandidateVerdictStatus.SHARED);
        verdict.setCurrent(true);
        verdict.setDomainTrustJson(VerificationFixtures.domainTrustJson("BADGE_ISSUER", "badges.issuer.example", "WEAK", "VERIFIED"));
        return verdict;
    }
}
