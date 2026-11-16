# Bug hunt summary: 7 reviewers and the integration pass

**Branch:** `Kshitij-redesign`, working tree of 16 Nov 2026. Nothing has been committed.

**Reports:**
- [R1 platform](BUG_HUNT_R1_PLATFORM.md)
- [R2 hiring core](BUG_HUNT_R2_HIRING.md)
- [R3 verification](BUG_HUNT_R3_VERIFICATION.md)
- [R4 AI service](BUG_HUNT_R4_AI.md)
- [R5 candidate portal](BUG_HUNT_R5_CANDIDATE.md)
- [R6 HR portal](BUG_HUNT_R6_HR.md)
- [R7 infrastructure](BUG_HUNT_R7_INFRA.md)

This file adds the integration pass, which reconciled the reports with each other and fixed their follow-ups.

> **Biggest remaining risk.** None of the Java changes have been compiled or run. That covers about 110 changed or
> new main-code entries and 41 test entries in `git status`, with dozens of new test methods. The sandbox has JDK 11 and no access to Maven Central. Two
> independent read-throughs acted as the compiler and found nothing, but the first `./mvnw verify` (CI on a push, or
> a JDK 21 machine) is the real gate. Run it before merging or porting anything.

## 1. Totals per area and severity

"Found" is the number of findings in each report. "Integration" counts the items from each report's "not fixed" or
"backend fixes needed" list that were fixed in this pass.

| Area | Critical | High | Medium | Low | Info | Found | Fixed by reviewer | Fixed in integration | Still open |
|---|---|---|---|---|---|---|---|---|---|
| R1 backend platform | 0 | 2 | 9 | 14 | 0 | 25 | 25 | 6 of 8 (U1, U2, U3, U6, U7, U8) | U4 (ops), U5 (product) |
| R2 backend hiring core | 0 | 3 | 5 | 8 | 0 | 16 | 16 | 5 of 10 (U3 partly, U4, U6, U9, U10) | U1, U2 (product); U5, U7, U8 (by design or unreachable) |
| R3 verification + agent-service | 1 | 5 | 14 | 25 | 1 | 46 | 44 | 2 (B18, B19) | 0 |
| R4 AI service | 0 | 14 | 30 | 14 | 0 | 58 | 58 | 1 of 10 (U8) | 9 (see section 4) |
| R5 candidate portal | 0 | 3 | 16 | 18 | 0 | 37 | 37 | backend list items 1–7, plus 8 partly | notification links |
| R6 HR portal | 0 | 4 | 21 | 27 | 0 | 52 | 52 | backend list B1–B8 | B6 (links), B9 (optional) |
| R7 infra / CI / docs | 0 | 3 | 12 | 12 | 1 | 28 | 27 + R7-17 (owner action, done now) | R7-17 | 0 |
| Integration pass (new, section 3) | 0 | 0 | 2 | 2 | 0 | 4 | – | 4 | 0 |
| **Total** | **1** | **34** | **109** | **120** | **2** | **266** | | | |

## 2. Critical and high findings (one line each; all fixed)

