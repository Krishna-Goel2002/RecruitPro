# SITE-CAND notes: landing site and candidate-portal main pages

Work package SITE-CAND of [ASSISTANT_PLAN.md](../plan/ASSISTANT_PLAN.md) §4–§5, on branch `wip/assistant-and-polish`
(started in `ada5ff0`, finished on top of it, not committed). The HR-UX package owns `hr-frontend/` and the design
system (`hr-frontend/src/design` is the source of truth); this package only copies it.

## 1. Landing site (`site/`)

**Stack.** Vite 5 + React 18 + TypeScript, pnpm 10 with a lockfile, Vitest + Testing Library (jsdom). Built in
`node:22-alpine`, served by `nginx:1.27-alpine` on port 80 (compose: `5170:80`). No API calls, no forms, no cookies.

**Design system.** `site/src/design` is a byte-identical copy of `hr-frontend/src/design` (fonts via `@fontsource`,
brand presets via the brand Vite plugin, `theme-init.js` identical to the portals). Only landing-specific code lives
outside it: `src/sections/*` (one file per section), `src/site/*` (config, FAQ, ladder, reveal wrappers, `site.css`).

**Sections.** Header (sticky; section links with `aria-current` from an IntersectionObserver; theme toggle; "Find jobs"
and "HR portal"; below 1060 px a Menu disclosure that closes on Escape with focus return and on link choice) → Hero →
The problem → How it works → For HR teams → For candidates → Trust and privacy → FAQ → Footer (closing CTA, link
columns, System / Light / Dark switch, fictional-data and coverage disclaimer).

- **Hero:** one sentence ("See the evidence behind every application."), a lead paragraph, two "doors" (companies →
  `VITE_HR_URL/login`, candidates → `VITE_CANDIDATE_URL/jobs`), three plain promises, and a product preview built from
  the real components (`Avatar blind`, `ReqMarker`, `VerdictChip`, `CoverageMeter`, `EvidenceLadder`) with fictional
  data marked **Example data**. It plays one story once: a recruiter confirms a verified GitHub result and Java 17 rises
  from Resume to Corroborated. Decorative for assistive tech; a `figcaption` tells the same story.
- **How it works:** the five-step ladder drawn large with tier descriptions; verdict chips resolving one after another;
  the recruiter confirm/overrule step. Each step states its caveat ("Coverage … not a probability of success",
  "Not found … never lowers a ranking", "Identity is revealed on shortlist and the reveal is audited").
- **For HR teams:** ranking groups (bars grow, numbers count up), the assistant's confirm-before-change contract
  (message then proposed-action card), work-sample rubric anchors, structured-interview ladder, fairness report
  (adverse-impact ratios against the 0.80 line, labelled "a reason to look closer, not proof of bias"). The three
  narrow features share subgrid rows so their demos start on one line.
- **For candidates:** strengthen, consent, preview, judged on the work; a static example of the share/hide review.
- **Trust and privacy:** blind-first, consent, de-identified AI, audit trail, plus "What we do not claim".
- **FAQ:** WAI-ARIA accordion (button in heading, `aria-expanded`/`aria-controls`, labelled region; Enter/Space,
  ↑/↓ wrapping, Home/End; closed panels are `visibility: hidden`, out of the tab order).

**Honesty rules followed.** No customers, logos, testimonials, metrics or awards. Every number on the page is inside a
visual labelled "Example". Claims match the product as built (shadow mode, per-organisation confirmation, suppression
below five people, external models off by default).

**Motion** (all from `src/design/motion.ts`; every effect has a reduced-motion end state, no timers):
scroll reveal (`useReveal`, fade + 8 px rise, 70 ms sibling stagger; the hidden state is set only from JS when the
reveal will run, so no-JS and reduced-motion never hide content), ladder bars growing with a 90 ms stagger, bar grow and
`CountUp`, verdict chips resolving, hover lift on doors and feature icons, press feedback from the design-system
buttons, header surface on scroll, FAQ height via a grid-rows transition, theme switch via the design system.

**Accessibility.** Skip link to `<main id="main">`, banner/main/contentinfo landmarks, two labelled `<nav>`s, one `h1`,
each section `aria-labelledby` its `h2`. axe (wcag2a/aa, 21a/aa): 0 violations in light and dark at 1440 and 390,
including the open menu, open FAQ and the lower sections.

**Security headers** (`site/nginx.conf`, checked with a local nginx 1.18 build of the same config):
`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self';
connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'none'; object-src 'none'`, plus nosniff,
`X-Frame-Options: DENY`, referrer and permissions policies; hashed `/assets/*` are immutable for a year, `index.html`
and `theme-init.js` are `no-cache`; a missing asset is 404, any other path serves the page. `'unsafe-inline'` for
styles covers the brand-token `<style>` and React style attributes (same as the portals); there is no inline script.

