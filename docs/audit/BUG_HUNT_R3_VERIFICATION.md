# Bug hunt R3: claim verification (backend `verification` package and `agent-service`)

Date: 16 Nov 2026 · Branch `Kshitij-redesign` (working tree, not committed) · Scope: `backend/src/main/java/com/recruitpro/verification/**`
and its tests, `support/VerificationFixtures`, `agent-service/**`, migrations V21/V22/V25/V26 (read only; no new migration was
needed).

Method: every file of the scope read line by line against `docs/handbook/03-claim-verification.md`,
`docs/plan/IMPLEMENTATION_PLAN_V2.md` §1–5, `WP-AG-NOTES.md` (incl. "Trust fixes"), `WP-VBE-NOTES.md` and `STATUS.md`;
each finding fixed minimally with a regression test that fails on the old code (agent-service: verified by running the
new tests against a clean copy of the original code). The Java code cannot be compiled in this sandbox (JDK 11, no
Maven); it was checked with the static tools and by reading.

## Summary

| Severity | Fixed | Not fixed |
|---|---|---|
| Critical | 1 | 0 |
| High | 5 | 0 |
| Medium | 14 | 0 |
| Low | 24 | 1 |
| Info | 0 | 1 |

Column "Kshitij?": does the bug also exist on the demo branch `Kshitij` (which has V1–V25 but not the trust fixes:
no `app/trust.py`, no `DomainTrusts`/V26). "partly" = the root cause exists there but part of the scenario needs the
trust fixes.

## Backend (`backend/.../verification`)

Paths are relative to `backend/src/main/java/com/recruitpro/verification/`. Integration tests:
`backend/src/test/java/com/recruitpro/verification/VerificationBugHuntR3IntegrationTest.java` (IT); unit tests:
`backend/src/test/java/com/recruitpro/verification/service/VerificationBugHuntR3RulesTest.java` (UT).

