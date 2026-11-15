# WP-OPS notes: retention purge, AI-service image, network isolation, proficiency in matching

Branch `redesign`, 16 Nov 2026. Not committed. Scope: `backend/src/main/java/com/recruitpro/retention/**` (+ test),
`ai-service/`, `docker-compose.yml` (+ `docker-compose.ai-egress.yml`), small additive edits to `application.yml`,
`application-test.yml`, the three `.env.example` files, `README.md` and `docs/guides/RUN_AND_TEST_GUIDE.md`. No
migration was needed (no V27). The handbook (`docs/handbook/`) was not edited; the sections that are now out of date
are listed at the end.

## 1. Data-retention purge (backend)

**What.** `retention/RetentionPurgeJob` (`@Scheduled(cron = app.retention.cron, zone = "UTC")`, default 03:30 UTC
daily) calls `retention/RetentionPurgeService.purge()`. Each rule selects up to `batch-size` keys (oldest first), then
deletes or anonymises exactly those keys with the condition repeated, in its own short transaction
(`REQUIRES_NEW`); a rule repeats until a batch is not full or `max-batches-per-table` is reached (the rest follows on
the next run). Plain JDBC on the existing tables, so no entity or repository of another package changed.

| Rule (report key) | Removed | Kept |
|---|---|---|
| `user_tokens` | used or expired more than `token-days` (30) ago | unused and still valid; recently used |
| `refresh_tokens` | revoked or expired more than `token-days` ago | active; recently revoked |
| `background_jobs_done` | DONE, `updated_at` older than `job-done-days` (30) | PENDING, RUNNING (never touched) |
| `background_jobs_failed` | FAILED or DEAD older than `job-failed-days` (90) | |
| `notifications` | EMAIL rows SENT or SKIPPED, and read IN_APP rows, created more than `notification-days` (180) ago | unread inbox messages, PENDING and FAILED e-mails |
| `sso_login_states`, `verification_oauth_states` | used or expired more than `state-days` (1) ago | open states |
| `rate_limit_buckets` | windows that started more than `rate-limit-days` (1) ago | |
| `reference_requests_anonymised` | EXPIRED, CANCELLED or DECLINED requests last changed more than `reference-days` (365) ago: `referee_name` → `[removed]`, `referee_email` → `removed@retention.invalid`, `answers_json` and `referee_comment` → NULL; **the row stays** (verdict history, counts) | ANSWERED requests (their evidence and verdicts), anything younger |
| `audit_events` | older than `audit-days` (730) | everything when `audit-days: 0` |

After a run the counts are logged (`Retention purge finished: user_tokens=2, …`) and written as one audit event
`RETENTION_PURGE` (organisation NULL, actor `SYSTEM`, target type `RETENTION`, details = the counts, `audit_events=kept`
when audit retention is off, and `failed: …` for rules that threw). A failing rule is logged by exception class and
does not stop the others. An `AtomicBoolean` prevents overlapping runs in one instance; several instances may run at
the same time (statements are idempotent, keys are taken in ascending order).

Refresh tokens: `RefreshTokenService.rotate` refuses an expired token before it looks at revocation, and a token
revoked more than 30 days ago is always expired (lifetime 14 days), so deleting it changes no sign-in or
reuse-detection outcome.

**Config keys** (`application.yml`, `app.retention.*`; invalid values stop start-up):

| Key | Default | Env |
|---|---|---|
| `enabled` | `true` (test profile `false`) | `RETENTION_ENABLED` |
| `cron` | `0 30 3 * * *` (`-` disables the schedule) | `RETENTION_CRON` |
| `batch-size` / `max-batches-per-table` | 500 / 200 | |
| `token-days`, `job-done-days`, `job-failed-days`, `notification-days`, `state-days`, `rate-limit-days`, `reference-days` | 30, 30, 90, 180, 1, 1, 365 | |
| `audit-days` | 730 (0 = keep forever) | `RETENTION_AUDIT_DAYS` |

**Tests.** `backend/src/test/java/com/recruitpro/retention/RetentionPurgeIntegrationTest.java` (H2): every rule's
delete/keep boundary, reference anonymisation keeping the row, idempotent second run, batching and the per-run cap,
`audit-days: 0`, settings validation, scheduled job off in the test profile. The SQL of every rule was also run
against PostgreSQL 16 (pgserver) with the migrations V1–V25 applied. **Not compiled** (no JDK 21 here).

**Not covered (still kept until account deletion):** evidence snapshots and verification documents on disk,
verdicts, `job_match_runs`, applications, resumes, FAILED e-mails. A per-organisation or legal-hold setting does not
exist; the periods are global.

