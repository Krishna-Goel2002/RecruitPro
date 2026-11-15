# WP-AG notes: agent-service

Date: 2026-11-15 · Contract: [IMPLEMENTATION_PLAN_V2.md](IMPLEMENTATION_PLAN_V2.md) sections 1, 2, 4.

## What was built

A stateless FastAPI service, `agent-service/`, on port 8100. It verifies claims against code (GitHub and GitLab),
websites, documents, Open Badges and references. It can be deployed on its own, and its patterns are copied from
ai-service: pure-ASGI token middleware, settings read from the environment, and a pluggable LLM provider with
`none` as the default.

| Area | Files |
|---|---|
| App, auth, config | `app/main.py`, `app/core/{auth,config,clock,budget}.py`, `app/api/{routes,deps}.py` |
| Contract models | `app/models.py` (pydantic, camelCase) |
| Evidence | `app/evidence.py`: hash, truncation, excerpt and timestamps for every item |
| Shared verdict layer | `app/verdicts.py` (ownership cap, probes, notes, finalize), `app/deidentify.py`, `app/citations.py`, `app/injection.py`, `app/llm/{provider,summary}.py` |
| Guarded fetching | `app/fetch/{ssrf,client,robots,html}.py`, `app/extract.py` (PDF/DOCX/PPTX) |
| Code hosts | `app/sources/{common,github,gitlab}.py`, `app/manifests.py`, `app/skills.py` |
| Verifiers | `app/verifiers/{code,web,document,badge,reference,ownership,common}.py`, `app/badges/{keys,status}.py` |
| Taxonomy | `app/taxonomy/` (vendored), `tools/sync_taxonomy.py` (`--check` for CI) |
| Demo | `app/demo/fakes.py` (MockTransport upstreams), `app/demo/fixtures.py` (synthetic `demo-` data) |
| Eval | `eval/{cases,builders,metrics,run}.py` |
| Tests | `tests/` (161 tests) |
| Packaging | `Dockerfile` (python:3.12-slim, non-root), `requirements*.txt` (pinned), `.dockerignore`, `.gitignore`, `README.md` |
| Wiring | `docker-compose.yml` (service `agent-service`), `.github/workflows/ci.yml` (job `agent-service`), `.env.example` |

## Endpoints

- `GET /health` is public and returns `{status, version, llmProvider, demoFixtures}`.
- `POST /api/v1/ownership/check`, `/verify/code`, `/verify/web`, `/verify/document` (multipart `file` + `request`),
  `/verify/badge` and `/verify/reference` all require `X-Internal-Service-Token`.
- The token is checked before the body is read.
- JSON bodies are limited to 1 MB and documents to 15 MB + 64 KB of multipart overhead.
- Startup fails when `AGENT_SERVICE_TOKEN` is empty, unless `AGENT_ALLOW_NO_TOKEN=true` (meant for tests only).

## Rules as implemented

### Evidence items

- `contentHash` is `sha256:` + the hex digest of the UTF-8 `snapshotText`. The hash is taken after normalisation and
  after truncation to 262144 characters.
- `retrievedAt` uses `...sssZ`. Excerpts are at most 1000 characters, keys at most 40.
- Item keys:
  - code: `profile`, `repo-N`, `commits-N`, `manifest-N-M`, `readme-N`
  - web: `page-N`
  - document: `document-1`
  - badge: `badge-1`, `badge-class`, `issuer`, `issuer-key`
  - reference: `reference-N`
  - ownership: `ownership-1`

### Code

**Which repositories count**

- Only repositories the login owns are read (`type=owner`, or `ownerAffiliations: OWNER`).
- Forks count only through the user's own commits made after the fork was created. A fork with none is ignored
  and gets the `FORK_ONLY` note.

**What is read per repository**

- Languages, counted when they are 5% or more of the bytes.
- Up to 4 manifests (all ten types in the contract), topics and the README.
- The latest 100 commits.
- Contributor statistics: these give the authored share. When GitHub answers 202, the share comes from the commit
  sample instead.

