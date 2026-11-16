# Bug hunt R7: infrastructure, CI, configuration and documentation accuracy

Branch `Kshitij-redesign`, working tree of 16 Nov 2026 (not committed). Reviewer R7 of seven. Scope: `docker-compose*.yml`,
all Dockerfiles and `.dockerignore` files, both portals' `nginx.conf`, `.github/workflows/ci.yml`, `.env.example`
files, `.gitignore` / `.gitattributes`, `tools/*.py`, `README.md`, `docs/guides/RUN_AND_TEST_GUIDE.md`,
`docs/plan/STATUS.md` (top section), `backend/mvnw*`, `backend/.mvn/`; plus the infrastructure sections of
`docs/handbook/01-architecture-and-platform.md` and one line of `ai-service/README.md`, which described the
Dockerfiles and compose file wrongly.

**Environment limits.** No Docker, no JDK 21, no Maven Central, no Docker Hub here. Compose files were checked with
`yaml.safe_load` plus a semantic script (networks exist, published ports have a non-internal network, every
`service_healthy` dependency has a health check, every build arg is declared, env names match what each service reads).
The nginx template was rendered with the image's envsubst rule (only defined variables) and run under nginx 1.18
(the image uses 1.27) against a real `vite build` of each portal. Image tags were checked against the upstream
release pages on GitHub. CI was not executed; the new `repo-checks` and `docker-stack` jobs cover what could not be
run here.

## Findings

Severity: **High** = breaks the demo or hides failures; **Medium** = wrong behaviour or a trap someone will hit;
**Low** = hardening, reproducibility, small inaccuracies; **Info** = noted, no change.