## 2. AI-service Docker image

- Base pinned to `python:3.11-slim-bookworm` (the floating `3.11-slim` tag moved to Debian trixie, where some
  package names differ). Build args `WITH_LIBREOFFICE=true`, `WITH_OCR=true`.
- `libreoffice-writer-nogui` with `--no-install-recommends`, `apt-get clean`, lists removed; `SOFFICE_PATH=/usr/bin/soffice`.
- OCR: `pip install -r requirements-ocr.txt` (RapidOCR on onnxruntime, which is the engine `app/pipeline/ocr.py`
  calls) plus `libgl1` and `libglib2.0-0`, which `opencv-python` needs at import time. **Tesseract is not installed**:
  nothing in the code calls it, so it would only add size. `OCR_ENABLED=false` stays the default (Dockerfile and
  compose `${OCR_ENABLED:-false}`).
- New `ai-service/.dockerignore` (tests, eval, caches, `.venv`, `.env` stay out of the image).
- Size: roughly 0.8–1.1 GB with both extras instead of about 250–300 MB (estimate; see `ai-service/README.md`,
  "Docker image"). `--build-arg WITH_LIBREOFFICE=false --build-arg WITH_OCR=false` gives the old image.

## 3. Compose network isolation

| Network | `internal` | Members | Purpose |
|---|---|---|---|
| `internal` | yes | postgres, mailpit, ai-service, backend | the backend's private services |
| `agent` | yes | backend, agent-service | the agent cannot reach postgres, the AI service or Mailpit |
| `egress` | no | backend | SMTP, GitHub OAuth, OIDC; publishes 8080 |
| `agent-egress` | no | agent-service | candidate URLs, GitHub/GitLab, badge issuers; publishes 8100 |
| `edge` | no | candidate-portal, hr-portal, mailpit | published ports only |

Docker publishes no ports for a container that is only on `internal: true` networks, so:

- **AI service**: only on `internal` (no egress, as designed); no host port. Health: `docker compose ps` (image
  `HEALTHCHECK`), the admin dashboard, or
  `docker compose exec ai-service python -c "import urllib.request as u; print(u.urlopen('http://127.0.0.1:8000/health').read().decode())"`.
  LLM mode needs egress: `docker compose -f docker-compose.yml -f docker-compose.ai-egress.yml up -d`.
- **PostgreSQL**: only on `internal`; no host port (`docker compose exec postgres psql …`). To use it from the host,
  a local `docker-compose.override.yml` can add `ports: ["127.0.0.1:5432:5432"]` and `networks: [internal, egress]`.
- **Agent service** 8100 and **Mailpit** 8025/1025 are now bound to `127.0.0.1`. Backend 8080 and the portals keep
  their published ports unchanged.
- Mailpit is also on `edge` only so its UI port can be published; it does not relay mail.

Validated with `yaml.safe_load` plus a script (every network referenced exists, every service with ports has a
non-internal network, the backend shares a network with each dependency). `docker compose config` was not available.

## 4. Requirement proficiency in matching (`evidence-ladder-4`)

`ai-service/app/matching/engine.py` `_apply_proficiency`, applied after the weakest-link tier is known, for SKILL
requirements only:

| Proficiency | MATCHED needs one of (for every skill the requirement names) | Otherwise |
|---|---|---|
| BASIC, INTERMEDIATE, none, unknown | (unchanged) | |
| ADVANCED | ≥ 36 months of relevant timeline roles (merged), tier ≥ CORROBORATED, or `advanced`/`expert`/`senior`/`lead` in the cited facts or a relevant role | UNCLEAR |
| EXPERT | ≥ 60 months, tier ≥ DEMONSTRATED, or `expert` | UNCLEAR |

- "Relevant role" uses the same relevance test as EXPERIENCE matching (skill held in the role, multi-word skill
  words present, or a practitioner title). A requirement without taxonomy skills (lexical fallback) can only use the
  tier or a level word.
- Skill names are not level words: mentions are removed before the search unless the surface is
  "<word> <canonical name>" ("Advanced Excel" counts; "Lead Generation", "Team Lead", "Advanced Cardiac Life Support"
  do not).
