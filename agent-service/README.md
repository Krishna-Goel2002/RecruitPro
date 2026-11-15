# RecruitPro agent service

Stateless FastAPI service (port **8100**) that checks a candidate's claims against evidence: their own code
(GitHub, GitLab), their website, uploaded documents, Open Badges and references. It **reads and drafts**; the Spring
backend stores evidence and verdicts and decides what anyone sees (plan v2, sections 1, 2, 4).

Every result names its evidence. Each evidence item carries a URL (or file), retrieval time (UTC, `Z`), a
`sha256:` hash of the exact `snapshotText` returned, an excerpt (<= 1000 chars) and the snapshot (<= 262144 chars).

## Endpoints

| Method + path | Purpose |
|---|---|
| `GET /health` | public: `{status, version, llmProvider, demoFixtures}` |
| `POST /api/v1/ownership/check` | DNS TXT, well-known file, page code or profile-bio code -> `{proven, method, detail, evidence}` |
| `POST /api/v1/verify/code` | GitHub (REST, GraphQL with a token) / GitLab (REST v4) |
| `POST /api/v1/verify/web` | same-site crawl through the SSRF guard |
| `POST /api/v1/verify/document` | multipart `file` (PDF, DOCX, PPTX <= 15 MB) + `request` JSON |
| `POST /api/v1/verify/badge` | Open Badges 2.0 hosted assertions, OB 3.0 VC-JWT (EdDSA, ES256, RS256) |
| `POST /api/v1/verify/reference` | deterministic referee-answer rules |

All `/api/*` routes need `X-Internal-Service-Token: $AGENT_SERVICE_TOKEN`. Response shapes are in `app/models.py`
(camelCase JSON). Errors: 422 `{"detail": {"code", "message"}}` when a source cannot be read at all (`URL_BLOCKED`,
`ROBOTS_DISALLOWED`, `SOURCE_NOT_FOUND`, `UNSUPPORTED_DOCUMENT`, `BAD_CREDENTIAL`, `BAD_TARGET`, ...), 502 for upstream trouble
(`TIMEOUT`, `RATE_LIMITED`, `UPSTREAM_ERROR`, ...), 413 for oversized bodies, 401/503 for authentication.

## Rules (summary; the docstrings of each module state them in full)

- **Code** (`app/verifiers/code.py`): owned repositories, non-forks, forks only with own commits after the fork.
  SKILL `VERIFIED` when >= 2 repositories show the skill with authored share >= 0.5 and the account's commits in them
  span >= 30 days (committer dates, each clamped to the repository's creation date, so backdated history in a fresh
  repository does not count); `PARTLY_VERIFIED` when any repository shows it; else `NOT_FOUND`; other claim types
  `NOT_CHECKABLE`. Notes: `BULK_HISTORY`, `BACKDATED_HISTORY`, `FORK_ONLY`, `UNLINKED_AUTHOR`, `TEMPLATE_README`.
  Nothing is cloned or run;
  stars, watchers and followers are stripped from every payload on arrival.
- **Web** (`app/verifiers/web.py`): `VERIFIED` only when stated on a page with a matching authorship marker (JSON-LD,
  meta author, byline); `PARTLY_VERIFIED` when stated without one; `CONTRADICTED` only for an explicit conflicting
  year, with both quotes; hidden text never counts.
- **Document**: never `VERIFIED`; `PARTLY_VERIFIED` when stated; notes `AUTHOR_MISMATCH`, `METADATA_AFTER_CLAIM`.
- **Badge**: signature / hosted check, revocation, expiry, recipient and achievement match. Wrong recipient, bad
  signature, revoked or foreign-origin hosted assertion -> `CONTRADICTED`; Data Integrity proofs and unresolvable keys
  -> `NOT_CHECKABLE`; a self-certifying `did:key` issuer -> at most `PARTLY_VERIFIED`.
- **Reference**: CONFIRM from MANAGER/PEER/CLIENT on the employer's (non-free-mail) domain -> `VERIFIED`; see
  `app/verifiers/reference.py`.