| ID | Finding |
|---|---|
| AG-01 (Critical) | OB2: an embedded or foreign issuer profile could pose as Credly. A forged badge came out VERIFIED. |
| R1-H1 | The lockout counter lost increments under parallel guesses, so the 5-attempt lockout did not hold. |
| R1-H2 | A stolen access token gave an unlimited password oracle on `/me/password` and `/me/mfa/disable`. The MFA enrol and confirm steps had no limit. |
| R2-H01 | A mandatory OTHER requirement put every applicant in MUST_HAVES_UNVERIFIED. |
| R2-H02 | A claim's verification was global: one organisation's verify or reject overwrote another's. |
| R2-H03 | A 30 s AI read time-out cut off legitimate slow parses, which were then retried and saturated the pool. |
| R3-B01 | Revoking a source raced the result writer, so verdicts and evidence survived the revoke. |
| AG-02 | Subdomains of shared hosting (github.io, netlify.app, …) inherited the platform's domain age. |
| AG-03 | PAGE_CODE ownership could be "proven" by a page that echoes its own URL, or through a redirect to another site. |
| AG-04 | Country variants of free-mail domains could anchor a reference. |
| AG-06 | A badge whose recipient name matched but whose e-mail differed counted as the candidate's. |
| R4-03 | The stemmer stripped "s" twice, so "nursing" never matched "nurse". |
| R4-04 | DOCX table rows were read cell by cell, so role titles were lost. |
| R4-07 | The file type came from the extension only, so renamed PDFs and DOCX files failed to parse. |
| R4-14 | `parse_resume` could raise, giving an HTTP 500 and five backend retries. |
| R4-18 | A "Roles & Responsibilities:" heading ended the work-history section. |
| R4-20 | Names with initials, accents or particles were not redacted. |
| R4-22 | When redactions overlapped, part of the longer one stayed visible. |
| R4-25 | DD/MM/YYYY and other Indian date formats were not parsed, so there was no timeline. |
| R4-30 | A bare "Master" counted as a degree, so "Scrum Master" became EDUCATION. |
| R4-33 | Practitioner titles such as PGT or midwife did not count as experience in their field. |
| R4-38 | Proficiency was too aggressive: filler words became ADVANCED. |
| R4-39 | Age, gender and marital wording became requirements without a warning. |
| R4-52 | The alias "SOLID" matched "solid understanding". |
| R4-55 | The address and phone patterns ran into the next lines and swallowed the skills. |
| R5 B-01 | The `refetchInterval` side effect starved polling. |
| R5 B-02 | The polling window never opened for runs started later. |
| R5 B-03 | A `javascript:` evidence URL could run from "View source". |
| R6-02 | Shortcuts under a confirm dialog could swap a shortlist for a reject. |
| R6-05 | A decision still inside its Undo window was lost on tab close after navigating away. |
| R6-15 | A pure reorder created a requirements version and re-matched everyone, and new rows lost their links. |
| R6-21 | Blind review relied on the server alone. |
| R7-01 | CI never ran on the demo or redesign branches. |
| R7-02 | No Docker image or compose file was ever built or validated in CI. |
| R7-23 | The run-and-test guide was wrong for the redesign branch. |

## 3. What changed in the integration pass

### 3.1 Claim verification: one model across all packages (R2-H02 and R3-B03 reconciled)

**Decision.** `candidate_claim_verifications` (one row per claim and organisation) is the **only decision input**.
The old `candidate_claims` columns stay in the schema but are deprecated: `tier`, `verified_by_user_id`,
`verified_at` and `verification_note`.

They are still written, but only as a **derived candidate-facing summary** of all organisations' rows,
`CandidateClaim.summarize(rows)`:
- if any organisation corroborated the claim, the summary shows the latest corroboration;
- otherwise, if any organisation rejected it, it shows the latest rejection;
- otherwise, the claim is unverified.

The summary no longer depends on which organisation checked last. Nothing that decides for an organisation reads
these columns. Changing a claim clears every row and the summary.

**Every reader and writer, checked:**