GraphQL is used for the inventory when there is a token (the candidate's, or `GITHUB_TOKEN`). That saves about 3
REST calls per repository.

**SKILL verdicts**

- `VERIFIED`: at least 2 strong repositories (authored share ≥ 0.5, own commits, not `BULK_HISTORY`), and the
  user's commits in them span at least 30 days, combined.
- `PARTLY_VERIFIED`: any considered repository shows the skill.
- `NOT_FOUND`: no considered repository shows the skill.
- Other claim types get `NOT_CHECKABLE`.

**Integrity notes and limits**

- Notes: `BULK_HISTORY`, `FORK_ONLY`, `UNLINKED_AUTHOR`, `TEMPLATE_README`.
- Popularity fields (stars, watchers, forks counts, followers and similar) are stripped from every payload as soon as
  it is parsed.
- Budget: `maxRequests` and a 50 s deadline. When either is hit, the result sets `usage.truncated` and the warning
  `BUDGET_TRUNCATED`.

### Web

**Crawl**

- Same-site breadth-first crawl (`www.` is ignored). Paths such as about, cv, portfolio and projects are fetched first.
- Limits: `maxPages` pages and `maxBytes` decoded bytes in total.
- Content types: HTML, plain text and PDF.
- Hidden text is kept in the snapshot but never counts as a statement.

**Authorship marker**

A page has a matching marker when one of these names on the page matches the owner name:

- JSON-LD `Person` or `author`
- meta author
- a byline (class, `rel` or `itemprop`, or a "By X" line)
- the author in PDF metadata

The owner name is `holderName` if given. Otherwise it is the start page's JSON-LD `Person`, or else its first author
marker.

**Verdicts**

- `VERIFIED`: the claim is stated on a page that has a matching marker. The ownership cap still applies.
- `PARTLY_VERIFIED`: the claim is stated, but not on a page with a matching marker.
- `CONTRADICTED`: the claim is stated nowhere, but a line has all of the claim's significant tokens with a different
  year. Two reasons are returned: one quotes the claim, the other quotes the page.
- `NOT_FOUND`: anything else.

### Document

- Metadata read: author, last modified by, created, modified, producer.
- `PARTLY_VERIFIED` when the claim is stated, otherwise `NOT_FOUND`. A document never yields `VERIFIED`
  (`ownershipProven = false`).
- Notes:
  - `AUTHOR_MISMATCH`: no shared name token with `holderName`.
  - `METADATA_AFTER_CLAIM`: the file was created in a year after the year the claim states.
- A document with no extractable text gets the warning `NO_TEXT`.

### Badge

**Checks**

- **OB3 VC-JWT**
  - Algorithms: EdDSA, ES256 or RS256, fixed by the issuer key type.
  - Rejected outright: `none` and HMAC.
  - Key sources:
    - `did:key` (Ed25519)
    - `did:web` (the document `id` must equal the DID; the method is chosen by `kid`)
    - JWKS at the issuer's origin (`jku` is accepted only on that origin)
  - Timing: `exp`/`validUntil` and `nbf`/`validFrom`.
  - Revocation: `StatusList2021Entry`/`BitstringStatusListEntry`. A JWT list is verified with the issuer key; a JSON
    list is used but flagged `STATUS_LIST_UNVERIFIED`; any other status type gets `REVOCATION_NOT_CHECKED`.
- **OB2 hosted**
  - The assertion is re-fetched from its `id`, and a pasted copy must match the hosted one.
  - The badge class and issuer are fetched.
  - The assertion's origin must equal the issuer's origin or appear in `allowedOrigins`.
  - `revoked` or HTTP 410 counts as revoked.
- Recipient matching: hashed (`sha256$` or `md5$`, with salt) or plain e-mail against `holderEmail`, and name
  identities against `holderName`.

**Verdict per claim**

Each claim is first matched to the achievement by taxonomy skill or token overlap. Claims that don't match get
`NOT_FOUND`.

- `VERIFIED`: everything checks out and the issuer is bound to a domain.
- `PARTLY_VERIFIED`: expired or not yet valid.
- `PARTLY_VERIFIED` with note `ISSUER_NOT_DOMAIN_BOUND`: a `did:key` issuer.
- `CONTRADICTED`: wrong recipient, bad signature, revoked, foreign origin, or a pasted copy that differs from the
  hosted assertion.
- `NOT_CHECKABLE`: Data Integrity proof ("proof type not supported yet"), unresolvable key, signed OB2, or an
  unsigned VC.
- When the recipient can't be compared, `ownershipProven` is false and the cap makes the verdict at most
  `PARTLY_VERIFIED`.

### Reference

Rules are as in the contract. Two refinements:

- A referee e-mail on a subdomain of the employer domain counts.
- A free-mail domain (a list of about 50) never counts, on either side.

A claim the referee did not answer gets `NOT_FOUND`.

### Ownership check

- Code format: 8–64 characters of `[A-Za-z0-9_-]`. This stops an empty code from matching anything.
- Methods:
  - DNS TXT: an exact `recruitpro-verification=<code>` record
  - well-known file: https, robots.txt skipped
  - page code: the raw page body contains the code (robots.txt respected)
  - profile bio: GitHub or GitLab bio contains the code
- A failure returns `proven:false` with a reason. The evidence item is returned only when `proven` is true.

### Shared layer (`finalize`)

1. **Injection screen.** Every snapshot is scanned. A hit adds the `INSTRUCTION_TEXT` note naming the items; it
   never changes a verdict.
2. **Ownership cap.** This is the only place it is applied. A per-claim override exists for references.
3. **Source notes.** Notes are attached to every verdict.
4. **Probe questions.** Template-based, at most 500 characters, for `PARTLY_VERIFIED`, `NOT_CHECKABLE` and
   `CONTRADICTED`.
5. **De-identification.** Applies to reason texts and quotes, note texts and probes. It replaces logins, names, URLs,
   e-mails, hosts and repository names with "the account", "the candidate", "the site" and "repository A/B/…".
6. **Optional LLM summary.** The system prompt is constant, evidence is de-identified JSON in the user message, and
   the model gets no tools. Every entry then goes through the citation validator, which drops:
   - entries with no evidence keys, or with unknown keys
   - entries whose quote is not found verbatim in a cited snapshot (whitespace differences are ignored)
   - entries containing injection markers
   - entries whose quote would reveal an identity

### SSRF guard

- Only http/https on ports 80/443, with no user-info in the URL. Internal host names are refused.
- Every resolved address must be public. Blocked: private, loopback, link-local, CGNAT, multicast, reserved,
  documentation, benchmark and metadata addresses, and mapped, NAT64, 6to4 or Teredo wrappers of them.
- The connection goes to the checked IP. The Host header and TLS SNI keep the name, so DNS rebinding does not work.
- Every redirect hop is re-checked, up to 5.
- Also enforced: a 10 s timeout, a cap on decoded bytes, a content-type allow-list, robots.txt, no cookies and
  `trust_env=False`.

## Deviations from the contract

All of these are backwards compatible.

1. **Optional `holderName` on the web and reference requests.** On the web it is the owner name that authorship
   markers are matched against. On references it is used only to de-identify comment quotes. If it is absent, the web
   falls back to the start page's JSON-LD `Person` or first author marker.
2. **Web markers.** A page counts only if it carries its own matching marker. Being on an owned site is not enough.
3. **Code rule details.** The contract leaves these open:
   - The 30-day span is measured over the qualifying repositories combined.
   - `BULK_HISTORY` repositories still show a skill but do not count towards `VERIFIED`. This is a deliberate
     tightening against faked histories; the note itself never sets a verdict.
   - A language must be at least 5% of a repository's bytes to count.
4. **`did:key`-issued badges are capped at `PARTLY_VERIFIED`.** Anyone can mint a `did:key` and self-issue a
   correctly signed "AWS" badge, so `VERIFIED` would be a false-corroboration path. `did:key` signatures are still
   fully verified. The demo OB3 badge uses `did:web` instead.
5. **Badge failure mapping.** The contract only defines wrong recipient and unsupported proof:
   - bad signature, revocation, an OB2 assertion off the issuer's origin, or a pasted copy that differs from the
     hosted one → `CONTRADICTED`
   - unresolvable key, alg `none`/HMAC, signed OB2, unsigned VC → `NOT_CHECKABLE`
   - expired or not yet valid → `PARTLY_VERIFIED`
   - Extra note codes: `CREDENTIAL_INVALID`, `CREDENTIAL_UNCHECKABLE`, `ISSUER_NOT_DOMAIN_BOUND`.
6. **Error shapes, which the contract does not specify.**
   - 422 `{"detail": {"code", "message"}}` when a source cannot be read at all: `URL_BLOCKED`,
     `ROBOTS_DISALLOWED`, `SOURCE_NOT_FOUND`, `OFF_SITE_REDIRECT`, `HTTP_ERROR`, `UNSUPPORTED_DOCUMENT`,
     `DOCUMENT_UNREADABLE`, `BAD_CREDENTIAL`, `DNS_FAILURE`.
   - 502 for upstream trouble: `TIMEOUT`, `NETWORK_ERROR`, `UPSTREAM_ERROR`, `RATE_LIMITED`, `TOKEN_INVALID`,
     `TOO_MANY_REDIRECTS`, `BUDGET`.
   - 413 for an oversized document.
   - `/ownership/check` never errors on fetch problems; it answers `proven:false`.
   - **WP-VBE should map non-2xx responses to run `FAILED` with `error_code = detail.code`.**
7. **Integrity notes are attached to every verdict.** `VerifyResponse` has no top-level notes field.
8. **Reason quotes are de-identified,** so a quote can differ from the snapshot when it contained an identity. Summary
   quotes that would reveal an identity are dropped instead, so they stay verbatim.
9. **robots.txt.** A 5xx or network error is treated as disallow. Machine endpoints the candidate pointed at skip
   robots.txt: the well-known ownership file, badge assertions, DID documents, JWKS and status lists.
10. **GitLab** links no commits to accounts. Commits are attributed by author name, with the warning
    `GITLAB_NAME_ATTRIBUTION`.
11. **Demo fixtures echo the requested challenge code** for `demo-` targets: DNS TXT, the well-known file, the page
    and the bio. This is so demo ownership checks pass offline.
12. **Compose.** The agent's `LLM_PROVIDER` is fed from `AGENT_LLM_PROVIDER` (default `none`) so it does not follow
    ai-service's setting. `AGENT_DEMO_FIXTURES` defaults to `true` locally.
    **The backend's `AGENT_SERVICE_URL`/`TOKEN` environment variables are left to WP-VBE.**
13. **Metric definitions.**
    - FCR = predicted `VERIFIED` among claims whose gold verdict is not `VERIFIED`.
    - The share of `VERIFIED` predictions that are wrong (false discovery) is reported as well. It is not gated.

## Test and eval results (Python 3.10.12 sandbox)

- `cd agent-service && python3 -m pytest -q` → **161 passed** (about 4 s). No network: real `socket.getaddrinfo` is
  patched to fail in every test. Coverage:
  - token middleware
  - SSRF: address classes, redirects to private and metadata addresses, the DNS-rebinding pin, caps, robots,
    cookies, IPv6
  - every verifier's rules, including badge good, bad, expired, wrong-recipient and forged cases for EdDSA, ES256
    and RS256, keys generated at test time
  - the ownership cap for all 5 verifiers
  - de-identification, the citation validator, injection handling and the no-tools LLM request
  - budgets and truncation
  - demo fixtures
  - taxonomy drift
- `python3 -m eval.run --check` → exit 0:
  - 79 cases, 26 of them adversarial
  - 110 claims
  - **FCR 0.0** (adversarial subset 0.0), false-discovery share 0.0
  - **kappa 1.0**, agreement 1.0
  - support: VERIFIED 30, PARTLY 32, NOT_FOUND 31, CONTRADICTED 8, NOT_CHECKABLE 9
- `/health` responds through TestClient and through a real uvicorn start. Startup with an empty token exits non-zero.
- `python tools/sync_taxonomy.py --check` → in sync.
- `pyflakes` and `mypy app` are clean.

## Open risks

- **The eval is near-tautological.** The gold labels are written by the same author as the rules on clean synthetic
  data, so kappa 1.0 says little about real-world accuracy. Shadow-mode labelling in the backend (plan section 5)
  must provide the real numbers before an organisation goes `VISIBLE`.
- **Python 3.12 was not executed here.** The sandbox only has 3.10. The code avoids 3.11+ APIs (with a `tomli`
  fallback) and the CI job runs on 3.12. The Docker build was not run.
- **IP pinning has not been exercised against a live TLS server.** Pinning uses httpcore's `sni_hostname` extension,
  which is in the code path for 1.0.9. Pinning also means an egress HTTP proxy is unsupported (`trust_env=False`).
  Deployments that need a proxy would have to put the guard inside the proxy.
- **Issuer trust is not established.** An OB2 hosted badge or a JWKS/`did:web` issuer on a domain the candidate
  controls still verifies. Issuer names are shown in evidence; an issuer allow-list or reputation list is future work.
- **De-identification is lexical.**
  - Name parts of 3 or more letters are replaced everywhere, which can damage a quote ("Rao Institute" becomes "the
    candidate Institute").
  - Name parts that are also skill names (e.g. "Ruby") are kept.
  - References do not know the candidate's name unless `holderName` is sent.
- **GitHub contributor statistics often answer 202 on the first call.** The 100-commit sample is then used, which
  understates the authored share for long-lived repositories with many contributors.
- **Upstream contracts.** GitHub, GitLab and badge-issuer payload shapes come from public documentation and recorded
  fixtures, not live calls. Rate limits without a token (60 requests/h for GitHub) will truncate real runs; set
  `GITHUB_TOKEN` in production.
- **`AGENT_DEMO_FIXTURES=true` answers every ownership challenge for `demo-` identifiers.** It must be false outside
  local demos. The service logs a warning at startup when it is on.