- **Shared layer** (`app/verdicts.py`): ownership cap (unproven ownership => at most `PARTLY_VERIFIED`), probe questions,
  de-identification (`app/deidentify.py`), injection notes (`INSTRUCTION_TEXT`, `app/injection.py`), optional LLM
  summary through the citation validator (`app/citations.py`).

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `AGENT_SERVICE_TOKEN` | (required) | shared secret with the backend; startup fails when empty |
| `AGENT_ALLOW_NO_TOKEN` | `false` | tests only: allow running without a token |
| `LLM_PROVIDER` | `none` | `none`, `anthropic`, `openai_compatible` (only writes `summary`) |
| `LLM_MODEL`, `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL`, `OPENAI_API_KEY`, `LLM_TIMEOUT_SECONDS` | | as in ai-service |
| `AGENT_DEMO_FIXTURES` | `false` | serve SYNTHETIC recorded responses for `demo-` logins/hosts (local demo only) |
| `AGENT_FETCH_TIMEOUT_SECONDS` | `10` | per request |
| `AGENT_REQUEST_DEADLINE_SECONDS` | `50` | per verification (the backend waits 60 s) |
| `AGENT_USER_AGENT` | `RecruitProVerifier/1.0 (+contact)` | sent on every fetch |
| `GITHUB_API_URL`, `GITHUB_GRAPHQL_URL`, `GITLAB_API_URL` | public services | API bases |
| `GITHUB_TOKEN` | unset | optional server token for GitHub rate limits when the candidate gave none |
| `AGENT_MAX_DOCUMENT_BYTES` | `15728640` | document upload cap |

## Run, test, evaluate

```bash
pip install -r requirements-dev.txt
AGENT_SERVICE_TOKEN=dev-token uvicorn app.main:app --port 8100
python -m pytest -q                 # no network: MockTransport, fake resolvers, real DNS is refused
python -m eval.run --check          # synthetic gold set; fails if FCR > 0.02 or kappa < 0.70
python ../tools/sync_taxonomy.py    # re-vendor the skills taxonomy from ai-service (a test fails on drift)
```

The eval set is synthetic (recorded-style API payloads, generated pages and documents, badges signed with keys made at
run time). Synthetic evidence is far cleaner than the real web; the backend's shadow-mode labelling must confirm the
numbers on real data before an organisation switches to `VISIBLE`.

## Security model

- **Internal only.** Pure-ASGI middleware checks the token before any body is read; JSON bodies are capped at 1 MB,
  documents at 15 MB.
- **SSRF guard** (`app/fetch/ssrf.py`, `app/fetch/client.py`): http/https on ports 80/443 only, no credentials in
  URLs, internal host names refused, every resolved address must be public (private, loopback, link-local, CGNAT,
  multicast, reserved, documentation, metadata, IPv4-mapped/NAT64/6to4/Teredo blocked). The connection goes to the
  checked IP (Host header and TLS SNI keep the name), so DNS rebinding between check and connect is impossible; every
  redirect hop (<= 5) is re-checked. Timeouts, decoded-byte caps, content-type allow-list, robots.txt for crawls, no
  cookies, no proxies from the environment, no JavaScript.
- **Fetched content is data.** HTML is parsed with the standard-library parser; no script runs. Instruction-like text
  produces an `INSTRUCTION_TEXT` note and never changes a verdict. The LLM (optional) receives de-identified evidence as
  JSON in the user message, a constant system prompt and no tools; its sentences survive only with verbatim quotes.
- **Badges**: algorithms fixed by issuer key type (no `none`, no HMAC), embedded `jwk` headers ignored, `jku` honoured
  only on the issuer's origin, `did:web` documents must carry the issuer's id, RSA keys >= 2048 bits.
- **Documents**: no rendering or macros; zip-bomb and page caps.
- **Privacy**: reason texts, notes and probe questions are de-identified; the service is stateless and keeps nothing.
- **Demo fixtures** are synthetic and answer every ownership challenge for `demo-` identifiers: never enable
  `AGENT_DEMO_FIXTURES` outside local demos.