- The UNCLEAR explanation keeps the original evidence sentence and adds what was missing, e.g. "…depth was not
  established: Kafka (1 year in the resume timeline, 3 years needed); the evidence is at tier RESUME_STATED (depth
  needs CORROBORATED or higher) and states no level ('advanced', 'expert', 'senior', 'lead')." The hint asks to probe
  depth or assign a work sample. A satisfied rule appends a short reason ("Advanced proficiency: Python: 3.3 years in
  the resume timeline.").
- Never NOT_OBSERVED because of proficiency; NOT_OBSERVED and UNCLEAR results are untouched. A self-claim that says
  "expert" stays MATCHED at SELF_CLAIMED (claimed coverage only).
- `ALGORITHM_VERSION` and the `MatchResponse.algorithmVersion` literal are now `evidence-ladder-4`. The backend stores
  whatever the AI service returns; only the opt-in `AiLifecycleIntegrationTest` (real AI service) hard-coded the
  version and was updated. Mocked backend tests keep `evidence-ladder-3` as fixture data.

**Tests / eval.** `tests/test_matching.py::TestProficiency` (8 tests). `eval/proficiency.py`: 18 hand-labelled cases;
`eval.run` adds `proficiency_case_accuracy` (threshold ≥ 1.0) and `proficiency_invariant_violations` (threshold ≤ 0:
re-matching every synthetic SKILL requirement as ADVANCED and EXPERT may only turn MATCHED into UNCLEAR). Existing
thresholds were not changed. Informational: `proficiency_downgrade_share` ADVANCED 0.74, EXPERT 0.95 on the synthetic
set.

## 5. How to verify

| Check | Command | Result here |
|---|---|---|
| ai-service tests | `cd ai-service && python3 -B -m pytest -q -p no:cacheprovider` | 186 passed |
| ai-service eval | `cd ai-service && python3 -B -m eval.run --check` | CHECK PASSED (skill F1 0.998, kappa 0.993, NDCG 0.990, flip 0.0, AIR 1.0, proficiency cases 18/18, violations 0) |
| Java static | `python3 tools/java_static_check.py backend/src` | 0 errors |
| Entity ↔ schema | `python3 tools/check_entity_schema.py` | 0 errors, 0 warnings |
| Compose YAML | `python3 -c "import yaml; yaml.safe_load(open('docker-compose.yml'))"` | parses |
| Still to run | `cd backend && ./mvnw verify` (JDK 21); `docker compose build ai-service && docker compose up` | not run here |

On a Docker host: `docker compose up --build`; `docker compose ps` (ai-service healthy);
`docker compose exec ai-service sh -c 'soffice --version'`; the `/health` exec line above; from inside the AI
service, `python -c "import urllib.request; urllib.request.urlopen('https://example.com', timeout=5)"` must fail
(no egress), while the backend still sends mail to Mailpit and the agent service still fetches `demo-` fixtures and
real GitHub profiles.

## 6. Risks

- **Proficiency lowers coverage.** The requirement compiler maps "strong", "deep", "extensive" to ADVANCED and
  "expertise" to EXPERT, so compiled jobs will now show more UNCLEAR results and more applicants in
  MUST_HAVES_UNVERIFIED when such a requirement is a knockout. That is the intended conservative behaviour, but HR
  should review suggested proficiencies; the HR requirements editor does not yet explain the effect. Years of use and
  titles ("senior") are proxies, not proof of depth; existing match runs stay `evidence-ladder-3` until re-matched.
- **Retention deletes data.** Wrong periods delete audit history; defaults are conservative and invalid values stop
  start-up. Deployments with legal retention duties should set `RETENTION_AUDIT_DAYS=0` (or a longer period) before
  the first run. Notifications older than 180 days disappear from inboxes once read.
- **Image size and attack surface.** LibreOffice parses untrusted documents (non-root, 60 s timeout, throw-away
  profile, no egress). OCR adds onnxruntime/OpenCV. Package names were checked for bookworm but the image was not
  built here.
- **Networks.** Not exercised on a Docker host. Anyone who relied on host ports 8000 or 5432 (scripts, DB tools)
  must use `docker compose exec` or an override. An LLM provider for the AI service needs the override file.
  Docker Desktop on Windows supports `internal` networks and `127.0.0.1:` bindings.
- Java not compiled; SQL checked on PostgreSQL 16 only (H2 via the integration test once `mvnw verify` runs).

## 7. Handbook sections now out of date (not edited)

`01-architecture-and-platform.md` 1.1 (ports), 1.2 ("compose defines no networks"), 1.3, 1.4 (`SOFFICE_PATH`,
`OCR_ENABLED`, new `RETENTION_*`), 5.1 and 5.4 ("never deleted"), 5.2 (new scheduler); `02-hiring-core-and-ai.md`
2.5 ("proficiency not implemented"), 6 (`evidence-ladder-3` → 4) and the gap table entry for proficiency.