| ID | Sev. | File | Problem | Fix | Verified how | Affects demo branch `Kshitij`? |
|---|---|---|---|---|---|---|
| R7-01 | High | `.github/workflows/ci.yml` | `push` only on `master`/`main`: pushes to `Kshitij` and `Kshitij-redesign` never ran CI, so the first real `mvnw verify` of plan v2 never happened automatically | Push on `master`, `main`, `Kshitij`, `Kshitij-redesign`; `workflow_dispatch`; per-ref concurrency; job timeouts | YAML parsed; trigger block inspected | Yes (same file there; backport) |
| R7-02 | High | `ci.yml` | No job built a Docker image or validated the compose files; the agent-service, new backend, LibreOffice/OCR AI and redesigned portal images had never been built anywhere | `repo-checks`: `docker compose config --quiet` for the base and the egress override. `docker-stack`: `docker compose up --build --wait`, then backend/agent/AI health, **AI egress must fail**, `soffice` present, portal CSP names the API origin, backend can write to the uploads volume, logs on failure, `down -v` | YAML parsed; every command checked against the compose file and images (no Docker here) | Yes (images never built there either) |
| R7-03 | Medium | `ci.yml` | No design-sync check in CI (the redesign depends on byte-identical `src/design`) | `repo-checks` runs `tools/check_design_sync.py` and `tools/sync_taxonomy.py --check` | Both run locally: exit 0 | No (no `src/design` on `Kshitij`) |
| R7-04 | Medium | `ci.yml` | Portals got Vitest from R5/R6 but CI only built them | `pnpm run --if-present test` before `pnpm build` (Vitest runs once under `CI=true`) | pnpm 10 `--if-present` semantics; current portals have no `test` script, so the step is a no-op until it exists | No |
| R7-05 | Low | `ci.yml` (`backend-smoke`) | Smoke DB `postgres:16` differed from compose (`pgvector/...`); smoke `needs: backend`, so one failing unit test hid whether the jar starts at all; `APP_ENCRYPTION_KEY` start-up validation never exercised; login without the portal header | Same image as compose; independent job; CI-only Base64 key (32 bytes); `X-RecruitPro-Portal: staff` like the HR portal | Key decodes to 32 bytes; header value from `auth/web/Portal.java` | Yes |
| R7-06 | Medium | `docker-compose.yml` | Backend waited only for `service_started` of ai-service and agent-service: on a cold start the first parse/verification calls and the admin health card hit services that were not listening yet | `condition: service_healthy` for both (their images have `HEALTHCHECK`) | Semantic script: both dependencies have a Dockerfile `HEALTHCHECK` | Yes |
| R7-07 | Medium | `docker-compose.yml` | Floating tags `axllent/mailpit:latest` and `pgvector/pgvector:pg16` (the latter moves Debian base with upstream, which can change libc collations under an existing volume) | `axllent/mailpit:v1.33.1`, `pgvector/pgvector:0.8.7-pg16` (the variant `pg16` points at today, so existing volumes keep their base) | Mailpit release v1.33.1 exists and its workflow pushes `axllent/mailpit:<tag>`; pgvector 0.8.7 README lists `0.8.7-pg16` | Yes |
| R7-08 | Medium | `docker-compose.yml`, portal `Dockerfile`s | `API_ORIGIN` (CSP `connect-src`) was only an image default: changing `VITE_API_BASE_URL` in compose without it blocks every API call by CSP | Compose passes `API_ORIGIN` as build arg and run-time env next to `VITE_API_BASE_URL`, with a "change both" comment; Dockerfile comment says so too | Semantic script: build args declared in the Dockerfiles; rendered template contains the origin | Yes |
| R7-09 | Medium | `frontend/nginx.conf`, `hr-frontend/nginx.conf` | A missing `/assets/*` file answered 404 **with `Cache-Control: public, max-age=31536000, immutable`**: a transient 404 during a deploy is cached for a year | `map $status` → year-long caching only for 200/304, `no-store` otherwise | nginx run: 200 and 304 immutable, 404 `no-store` | Yes (same nginx.conf) |
| R7-10 | Medium | both `nginx.conf` | No gzip: the 295 kB main bundle went out uncompressed (93 kB gzipped) | `gzip on` for CSS/JS/JSON/SVG/text, `gzip_vary`, min 1 kB | nginx run: `Content-Encoding: gzip` on `/`, SPA routes and assets | Yes |
| R7-11 | Low | both `nginx.conf` | Unhashed public files (`/theme-init.js`) had no `Cache-Control` (heuristic caching could keep an old theme script); `server_tokens` on; comment said only "Vite emits no inline scripts" | `location /` sends `no-cache` (revalidate) with the full header set; `server_tokens off`; comment documents theme-init.js, the brand `<style>`, self-hosted fonts and the caching rules | nginx run: `/theme-init.js` `no-cache`, `Server: nginx` without version; built `index.html` has no inline `<script>` and fonts are in `/assets/` | Partly (no theme-init.js there; gzip/tokens apply) |
| R7-12 | Low | portal `Dockerfile`s, `.dockerignore` | No `HEALTHCHECK` in the portal images; `.dockerignore` missed `.pnpm-store/`, `coverage/`, `*.tsbuildinfo` | busybox `wget` health check on `/`; ignores added; both portals byte-identical (now enforced by `check_design_sync.py`) | `check_design_sync.py` exit 0 | Yes |
| R7-13 | Low | `agent-service/Dockerfile` | `python:3.12-slim` follows the newest Debian release silently | `python:3.12-slim-bookworm` (like the AI image) | — | Yes |
| R7-14 | Low | `ai-service/Dockerfile` | `SOFFICE_PATH=/usr/bin/soffice` was set even for `WITH_LIBREOFFICE=false`, so the small image tried a missing binary on every `.doc` instead of reporting conversion unavailable; uvicorn sent a `Server` header (the agent hides it) | `SOFFICE_PATH` unset (config auto-detects `soffice` on `PATH`); `--no-server-header`; `ai-service/README.md` wording | `app/core/config.py` uses `shutil.which("soffice")` when unset | No (no LibreOffice there) / Yes (header) |
| R7-15 | Medium | `.gitignore` | `agent-service/.venv/` (created by guide 8.3) was not ignored: thousands of untracked files, easy to commit; also `tools/ui-snapshots/out/`, `coverage/`, `.mypy_cache/` outside the agent, and the guide's `services.txt` | Generic patterns (`node_modules/`, `.venv/`, `venv/`, `.pytest_cache/`, `.mypy_cache/`, `coverage/`, `*.tsbuildinfo`, `.pnpm-store/`, ...) | `git check-ignore -v` on each path | Yes |
| R7-16 | Medium | `.gitattributes` (new) | No line-ending rules: `mvnw.cmd` is stored LF (cmd.exe can mis-parse labels/GOTO in LF files); with `core.autocrlf=true` `mvnw` gets CRLF (`/bin/sh^M`) | `* text=auto`; `mvnw`, `*.sh`, Dockerfiles, nginx.conf, YAML `eol=lf`; `*.cmd`/`*.bat` `eol=crlf`; binaries marked | `git ls-files --eol`: index is all LF, so no renormalisation churn; attributes shown per file | Yes |
| R7-17 | Low | `backend/mvnw` | Stored as mode `100644`: `./mvnw` is "Permission denied" on Linux/macOS (CI does `chmod +x`) | Documented `chmod +x mvnw` in README and guide 4.1. **Owner action** (changes the index, so not done here): `git update-index --chmod=+x backend/mvnw` | `git ls-files -s` | Yes |
| R7-18 | Medium | `tools/check_migrations_pg.py` | False negative: a misnamed `.sql` (e.g. `V27_x.sql`, single underscore) was silently skipped, exactly as Flyway skips it, so the migration would never run and the check stayed green; duplicates stopped at the first; Flyway placeholders `${` not checked; temp server and data dir left behind | Pre-flight: misnamed files and placeholders are errors, all duplicates reported, `R__` noted, **version gaps warned**; server stopped and data dir removed | Synthetic names → expected errors/warnings; full run: 27 migrations applied, `WARN version gap: no migration for V27` | Yes (tool is the same there) |
| R7-19 | Low | `tools/check_entity_schema.py` | False negative: a field whose Java type has no rule (`UUID`, `byte[]`, `OffsetDateTime`, ...) was silently not type-checked | Reported as a warning naming the column; temp server cleaned up | 488 columns of 42 entities, 0 errors, 0 warnings (no unchecked types today) | Yes |
| R7-20 | Low | `tools/check_design_sync.py` | Treated any path without a suffix as a folder (a `Dockerfile` pair would be "missing folder"); did not cover nginx.conf/Dockerfile/.dockerignore | File/folder decided by the filesystem; the three serving files added to the pairs | Negative test on a copy: extra file, differing Dockerfile, missing .dockerignore all reported, exit 1 | No |
| R7-21 | Low | `tools/totp.py` | Traceback on a mistyped secret | Clear message, exit 2 | RFC 6238 vectors (T=59 → 94287082, T=1111111109 → 07081804); `otpauth://` URI accepted | Yes |
| R7-22 | Medium | `README.md` | No branch explanation; "V1–V25"; status block implied only `2fedafb` was uncompiled; second portal command `cd hr-frontend` ran inside `frontend/`; no PowerShell notes; config table missing `API_ORIGIN`, retention vars; demo identities `demo-analytics.example` / `demo-newco.example` missing | Branches table (`Kshitij` demo, `Kshitij-redesign`, `master`); status says no `mvnw verify` recorded after `9ad341a` on either branch and images not built; two terminals; PowerShell comments; offline demo identities table; gates add design/taxonomy sync and `pnpm run --if-present test`; CI described | Every path/command cross-checked (script + manual) | Yes (README differs per branch; this one now explains both) |
| R7-23 | High | `docs/guides/RUN_AND_TEST_GUIDE.md` | Written for `Kshitij` only: on the redesign branch several steps were wrong (6.5 "Draft requirements" button vs the wizard with automatic compiler; 6.12 "There is no QR code"; 6.16 connect flow; 6.20 admin tabs; 4.3 health list), and branch switching did not mention V26 | Header table for both branches; new **0.3 What is different on `Kshitij-redesign`**; **Redesign:** notes in 4.4, 6.2, 6.3, 6.5, 6.6 (keyboard triage + 5 s Undo), 6.12 (QR), 6.13, 6.15, 6.16 (Add-a-source drawer), 6.18 (`demo-newco.example` variant), 6.19, 6.20; 4.3 `soffice --version`; 8.4–8.6 current outputs, compose check, CI; 10: unhealthy-dependency, `/bin/sh^M`; 11: `down -v` when switching branches | UI labels taken from the source (`AddSourceDrawer.tsx`, `ApplicantsTab.tsx` shortcut sheet, `SecurityPage.tsx`, `AdminVerificationPage.tsx`); anchors and paths checked | Kshitij steps kept and marked |
| R7-24 | Medium | `docs/handbook/01-architecture-and-platform.md` | §1.1–1.3 said compose defines no networks, all ports published, image has no LibreOffice, mailpit tag unpinned, "uncommitted" compose additions | Rewritten for both branches (networks, published ports, health conditions, `API_ORIGIN`, `SOFFICE_PATH`, security table rows) | Cross-checked with the compose file | Branch-specific text added |
| R7-25 | Low | `docs/plan/STATUS.md` | Top said branch `Kshitij` only; WP-OPS/B2 "branch `redesign`, uncommitted"; "CI uses 3.12"; MFA "no QR code" as a general limit | Current-truth table for both branches, CI scope, commit ids, redesign row, Python versions per service, QR only missing on `Kshitij` | `git log Kshitij..Kshitij-redesign` | — |
| R7-26 | Low | `backend/.env.example` | `SSO_OIDC_SCOPES` read by `application.yml` but undocumented | Added (quoted, it contains spaces). `VERIFICATION_CONSENT_VERSION` deliberately left out (server-side consent versioning) | Script: every `${VAR}` in `application*.yml` vs the example | Yes |
| R7-27 | Low | `.env.example` | After using the egress override, a plain `docker compose up -d` silently recreates the AI service without egress | Documented `COMPOSE_FILE` (with `:` and the Windows `;` separator) | Compose `COMPOSE_FILE`/`COMPOSE_PATH_SEPARATOR` semantics | No (no override there) |
| R7-28 | Info | `docs/guides/RUN_AND_TEST_GUIDE.md` §8.3 / CI | Counts and outputs drift as the other reviewers change code | Wording now quotes the output format and per-branch counts measured on 16 Nov | — | — |