**Portal URLs.** `VITE_HR_URL` and `VITE_CANDIDATE_URL` (Docker build args; defaults `http://localhost:5174` and
`:5173`). Anything that is not an absolute http(s) URL falls back to the default, so a typo can never produce a
`javascript:` or relative link (`site/src/site/config.ts`, tested).

## 2. Compose, CI, sync

- `docker-compose.yml`: service `site` (build args above, `5170:80`, `edge` network, explicit healthcheck mirroring
  the image's `HEALTHCHECK`).
- `.github/workflows/ci.yml`: `site` added to the web-app matrix (install --frozen-lockfile, test, build); the
  docker-stack job checks the site's CSP (`connect-src 'self';`), `#root` and `/theme-init.js`.
- `tools/check_design_sync.py`: `hr-frontend/src/design` → `frontend/` and `site/`; `public/theme-init.js` → both;
  `.dockerignore` → both; portal `nginx.conf`/`Dockerfile` → `frontend/` only (the site has its own stricter pair).
- `tools/ui-snapshots`: `--portal site` with `fixtures/site.mjs` (landing, FAQ open, phone menu, and three
  scrolled screens for `--viewport` phone captures, because full-page captures above ~8,192 px repeat the top).

## 3. Candidate portal pass (`frontend/`)

Consumes only design-system utilities that existed in both the older `frontend/src/design` copy and the
`hr-frontend` source (`stagger`, `useInView`, `CountUp`, `Stat`), so it built before and after HR-UX re-synced.

| Page | Change |
|---|---|
| Overview | "At a glance" strip: in progress, to do (work samples + interviews not yet over), to review, strong evidence (Corroborated or better), each a link to where it is handled; tiles stagger in, numbers count up, hover lift. Pure counts in `lib/summary.ts` (tested). Attention list staggers on first load. |
| Jobs | Status line adds "you have applied to N of these"; existing empty states (no results, no roles, past the end) kept. |
| Job detail + Apply | Match count counts up; the evidence ladders grow on first view. |
| Applications | Pipeline summary: one proportion bar in stage order (In review, Shortlisted, Interviewing, Selected, Closed; never red for Closed), widths always sum to exactly 100 (tested), sweeps in on first view, counted legend; `role="img"` with a full text label. |
| Application detail | "3 of 5 requirements verified · Must-haves: 3 of 3" headline with a per-requirement segment bar (verified filled, own claim outlined), rows stagger once. |
| Strengthen | Stats already count up; the "Waiting for your review" rows now stagger in once (`EnterList`). |
| Signed-out pages | `AuthLayout` (sign in, register, forgot/reset password, verify email) and the public referee page link to the landing site, "About RecruitPro", from `VITE_SITE_URL` (default `http://localhost:5170/`, validated like the site's URLs). |

`components/EnterList.tsx` + `lib/hooks.ts#useEnterOnce` stagger a list on first load only (never on refetch or
filter). Now that motion v2 has reached `frontend/src/design`, `useEnterOnce` can delegate to the design system's
`useStaggerOnce` (same contract).

## 4. Tests and gates (run on /tmp copies without node_modules in the repository)

| App | Result |
|---|---|
| `site` | `pnpm install --frozen-lockfile`, `pnpm test` 11 tests (structure and landmarks, every section, coverage caveat, CTA defaults, CTA env URLs, URL validation, FAQ mouse/Enter/Space, FAQ arrows/Home/End, header menu, reduced motion, full motion) plus the synced design-system tests: 4 files / 29 tests; `pnpm build` OK (JS ≈ 212 kB, 66 kB gzip) |
| `frontend` | after HR-UX's latest design sync: 20 test files / 139 tests, build OK (also green before that sync: 17 / 121) |
| `check_design_sync.py` | `design folders in sync (hr-frontend -> frontend, site)`; `site/src/design` re-copied from `hr-frontend/src/design` at the very end |

Screenshots (light/dark, 1440/390, axe): `outputs/design-shots/site/` and `outputs/design-shots/cand/`.

## 5. Not done / follow-ups

- `docker compose up` was not run here (no Docker in the build environment); the nginx config was exercised with a
  local nginx and the CI docker-stack job covers the image.
- The candidate portal's Dockerfile is shared with the HR portal, so `VITE_SITE_URL` is not a build arg yet; the
  default `http://localhost:5170/` is compiled in. Add `ARG VITE_SITE_URL` in `hr-frontend/Dockerfile` (HR-UX) and
  copy it, if the landing site moves.
- Full-motion screenshots only show the hero (sections reveal on scroll); motion was checked in tests with a fake
  IntersectionObserver and by reading the CSS.