| Place | State after this pass |
|---|---|
| `hr/service/ApplicationDecisionService.verifyClaim` | Upserts the organisation's row, then recomputes the summary. Before, it copied "latest by anyone" into the columns. |
| `evidence/service/MatchingService`, `MatchRequestBuilder`, `ClaimEvidence` | Use only the per-organisation row. The legacy `ClaimEvidence.forOrganization(claim, Long, Long)` and `verifiedBy` read the global columns and were **removed**. |
| `evidence/dto/CandidateClaimResponse` | The staff view reads the organisation's row. The legacy `forStaff(claim, boolean, boolean)` read the global columns and was **removed**. The candidate view shows the summary. |
| `evidence/entity/CandidateClaim` | `isRejected()` was **removed** (it was only used by the legacy overload). `verificationStatus()` documents that it returns the summary. |
| `evidence/service/CandidateClaimService` | On a value change it deletes all rows and resets the summary (unchanged). Fact corrections now respect the 100-claim cap (R2-U4). |
| `verification/service/VerificationSubjects` | Unchanged since R3-B03: no claim is dropped because of a rejection. The subject tier is the summary and is used only by the candidate ledger. |
| `verification/service/StaffVerificationService` | Since R3-B03, the claim tier comes from the organisation's own row. |
| `verification/service/VerificationEvidenceContributor` | **New:** a claim that the job's organisation rejected never lifts (`vf:` fact) in that organisation's matching. Other organisations' rejections have no effect. This restores what the pre-R3 global filter did, but per organisation: R3-B03 had removed the filter for every organisation, including the one that rejected the claim. |
| Privacy export / account deletion | The export lists claims with the summary (new `ClaimExport`, explicit nulls). Rows of other organisations are organisation data and are not exported. Deletion cascades the rows (FK `ON DELETE CASCADE`). |
| Demo seeders (`VerificationDemoSeeder`) | Create only unverified claims; no change needed. |
| Tests | `MatchRequestBuilderTest` was moved to per-organisation rows and gained a summary test. `ClaimVerificationPerOrganizationIntegrationTest` now checks that the candidate sees VERIFIED after A verifies and B rejects. New `VerificationBugHuntR3IntegrationTest.aRejectionKeepsTheClaimsVerdictOutOfTheRejectingOrganisationsMatchingOnly`. |
| `V27__hiring_core_fixes.sql` | Comment updated. The backfill (one existing verification becomes its verifier's organisation row) agrees with the summary. |

### 3.2 Migrations

- `V28__hiring_core_fixes.sql` was renumbered to **`V27__hiring_core_fixes.sql`**: there was no V27, and nothing had
  been applied. The references in the R1 and R2 reports were updated.
- **New `V28__integration_fixes.sql`:**
  - `job_requirements.display_order`, backfilled in id order;
  - `refresh_tokens.family_started_at`, backfilled with the family's first `created_at`, then NOT NULL.
- `check_migrations_pg.py` applies V1–V28 with no gap warning.

### 3.3 Backend follow-ups from R5 and R6 (each with a test)

| Item | Change |
|---|---|
| R5-1 / R5-2 | `X-RecruitPro-Portal` is validated **before** the password change (`MeController`) and before the account deletion (`CandidatePrivacyController`). |
| R5-3 | **Every 429 carries `Retry-After`:** `TooManyRequestsException(message, retryAfterSeconds)` and the global handler. The seconds are real for the IP limiter, verification runs (10/24 h), reference requests (10/24 h), the account and MFA lockout, and the new OAuth-start limit. **Business caps are now 409 with codes:** `REFERENCE_LIMIT:` (5 open requests) and `SOURCE_LIMIT:` (30 sources). The candidate portal maps both. |
| R5-4 | The candidate department filter matches "contains", case-insensitively, with LIKE wildcards escaped. |
| R5-5 | OAuth callback: re-read; every path already 302s and clears the cookie (R3-B21). No change. |
| R5-6 | `PUT /candidates/me` uses `saveAndFlush`, so the returned `updatedAt` is fresh. |
| R5-7 | The data export writes explicit nulls for the profile and claims, through export-only records. |
| R5-8 (part) | `applicationId` added to the candidate work-sample and interview responses. |
| R6-B1 | Compile request limits: title 255, description 50,000, cut to the AI service's 200 / 20,000 on send. The portal slicing was relaxed. |
| R6-B2 | **Requirement order is stored** (`displayOrder`). A pure reorder saves only the order: no new requirements version, no audit row, no re-match. Reads order by `displayOrder, id`. The HR wizard treats a reorder as a change again. |
| R6-B3 | **MFA lockout is distinguishable.** Every `mfaToken` step answers 429 `MFA_LOCKED: … Try again in N minutes.` with `Retry-After`. A wrong code and an expired step stay 401. The HR login and accept-invite pages handle it. |
| R6-B4 | HR interview responses carry `scorecardEditable` (one predicate shared with submit and edit) and `applicationStatus`. The portal shows "Scorecard closed". |
| R6-B5 / R1-U8 | The audit CSV sends `Cache-Control: no-store`, `nosniff` and `X-Export-Truncated: true|false`; the last is exposed via CORS. The HR portal warns when the export was cut off. |
| R6-B7 | `PasswordPolicy.isValid` counts code points and agrees with the DTO regex. The HR checklist was mirrored. |
| R6-B8 | `totalHrUsers` documented as HR_MANAGER plus RECRUITER (no API change). |

### 3.4 Other "not fixed" items, now fixed

| Item | Change |
|---|---|
| R1-U1 | `POST /me/mfa/recovery-codes` needs `{code}` or `{password}`. A wrong value counts toward the lockout. The route is limited to 10 per 15 minutes per IP. The HR Security page asks for an authenticator code. |
| R1-U2 | **Absolute session lifetime:** `app.jwt.refresh-absolute-lifetime-days`, default 30 (`JWT_REFRESH_ABSOLUTE_LIFETIME_DAYS`). `familyStartedAt` is copied on rotation. A new token never outlives the family's end. After the end, refresh answers 401 and the family is revoked as `SESSION_LIFETIME`. |
| R1-U3 | Access tokens carry `iat_ms`. The password-change check compares at millisecond precision; older tokens fall back to seconds. |
| R1-U6 | Organisation sign-up retries a slug collision, up to 3 times, each attempt in its own transaction. |
| R1-U7 | `BootstrapRunner` tolerates a concurrent instance creating the bootstrap data. |
| R2-U3 (part) | ai-service: an OTHER requirement with a linked interview or work-sample rating is shown as MATCHED, with the evidence and its tier. It stays outside coverage and knockouts, so scores are unchanged. |
| R2-U9 | Candidate portal: a resume delete button, with confirmation and the 409 "in use" message. |
| R2-U10 | `app.ai-service.parse-timeout-ms` added to `application.yml` and `backend/.env.example`. |
| R3-B18 | GitHub OAuth `start` is limited to 20 per hour per candidate, counted in the database under the candidate lock: 429 with `Retry-After`. |
| R3-B19 | The unused `TokenResponse.scopes()` was removed. |
| R4-U8 | Certifications: a partial or in-progress stage the requirement does not name ("CA Inter", "CFA Level II", "Pursuing ACCA") gives UNCLEAR, never MATCHED. "ITIL Foundation" still meets "ITIL". |

### 3.5 Portals and design

- **(d) HR accept-invite with MFA.** The second sign-in step was extracted into `pages/auth/MfaSteps.tsx`, shared by
  the login and accept-invite pages.
  - Accept-invite now handles enrolment (QR code, confirm, recovery codes, then sign-in), verify-with-code, lockout
    and an expired step.
  - Tests: `AcceptInvitePage.test.tsx`.
- **(e) `design/components/ProvenanceLine.tsx`.** Only `http:`/`https:` hrefs become links. The change is identical
  in both portals (`check_design_sync.py` reports in sync). Test: `ProvenanceLine.test.tsx`.
- **(g)** `git update-index --chmod=+x backend/mvnw` changed the index only; it is now `100755`.

### 3.6 New findings of the integration pass

| ID | Sev | Finding | Fix |
|---|---|---|---|
| I-01 | Medium | Two problems in the claim columns. The candidate's claim status (and ledger tier) flipped to whichever organisation checked last: A verified, then B rejected, and the candidate saw "rejected" while A still counted the claim. The legacy overloads that read the global columns were still public and were used in tests. | Summary rule and overloads removed (3.1). |
| I-02 | Medium | After R3-B03, an organisation's own rejection of a claim no longer kept the agent verdicts on that claim out of that organisation's matching. | Per-organisation exclusion in `VerificationEvidenceContributor` (3.1). |
| I-03 | Low | Migration gap: V28 existed without a V27 (flagged by R7). | Renumbered (3.2). |
| I-04 | Low | `RefreshTokenService.issue(user, familyId)` restarted a family's absolute lifetime. Latent: nothing calls it yet. | An existing family keeps its first sign-in time. |

## 4. Remaining open items and recommendations

**Product decisions:**

| Item | Recommendation |
|---|---|
| R2-U1 fairness differencing | Allow only whole calendar months or quarters for `from`/`to`, or require ranges to differ by at least 30 days. Otherwise add small noise to the suppressed counts. |
| R2-U2 reveal, then reopen, of a REJECTED-from-SUBMITTED application | Keep it, but require a note on REOPEN after a reveal; it is already audited. |
| R1-U5 inviting an e-mail that already has an account | Always answer 201 "invitation sent". If the address has an account, mail that person asking them to contact the admin. This removes the account-existence oracle. |
| R2-U3 rest | Whether a linked rating on an OTHER requirement should enter coverage. That changes scores after interviews, so it is a product call. |
| R5-8 / R6-B6 notification links | Add a nullable `notifications.link`, filled by each producer with a path for the recipient's portal (`/jobs/{jobId}/applicants/{appId}?tab=…` for staff, `/applications/{appId}` for candidates). Return it in `NotificationResponse`; the portals prefer it and fall back to title matching. |
| R6-B9 | Optionally put `@JsonInclude(ALWAYS)` on response records that have nullable fields. The portal types are already optional-and-nullable. |

**Operations:**
- **R1-U4.** Behind a known proxy, set `SERVER_FORWARD_HEADERS_STRATEGY=native` and restrict
  `server.tomcat.remoteip.internal-proxies` to the proxy's address. Leave it off for the direct compose stack.
- **R1 note.** If the thread-contention tests flake on CI, add `;LOCK_TIMEOUT=10000` to the H2 URL.

**AI service (R4):**
- U1: lay out OCR boxes before grouping rows, for two-column scans.
- U2: per-label context words for ESCO.
- U3: a SCHOOL `degreeLevel`. This is a contract change on both sides, so plan it together.
- U4: NER for names outside the header.
- U5: LibreOffice runs in a container with no network route; keep it that way.
- U6: deliberate, no change.
- U7: read dates day-first or month-first from the job's country.
- U9: bump pytest to 9.x on its own.
- U10: the image build is covered by CI's `docker-stack` job.

**Verification design gaps (R3, documented):**
- in-memory per-instance limits, except the new OAuth-start limit, which is in the database;
- no orphan-file sweep;
- documents are stored unencrypted;
- the GitHub token is not revoked at GitHub when a source is revoked;
- a cancelled reference's unsent e-mail is still delivered;
- a recheck un-shares the verdict;
- readiness for VISIBLE is advice only.

**HR portal limits (R6):**
- in-app navigation away from a dirty live job is not blocked (needs a data router for `useBlocker`);
- decisions still in their Undo window are not sent on tab close (needs `keepalive` on `pagehide`);
- "new since last visit" is per browser (needs `lastViewedAt` in the API);
- there is no harness screen for a live-job pure reorder (unit tests cover it).

**Documentation drift** (outside this pass's files; for the docs owner):
- Handbook §3.x/§4.5: a recruiter's rejection is now per organisation. There are also the new limits: 30 sources,
  10 references per 24 h, 20 OAuth starts per hour, and the 409 codes.
- Handbook §2.5/§6: proficiency.
- WP-PLAT notes §1.2: the `rp_sso_state` cookie, the new rate limits, MFA 429 `MFA_LOCKED`, recovery-code step-up,
  the absolute session lifetime.
- STATUS section B #7: "claim rejected by a recruiter" is now per organisation.

## 5. Gate results (integration pass, final run)

| Gate | Result |
|---|---|
| `python3 tools/java_static_check.py backend/src` | 611 files, **0 errors** |
| `python3 tools/check_entity_schema.py` | 490 columns of 42 entities against 28 migrations, **0 errors, 0 warnings** |
| `python3 tools/check_migrations_pg.py` | **V1–V28** applied to PostgreSQL 16, no gap warning |
| `python3 tools/check_design_sync.py` | in sync |
| `python3 tools/sync_taxonomy.py --check` | exit 0 |
| `cd ai-service && python3 -B -m pytest -q && python3 -B -m eval.run --check` | **269 passed**; CHECK PASSED |
| `cd agent-service && python3 -B -m pytest -q && python3 -B -m eval.run --check` | **273 passed**; exit 0, mismatches 0, errors 0 |
| HR portal, clean `/tmp` copy: install `--frozen-lockfile`, build, test | build OK; **132 tests in 20 files** pass |
| Candidate portal, the same | build OK; **113 tests in 14 files** pass |
| Screenshot harness, touched and new screens, light and dark, 1440 and 390, `--axe` | HR 24 screens (96 captures), candidate 11 screens (44 captures). 0 page errors, 0 blank, 0 overflow, 0 axe violations of any impact, 0 unmatched API calls. A re-check of 5 screens by the coordinator gave the same result. PNGs are in the session outputs under `design-shots/integration/`. |
| Java compile | **Not possible here.** Two independent "act as javac" reviews of every changed or new test and main file found no errors and changed nothing. Run `./mvnw verify` first. |
| Repository hygiene | No `node_modules`, `.pnpm-store`, `dist` or harness `out/` in the repository. |

## 6. Fixes that also affect the demo branch `Kshitij` (port after the demo)

`Kshitij` has the same backend, agent and AI code for every package touched here except the redesign UI and the
V26 trust features. It has migrations V1–V25. So the claim-verification table (V27 here) and V28 must be ported
as that branch's next free versions, with the same SQL.

Frontend fixes **cannot be cherry-picked**, because `Kshitij` has the pre-redesign portals. Re-implement them there.

**Backend: port all of these.**
- **R1:** all 25 findings.
- **R2:** all 16, including the per-organisation claim table, plus the integration summary rule.
- **R3:** backend B01–B12, B14, B15, B17 (partly), B20, B21, plus B18 and B19 from this pass.
- **Integration pass:**
  - R5-1/2: portal header validated before the change;
  - R5-3: `Retry-After` and the 409 codes (portal wording must follow);
  - R5-4, R5-6, R5-7, R5-8 (part);
  - R6-B1, R6-B2 (requirement order; needs Kshitij UI wiring), R6-B3 (MFA 429; needs the Kshitij login page),
    R6-B4, R6-B5, R6-B7;
  - R1-U1 (recovery-code step-up; needs the Kshitij security page), R1-U2, R1-U3, R1-U6, R1-U7, R1-U8;
  - R2-U4, R2-U10; I-01, I-02, I-04.

**agent-service.** All AG findings except:
- AG-02 and AG-17 (`trust.py` / not on that branch), AG-24 and AG-25;
- AG-01 is partial: the origin check applies, the Credly trust does not.

**ai-service.**
- All R4 findings. R4-38's proficiency only affects matching on the redesign branch.
- Integration: R2-U3 OTHER display, R4-U8 partial stages.

**Infrastructure (R7).** R7-01, R7-02, R7-05, R7-06, R7-07, R7-08, R7-09, R7-10, R7-12, R7-13, R7-15, R7-16, R7-17
(`chmod +x`), R7-18, R7-19, R7-21, R7-26.

**Candidate portal (re-implement).**
- R5: B-01–B-05, B-08, B-16 (partly), B-19, B-31, C-01–C-04.
- Integration: the 409/429 limit messages and the resume delete button.

**HR portal (re-implement).**
- R6: R6-01, R6-10 (partly), R6-11, R6-12, R6-13, R6-15, R6-18, R6-20, R6-22, R6-24, R6-27, R6-31 (partly), R6-32,
  R6-40, R6-42, R6-44, R6-51 (partly).
- Integration:
  - accept-invite MFA (R1-M4 makes the server return a challenge, so without it Kshitij's accept-invite shows an
    error in MFA organisations);
  - MFA lockout 429;
  - recovery-code step-up;
  - `scorecardEditable`;
  - the audit truncation warning;
  - the requirement-order save.

**Not for `Kshitij`.** R3-B13, R3-B16 (V26 only), R6-45 and ProvenanceLine (the redesign's design system), and the
redesign-only portal fixes listed as "no" in the R5 and R6 reports.