### For the coordinator (other reviewers' areas)

- **Migration gap:** `V28__hiring_core_fixes.sql` (untracked, another reviewer) exists without a `V27`. Flyway accepts
  gaps, but a `V27` added later is refused by `validate` on any database that already ran V28 (no `outOfOrder`).
  Either renumber to V27 or confirm V27 is coming in the same change. `check_migrations_pg.py` now warns about it.
- `docs/handbook/02-hiring-core-and-ai.md` §2.5/§6 (proficiency, `evidence-ladder-4`) is still stale
  (WP-OPS-NOTES §7); not infrastructure, left to the docs owner.
- Backport to `Kshitij` (same files there, low risk): R7-01, R7-05, R7-06, R7-07, R7-09, R7-10, R7-15, R7-16, R7-17.

## Checked and found correct (no change)

- **Env names, three ways:** every `environment:` key of the backend is read by `application*.yml`; every `${...}` in
  the compose file is in `.env.example` and vice versa; ai-service and agent-service variables match
  `app/core/config.py`. Shared tokens default to the same value on both ends.
- **Defaults:** `APP_ENCRYPTION_KEY` default decodes to exactly 32 bytes; `JWT_SECRET` default is 46 bytes (≥ 32 required
  by `JwtService`); an empty `APP_ENCRYPTION_KEY=` in `.env` falls back to the default (`:-`).