| id | severity | file:line | problem | fix | test | Kshitij? |
|---|---|---|---|---|---|---|
| R3-B01 | High | `service/VerificationResultWriter.java:131-137`, `service/VerificationSourceService.java:340`, `:309`, `repository/VerificationRunRepository.java:26` | Revoke and the VERIFY_SOURCE result writer did not serialise. A revoke committing while a run result was being stored left current (shareable) verdicts, evidence rows and snapshot files on a revoked source, i.e. principle 6 ("revoking deletes evidence and withdraws verdicts") broken; same for an ownership proof stored during a revoke. | Both take the candidate row lock (as source create / run start already do): the writer reads the run's candidate id with a scalar query, locks, then loads run and source; `revoke` and `applyOwnershipResult` lock before reading. | IT `aResultStoredWhileTheSourceIsBeingRevokedIsNotKept` | yes |
| R3-B02 | Medium | `service/ReferenceService.java:257`, `:219`, `repository/VerificationReferenceRequestRepository.java:32-41` | "Answered once" was a read-check-write without a lock: two concurrent submissions (or answer + decline, or a candidate's cancel racing the answer) both passed the SENT check; the last write won (answers overwritten, ANSWERED turned CANCELLED, duplicate VERIFY_REFERENCE). | `submit` loads the request with `SELECT ... FOR UPDATE` by token hash; `cancel` with `FOR UPDATE` by id (ownership checked after). The loser sees the new status and gets 410 / 400. | IT `aRefereeAnswerRacingADeclineGets410AndTheFirstOutcomeStays` | yes |
| R3-B03 | Medium | `service/VerificationSubjects.java:78`, `service/StaffVerificationService.java:209` | Cross-organisation interference (R2-U6): a claim rejected by any organisation (the claim's "latest check" columns) was dropped from the candidate's subjects, so organisation B's rejection removed the claim's verdicts from organisation A's staff view, shadow queue and matching, and stopped runs/references for it; the staff tier also read the global columns, so A lost its CORROBORATED tier after B's later check. | Subjects no longer depend on the global rejection (rejection is per organisation since R2-H02); the staff view's claim tier comes from the organisation's own `candidate_claim_verifications` row (`ClaimEvidence.forOrganization(claim, row)`). | IT `anotherOrganisationsRejectionNeitherHidesTheClaimNorItsTierInThisOrganisation` | yes (global columns) |
| R3-B04 | Medium | `service/ReferenceService.java:180-186` | E-mail abuse: the 5-open cap is freed by cancelling, so a candidate could create/cancel without limit and make the server e-mail any address with text they control (referee name, first name). | At most `MAX_PER_DAY` = 10 requests per candidate per 24 h (cancelled included) → 429, checked under the candidate lock. | IT `referenceRequestsAreCappedPerDayEvenWhenCancelled` | yes |
| R3-B05 | Medium | `service/VerificationPrivacyService.java:168` | Account deletion kept the REFERENCE_REQUEST e-mails (candidate's name + live link; recipient is the referee, so the notification-side delete never matched them); an unsent one was still delivered after the account was gone (WP-VBE notes §7 known gap). | Deletion finds each request's e-mails by referee address + template and deletes those whose link token hashes to the request's `token_hash` (other candidates' e-mails to the same referee stay). | IT `accountDeletionRemovesTheCandidatesReferenceEmailsOnly`; UT `referenceEmailsAreMatchedByTheirToken` | yes |
| R3-B06 | Medium | `service/VerificationSourceService.java:169`, `:212`, `repository/VerificationSourceRepository.java:19` | No cap on connected sources: unlimited 15 MB document uploads per candidate (disk exhaustion) and unlimited re-runnable sources. | `MAX_ACTIVE_SOURCES` = 30 non-revoked sources per candidate → 429 (create and document upload, under the lock; revoked ones do not count). | IT `sourcesAreCappedPerCandidateAndLongHostsDoNotOverflowTheLabel` | yes |
| R3-B07 | Low | `service/VerificationRunService.java:204`, `service/VerifyReferenceJobHandler.java:136` | `holderName` = first (≤100) + " " + last (≤100) can be 201 characters; the agent accepts 200, so every run/reference of such a candidate failed with 422 → `AGENT_SERVICE_REJECTED`. | One helper builds the name and cuts it to 200. | UT `holderNamesFitTheAgentLimit` | yes |
| R3-B08 | Low | `service/VerificationSourceService.java:162`, `:571` | `display_label` is VARCHAR(300) but "Website: " / "Open Badge (" + host could exceed it (host up to ~490 chars in a 500-char URL) → DB error → HTTP 500. | Labels cut to 300. | IT `sourcesAreCappedPerCandidateAndLongHostsDoNotOverflowTheLabel`; UT `displayLabelsAreCut` | yes |
| R3-B09 | Low | `service/VerificationSourceService.java:520` | `parseUrl` rebuilt the URL from *decoded* path and query, so `%2F` became `/` and `%26` became `&`: the stored identifier (and the URL the agent fetched / the PAGE_CODE target) differed from what the candidate entered. | Rebuilt from raw path and query; fragment and user info still dropped; length re-checked. | UT `websiteUrlsKeepTheirEncodedPathAndQuery` | yes |
| R3-B10 | Low | `service/VerificationResultWriter.java:393` | `storeEvidence` registered the rollback cleanup only after all items; a runtime failure mid-loop (DB error) left the snapshot files already written on disk forever. | Runtime exceptions also delete the files written so far, then rethrow. | UT `snapshotsAreRemovedWhenStoringEvidenceFails` | yes |
| R3-B11 | Low | `service/CandidateLedgerService.java:151` | HIDE of a reviewed verdict re-matched (lift removed), but SHARE again did not, so the organisation's earlier CONFIRM silently stayed out of its matching until some other trigger. | SHARE (from not shared) of a verdict with any review re-matches the candidate. | IT `sharingAConfirmedVerdictAgainReMatchesTheCandidate` | yes |
| R3-B12 | Low | `service/VerificationMetrics.java:74` | `readyForVisible` compared the 4-place *rounded* FCR/kappa with the thresholds: FCR 0.020045 (shown 0.02) counted as ≤ 0.02, kappa 0.69996 as ≥ 0.70. | Thresholds compared with the exact values; the response still shows rounded ones. | UT `readinessIgnoresRounding` | yes |
| R3-B13 | Medium | `service/ShadowEvaluationService.java:131-138`, `:200` | The shadow queue showed, and the metrics scored, the stored verdict, ignoring the organisation's trusted-domain lists, while the staff view, tier rule and matching use the verdict as it counts for the organisation. Labellers judged a different verdict than the one the organisation would act on, and the false-corroboration rate (the go/no-go for VISIBLE) under-counted domain-lifted VERIFIED verdicts. | Queue items use `staffVerdict(..., settings)` (effective verdict + org domain trust); metrics pair the label with `DomainTrusts.effectiveVerdict(verdict, orgSettings)`. | IT `theShadowQueueAndMetricsUseTheVerdictAsItCountsForTheOrganisation` | no (V26 only) |
| R3-B14 | Low | `service/ShadowEvaluationService.java:157-162` | `POST .../verdicts/{id}/label` accepted verdicts the queue hides (connector disabled for the organisation, subject no longer current), so they entered the metrics. | Same filters as the queue → 404. | IT (same test, second part) | yes |
| R3-B15 | Low | `service/VerificationResultWriter.java:166`, `:175` | Run results: the duplicate check used the raw claim id but the lookup the trimmed one (`" claim:7 "` and `"claim:7"` both stored, the second superseding the first); a code host's verdict for a non-SKILL subject (never sent) was stored. | Duplicates compared trimmed; code-host verdicts for non-SKILL subjects dropped. | IT `codeHostResultsKeepOneSkillVerdictPerSubject` | yes |
| R3-B16 | Low | `service/DomainTrusts.java:93` | `effectiveVerdict` lifted a domain-capped PARTLY_VERIFIED to VERIFIED for a listing organisation even when `ownershipProven` was false (the tier rule re-checked ownership, but the staff view and shadow queue displayed VERIFIED): principle 2. | The lift requires proven ownership. | UT `trustedDomainsDoNotLiftTheOwnershipCap` | no (V26 only) |
| R3-B17 | Low | `service/VerificationPrivacyService.java:79`, `:106`, `dto/VerificationExport.java` | The data export left out the verdicts' domain-trust result (V26) and the sources' ownership-check outcome. | Added `domainTrust` (verdicts) and `ownershipDetail` (sources), additive. | IT `theDataExportCarriesDomainTrustAndOwnershipDetail` | partly |
| R3-B18 | Low | `service/GitHubOAuthService.java:102` | `GET .../oauth/github/start` has side effects (a state row per call) and no per-candidate limit. | **Not fixed** (see below). | — | yes |
| R3-B19 | Info | `client/GitHubOAuthClient.java:78` | `TokenResponse.scopes()` yields a leading empty element for a scope string with leading separators. | **Not fixed**: the method is unused (scopes are stored as the raw string). | — | yes |
| R3-B20 | Low | `service/ReferenceService.java:132`, `:146`, `:403` | Referee name / employer name accepted CR/LF and other control characters; they go into e-mail subject/body (the subject is flattened, the body is plain text, so no header injection, but multi-line text could forge e-mail content). | Control characters → 400. | IT `refereeNamesWithLineBreaksAreRefused`; UT `controlCharactersAreDetected` | yes |
| R3-B21 | Low | `service/GitHubOAuthService.java:172`, `controller/VerificationOAuthCallbackController.java:57` | The public callback promised "always 302", but any exception other than `RestClientException` (unexpected client error, DB failure while consuming the state) produced an HTTP 500 JSON page and left the binding cookie set. | Other runtime failures during the exchange → `?error=GITHUB_UNAVAILABLE`; anything else in the callback → `?error=CONNECT_FAILED`; the cookie is cleared in every case. | IT `anUnexpectedGitHubClientFailureStillRedirectsToThePortal` | yes |

## agent-service

Paths relative to `agent-service/`. Tests in `agent-service/tests/`; eval cases in `eval/cases.py`.

| id | severity | file:line | problem | fix | test | Kshitij? |
|---|---|---|---|---|---|---|
| AG-01 | Critical | `app/verifiers/badge.py:315-335` | OB2: the issuer `id` and `allowedOrigins` were taken from an embedded issuer profile, or from a fetched profile claiming another `id`. A forged badge on a 20-day-old domain claiming to be a Credly issuer (listing its own origin) came out VERIFIED, trusted as Credly. Reproduced. | The issuer id is the URL the profile was fetched from; `allowedOrigins` counts only from that fetched profile; a profile whose `id` names another origin is INVALID. | `test_badge.py::test_ob2_issuer_profile_cannot_claim_another_issuer`, `::test_ob2_embedded_issuer_profile_is_not_authoritative`, `::test_ob2_issuer_id_is_where_the_profile_was_fetched`; eval `badge-ob2-forged-issuer-profile` | partly (origin check yes; Credly trust no) |
| AG-02 | High | `app/trust.py:66`, `:395`, `:428` | `someone.github.io` had no RDAP answer, so the lookup climbed to `github.io` (2008): a self-issued badge or "employer" there counted as established (also netlify.app, vercel.app, herokuapp.com, ...). | Shared-hosting platform list: subdomains are WEAK (`signals.sharedHosting`) for issuer and employer checks; an organisation can still list them. | `test_trust.py::test_issuer_on_a_shared_hosting_subdomain_does_not_inherit_the_platform_age`, `::test_employer_domain_on_shared_hosting_is_weak`; eval `badge-ob3-shared-hosting-issuer` | no (trust.py) |
| AG-03 | High | `app/verifiers/ownership.py:99-106` | PAGE_CODE was "proven" by any page echoing its own URL (`victim.com/search?q=<code>`); redirects to another site (e.g. a paste site) were accepted for the page and the well-known file. | Refused when the code is in the URL or any redirect hop (also percent-encoded, any case); the final URL must stay on the same site (`www.` ignored). | `test_ownership.py::test_page_code_cannot_be_proven_by_a_page_that_echoes_its_url`, `::test_page_code_and_well_known_file_must_stay_on_the_site` | yes |
| AG-04 | High | `app/verifiers/reference.py:42`, `:57` | Free-mail detection was exact-match: `outlook.co.uk`, `hotmail.fr`, `yahoo.com.br` anchored a reference (and pass the employer trust check), so a sock-puppet mailbox gave VERIFIED. | Free-mail brands detected under country suffixes; company domains (`mail.ibm.com`, `acme.co.uk`) unaffected. | `test_reference.py::test_a_free_mail_country_domain_never_anchors_a_reference`, `::test_country_variants_of_free_mail_are_free_mail`, `::test_company_domains_are_not_free_mail`; eval `ref-free-mail-country-variant` | yes |
| AG-05 | Medium | `app/badges/status.py:44` | Only the first `credentialStatus` entry was checked; suspension list first + revocation list second (the usual pattern) → a revoked credential was VERIFIED. | Every revocation/suspension entry (max 3) checked; any set bit = revoked. | `test_badge.py::test_every_status_entry_is_checked`; eval `badge-ob3-revoked-second-status-entry` | yes |
| AG-06 | High | `app/verifiers/badge.py:131` | Recipient matching: any match won, so another person's badge whose *name* matched but whose e-mail differed came out matched → VERIFIED with `ownershipProven`. | An e-mail result outranks a name; e-mail differs + name matches = not comparable (capped). | `test_badge.py::test_a_matching_name_does_not_outweigh_a_different_email`; eval `badge-ob3-namesake-recipient` | yes |
| AG-07 | Medium | `app/fetch/client.py:189`, `:204` | No whole-request deadline: httpx timeouts are per read, so a byte every 9 s held a verification past the 50 s deadline. | Deadline = min(fetch timeout, remaining verification deadline), checked between chunks → TIMEOUT. | `test_ssrf.py::test_a_trickling_body_hits_the_total_timeout` | yes |
| AG-08 | Medium | `app/fetch/ssrf.py:34`, `app/fetch/client.py:218` | Control characters in a URL / crawled link raised an uncaught `InvalidURL` → the whole web verification answered 500. | `parse_url` rejects control characters; `InvalidURL` → URL_BLOCKED. | `test_ssrf.py::test_control_characters_in_urls_are_blocked_not_a_crash`, `::test_a_link_with_a_control_character_is_skipped_by_the_crawl` | yes |
| AG-09 | Medium | `app/extract.py:108` | pypdf `LimitReachedError` (and other non-`PdfReadError` errors) escaped as 500 on `/verify/document` and on crawled PDFs (found by fuzzing). | Every PDF/DOCX/PPTX parsing error → DOCUMENT_UNREADABLE. | `test_document.py::test_malformed_pdfs_are_unreadable_not_a_server_error`, `::test_pypdf_errors_outside_pdfreaderror_are_mapped`; `test_web.py::test_a_broken_pdf_on_the_site_is_skipped_not_a_server_error` | yes |
| AG-10 | Medium | `app/deidentify.py:45`, `:120`, `:126` | Known garbling "Rao Institute" → "the candidate Institute"; common-word name parts replaced everywhere ("a page of the site" → "a the candidate of the site"); replacements were re-replaced ("@ana" → "the the account"). | One-pass replacement (longest match first); a name part directly before an organisation word (Institute, University, Labs, Inc, & Co, ...) is kept; common-word name parts only when capitalised. Full name, other parts, logins, e-mails, URLs, hosts still always replaced. | `test_verdicts.py::test_surname_inside_an_organisation_name_is_not_garbled`, `::test_name_parts_that_are_common_words_only_match_when_capitalised`, `::test_replacements_are_never_replaced_again` | yes |
| AG-11 | Medium | `app/deidentify.py:32` | Phone numbers in reason quotes were never de-identified. | `+` with 8+ digits, or 10–15 digits → "the phone number"; years/dates kept. | `test_verdicts.py::test_phone_numbers_are_de_identified_but_years_and_dates_are_not` | yes |
| AG-12 | Medium | `app/sources/common.py:109`, `:140` | GitHub secondary rate limits (403 + `retry-after` / "rate limit") and GitLab `ratelimit-remaining: 0` were treated as a plain 403: the profile call reported SOURCE_NOT_FOUND ("account does not exist"), repo calls came back empty. | Shared rate-limit detection → 502 RATE_LIMITED; a plain 403 unchanged. | `test_code.py::test_rate_limits_are_reported_not_mistaken_for_a_missing_account`, `::test_a_plain_403_is_still_not_found` | yes |
| AG-13 | Medium | `app/sources/gitlab.py:97` | GitLab raw files (README, manifests) were read whole into memory, no size cap or rate-limit handling (the test hangs on the old code). | Streamed `ApiClient.get_raw`, 200 KB cap, same error mapping as GitHub. | `test_code.py::test_gitlab_raw_files_are_streamed_and_capped`, `::test_gitlab_raw_file_rate_limit_is_reported_like_github` | yes |
| AG-14 | Low | `app/demo/fixtures.py:60` | `demo-ravi`'s bulk-history repo had a fixed date (2025-11-10) and no longer showed BULK_HISTORY (handbook §10 #12). | Creation date relative to the check (3 days ago, all commits that day). | `test_demo.py::test_demo_ravi_always_shows_bulk_history` | yes |
| AG-15 | Low | `app/fetch/ssrf.py:31`, `:116` | Blocked host names checked before IDNA normalisation (`ｌｏｃａｌｈｏｓｔ`, `x.ｉｎｔｅｒｎａｌ` slipped past the name check; the address check still caught them); `fec0::/10` allowed. | Name check after IDNA; `fec0::/10` blocked. Decimal/octal/hex/short IPv4 and `::ffff:127.0.0.1` were already blocked (tests added). | `test_ssrf.py::test_blocked_host_names_are_checked_after_idna_normalisation`, `::test_site_local_and_mapped_ipv6_and_short_ipv4_forms_are_blocked` | likely |
| AG-16 | Low | `app/fetch/client.py:139` | https → http redirects were followed (DID documents, JWKS, assertions over plain http). | Refused (URL_BLOCKED). | `test_ssrf.py::test_https_to_http_redirects_are_refused` | likely |
| AG-17 | Low | `app/fetch/client.py:181` | Host header of an IPv6-literal URL lacked brackets. | Brackets added. | `test_ssrf.py::test_ipv6_literal_host_header_keeps_its_brackets` | no |
| AG-18 | Low | `app/badges/keys.py:84`, `app/verifiers/badge.py:154-158`, `:407`, `:429` | Malformed RSA `n` in a JWKS, a non-object JWT payload, or deeply nested pasted JSON → 500. | KeyResolutionError (NOT_CHECKABLE), INVALID or BAD_CREDENTIAL. | `test_badge.py::test_malformed_issuer_keys_and_payloads_are_not_server_errors` | yes |
| AG-19 | Low | `app/manifests.py:157`, `app/api/routes.py:97` | Deeply nested package.json / Cargo.toml in a candidate repo → 500 for the whole code verification; same for the document endpoint's `request` JSON. | `RecursionError` caught. | `test_code.py::test_deeply_nested_manifests_do_not_crash_the_verification`; `test_document.py::test_deeply_nested_request_json_is_a_422` | yes |
| AG-20 | Low | `app/verifiers/badge.py:219`, `:353` | Expiry checked without clock skew (handbook: 5 min for exp and nbf). | Same skew for expiry. | `test_badge.py::test_expiry_allows_the_same_clock_skew_as_not_before` | yes |
| AG-21 | Low | `app/injection.py:37`, `:46`, `app/citations.py:63` | Zero-width / full-width characters evaded the injection screen; LLM summary quotes were never screened. | Also scan an NFKC copy without invisible characters; screen quotes. | `test_verdicts.py::test_injection_markers_survive_invisible_and_full_width_characters`, `::test_summary_quotes_with_injection_markers_are_dropped` | yes |
| AG-22 | Low | `app/fetch/html.py:26` | Text hidden with inline `opacity:0` / `font-size:0` counted as a claim statement. | Treated as hidden. | `test_web.py::test_text_hidden_with_opacity_or_zero_font_size_never_counts`; eval `web-hidden-opacity` | yes |
| AG-23 | Low | `app/fetch/client.py:41` | HTML without a charset header always decoded as UTF-8 (`<meta charset>` ignored). | Charset sniffed from BOM / `<meta charset>`. | `test_ssrf.py::test_html_charset_is_sniffed_from_meta_when_the_header_has_none` | yes |
| AG-24 | Low | `app/core/config.py:107-111`, `README.md` | Startup accepted a 1-character token, the `.env.example` placeholder, zero/negative/NaN timeouts or document size. | `validate_settings` refuses them (token ≥ 16 chars, not `replace-with...`/`changeme...`); README example token updated. The compose default token passes. | `test_auth.py::test_unsafe_or_impossible_settings_refuse_startup` | no |
| AG-25 | Low | `eval/run.py:80` | A crashing eval case was scored NOT_FOUND and `--check` still passed. | Any crashed case fails `--check`. | `test_eval.py::test_a_crashing_case_fails_the_gate` | no |

## Not fixed, and why

- **R3-B18** OAuth `start` has no per-candidate limit: it needs an authenticated candidate, each call costs one small row,
  and rows are purged daily by `VerificationMaintenance`; the rate limiter is disabled in the test profile, so a limit
  could not be tested here. Recommendation: add `PublicEndpointRateLimiter` rule `oauth-start` (e.g. 20/h per candidate).
- **R3-B19** dead code (`TokenResponse.scopes()` unused).
- Known design gaps left as documented (not bugs of this pass): rate limits are in-memory per instance; no orphan-file
  sweep (deletion after commit is best effort); documents stored unencrypted; the OAuth token is not revoked at GitHub
  on source revoke; a cancelled reference's not-yet-sent e-mail is still delivered (the account-deletion case is fixed,
  R3-B05); a recheck un-shares (handbook §10 #9); readiness for VISIBLE is advice only; reviews/labels other
  organisations made about a candidate's verdicts are not in the candidate's export (organisation data; product
  decision); GitHub OAuth reconnects bypass the 30-source cap (reconnecting an existing login is the normal path).
- agent-service: repository/commit lists are not paginated beyond 100 by design;
  no production marker exists to refuse demo fixtures in prod (warning only); two-letter surnames are never replaced
  alone (the full name is); a GraphQL node without a language name can still raise `KeyError` (trusted upstream);
  employer home pages may redirect to other sites (alias domains); CSS-class / stylesheet hidden text is not detectable
  without rendering; backdated commits in *old* repositories cannot be detected from commit dates; one-word summary
  quotes still pass the citation check (3-character minimum); duplicate crawl pages after redirects (cosmetic).

## Checked and found correct (no change)

OAuth: state 32 random bytes, only SHA-256 stored, 10-min expiry, conditional single-use `markUsed`, browser binding
(httpOnly, SameSite=Lax, callback-path cookie, constant-time compare, mismatch does not consume), PKCE S256 with the
verifier encrypted under `voauth:<stateHash>`, token encrypted under `vsrc:<id>` after the row exists, error redirects
built only from constants (no open redirect), `error=` burns the state. Ownership cap in the backend (DOCUMENT never,
code/web need PROVEN source, badges/references the agent's per-claim value) and in the agent. Hash check (one mismatch
rejects all), storage keys UUID-validated and resolved inside the root (no traversal; symlinks refused on load). One
active run per source and the 10/24 h limit under the candidate lock. Job error mapping (coded → FAILED, transient →
retry, dead letter → `AGENT_SERVICE_UNAVAILABLE`), retry idempotency (`claim` returns null for finished runs, references
check `verified_at`). Supersede/withdraw keep one current verdict per subject + source/reference. Staff view: SHADOW
returns `claims: []` before any verdict is read, INTERVIEWER 403 at class level, other organisations 404, evidence /
candidate note / trusted domain null before reveal, reviews and label counts scoped to the caller's organisation.
Tier rule and `VerificationEvidenceContributor` exactly as plan §3.5 (+ connector, + organisation trust lists, + current
subject). Kappa (p_e = 1 only when everything agrees on one class → 1.0; no pairs → null), FCR, per-class precision /
recall. Account deletion covers every verification table (sources, runs, evidence, verdicts, reviews, labels, references,
OAuth states) and files; retention anonymises old reference requests (WP-OPS). DTO sizes vs columns (except B07/B08).
SSRF: decimal/octal/hex/short IPv4, IPv4-mapped IPv6, link-local/metadata, CGNAT, multicast, userinfo, ports, redirects
re-validated per hop, connection pinned to the validated address; token middleware constant-time; JWT `alg: none`,
HS256-with-public-key, embedded `jwk`, foreign `jku`/`kid` rejected.

## Documentation drift (not edited here; outside the scope's files)

- Handbook §4.5/§3.x and WP-VBE notes: "claims not rejected by a recruiter" (now: all claims; R3-B03); new limits
  (30 sources, 10 references/24 h); account deletion now also removes REFERENCE_REQUEST e-mails (WP-VBE §7 gap closed).
- Handbook §5.8 and WP-AG "Open risks": OB2 issuer rule (AG-01) and the de-identification garbling (AG-10) are fixed.

## Gates

| Gate | Result |
|---|---|
| `cd agent-service && python3 -m pytest -q` | **273 passed** (baseline 205) |
| `cd agent-service && python3 -m eval.run --check` | exit 0: 99 cases (43 adversarial), mismatches 0, errors 0, false-corroboration 0.0, kappa 1.0 |
| `python3 tools/java_static_check.py backend/src` | 606 files, **0 errors** |
| `python3 tools/check_entity_schema.py` | 488 columns of 42 entities vs 27 migrations, **0 errors, 0 warnings** |
| `python3 tools/check_migrations_pg.py` | not run: no migration added |
| `./mvnw verify` | **not run** (no JDK 21 / Maven in the sandbox). The new Java tests (2 classes, 20 tests) were written to fail on the old code; first run on a JDK 21 machine. |
