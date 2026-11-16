# Security note: development secrets published in the repository (17 Nov 2026)

## What GitGuardian found

GitGuardian flagged a "generic encryption key" in the GitHub repository. A full scan of every commit on every branch
(`git grep` over all 23 commits, plus `detect-secrets` on both branch heads) found these committed secret values:

| Value (where) | What it protected | Real data at risk? |
|---|---|---|
| `APP_ENCRYPTION_KEY` default in `docker-compose.yml` | AES-256-GCM key for OAuth tokens, MFA secrets and SSO PKCE verifiers in the local Docker stack | Only local demo data |
| `APP_ENCRYPTION_KEY` in `.github/workflows/ci.yml` (smoke job) | Throw-away CI database | No |
| Test encryption key in four backend tests | In-memory H2 test database | No |
| `JWT_SECRET`, `AI_SERVICE_TOKEN`, `AGENT_SERVICE_TOKEN` defaults in `docker-compose.yml`; CI `JWT_SECRET`; a README example token | Local Docker stack / CI | Only local demo data |

The scan found **no** private keys, cloud credentials, GitHub/OpenAI/Anthropic tokens, real passwords, `.env` files or
other user secrets in the history. Other scanner hits are false positives: character alphabets (Base32, recovery
codes), RFC 6238 test vectors, fake tokens in unit tests, `pnpm-lock.yaml` integrity hashes, the documented demo
password `ChangeMe-123!`, and the agent service's synthetic demo badge issuer (derived from a public seed and used only
for `demo-` hosts when `AGENT_DEMO_FIXTURES=true`).

None of the published values was ever used in a production deployment. They were development defaults by design, but
a published key must be treated as compromised, and a default secret invites someone to deploy with it.

## What changed (branch `Kshitij-redesign`)

1. **No default secrets.** `docker-compose.yml` requires `JWT_SECRET`, `AI_SERVICE_TOKEN`, `AGENT_SERVICE_TOKEN` and
   `APP_ENCRYPTION_KEY` from `.env` and stops with a clear message when one is missing.
2. **Generators.** `tools/generate_env.py` and `tools/generate-env.ps1` create the git-ignored `.env` with fresh values
   from the OS cryptographic random generator, never print them, keep existing values, and rotate them with
   `--force` / `-Force`.
3. **Published values are refused at start-up.**
   - The backend's `PublishedSecretsGuard` stops the application when any of the four secrets equals a published value
     (all profiles except `test`).
   - The AI service and the agent service do the same for their tokens.
   - Only SHA-256 hashes of the published values are stored, so the values are not repeated in the code.
4. **CI** generates its secrets per run (`openssl rand`, masked in logs) or runs `tools/generate_env.py`; nothing is
   stored in the workflow file.
5. **Tests** use an all-zero key, which is obviously not a secret and is itself on the refused list.
6. **Docs** no longer print the old values.

## What you should do

1. **Generate your `.env`:** `python tools\generate_env.py` (or `.\tools\generate-env.ps1`).
2. **Reset your local stack once.** Data encrypted with the old key, such as MFA enrolments, can't be read with the new
   one:

   ```powershell
   docker compose down -v
   docker compose up -d
   ```

   The demo data is seeded again.
3. **GitGuardian:** resolve the incident as *revoked*, because the key is refused at start-up and no longer used. You
   don't need to rewrite Git history: the value is worthless now. Rewriting would also break every clone and the
   open branches. If your organisation's policy still requires removal, use `git filter-repo --replace-text` on a
   fresh mirror clone, force-push all branches, and ask everyone to re-clone.
4. **Never** put real secrets in `docker-compose.yml`, workflow files, tests or docs. Use `.env` locally, and GitHub
   Actions secrets or your deployment platform's secret store elsewhere.
5. **Optional local guard:** install GitGuardian's `ggshield` and add its pre-commit hook (`ggshield install -m local`)
   to catch secrets before they are committed. `.gitguardian.yaml` in the repository root ignores the test folders,
   which only hold fake credentials.