- **Volumes:** `/data/uploads` is created and owned by the non-root `recruitpro` user in the image, so a new named
  volume is initialised with that owner (copy-up); work samples (`/data/uploads/work-samples`) and verification
  snapshots (`/data/uploads/verification`) are subfolders of the same volume. CI's `docker-stack` job writes to it.
- **Networks:** postgres and ai-service only on `internal`; agent-service cannot reach either; every service with
  published ports has a non-internal network; the egress override merges to `[internal, ai-egress]`.
- **Images:** pinned bases (`maven:3.9-eclipse-temurin-21`, `eclipse-temurin:21-jre`, `python:3.11-slim-bookworm`,
  `node:22-alpine`, `nginx:1.27-alpine`); multi-stage builds copy only the artefact; non-root users in the backend and
  Python images; `curl` installed for the backend health check; `libreoffice-writer-nogui`, `libgl1`, `libglib2.0-0`
  are bookworm package names; `.dockerignore` files exclude `target/`, `node_modules/`, `dist/`, venvs, tests.
- **Portal build:** `pnpm install --frozen-lockfile && pnpm build` passes for both portals (working tree, pnpm 10.34,
  Node 22); built `index.html` has only external scripts (`/theme-init.js`, `/assets/index-*.js`); fonts are woff2
  under `/assets/`; SPA routes fall back to `index.html` with `no-cache` and the full header set.
- **Tools:** `check_design_sync.py` in sync; `sync_taxonomy.py --check` exit 0; `java_static_check.py` 604 files,
  0 errors; `check_entity_schema.py` 0/0; `check_migrations_pg.py` all 27 files apply (warning: V27 gap);
  `audit/probe_matching.py` and the agent `eval.run --check` run.
- **Secrets / hygiene:** no API keys, tokens or private keys in the tree or in history (pattern scan of `git log -p`);
  only documented dev defaults. No tracked build output, caches or `.env`; largest tracked file is
  `docs/design/name-options.html` (1.1 MB, intentional preview page).
- **CI versions:** JDK 21 Temurin, Python 3.11 (ai-service) / 3.12 (agent-service), Node 22, pnpm 10 match the
  Dockerfiles; actions pinned to major versions (`checkout@v4`, `setup-java@v4`, `setup-python@v5`,
  `pnpm/action-setup@v4`, `setup-node@v4`, `upload-artifact@v4`); Maven, pip and pnpm caches configured.
- **Info, not changed:** `backend/.mvn/jvm.config` sets `-Djava.io.tmpdir=target` (relative to `backend/`); it worked
  for the 15 Nov `mvnw verify`, and the Docker build uses the image's `mvn`, which does not read it. If an early build
  step ever fails with "No such file or directory" for a temp file, create `backend/target` first or drop the line.
