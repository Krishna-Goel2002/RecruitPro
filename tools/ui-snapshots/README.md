# ui-snapshots

Headless screenshots of both portals against **fixture API answers**, so the redesign can be checked screen by screen without a backend. It also reports crashes, horizontal overflow and (optionally) WCAG 2.1 AA violations found by axe-core.

```
tools/ui-snapshots/
├─ snap.mjs              the harness (static server + Chromium + /api/v1 interception)
├─ lib/mock.mjs          envelope, paging and relative-time helpers
├─ fixtures/candidate.mjs  routes + screen list for frontend/
├─ fixtures/hr.mjs         routes + screen list for hr-frontend/ (roles: HR_MANAGER default, ADMIN, INTERVIEWER)
└─ out/                  screenshots + report.json (git-ignored)
```

## Run it

```bash
# 1. build the portal you changed
cd frontend && pnpm install --frozen-lockfile && pnpm build && cd ..
# 2. install the harness once
cd tools/ui-snapshots && npm install && npm install --no-save axe-core
# 3. capture (light + dark, 1440x900 and 390x844, full page, reduced motion)
node snap.mjs --portal candidate --dist ../../frontend/dist --theme both --axe
node snap.mjs --portal hr --dist ../../hr-frontend/dist --theme both --axe
```

On Windows or macOS, point it at an installed browser: `CHROME_PATH="C:\Program Files\Google\Chrome\Application\chrome.exe"` (Edge works too). Without `CHROME_PATH` it uses `@sparticuz/chromium`, which only runs on Linux.

| Flag | Meaning |
|---|---|
| `--portal candidate\|hr` | fixture set (required) |
| `--dist <dir>` | the built `dist/` (required) |
| `--out <dir>` | output folder (default `out/<portal>`) |
| `--theme light\|dark\|both` | emulates `prefers-color-scheme` **and** sets `localStorage['theme-preference']` |
| `--widths 1440,390` | viewport widths (height 900 desktop, 844 phone) |
| `--only a,b` | capture only these screen names |
| `--viewport` | viewport-only shots instead of full page |
| `--motion` | allow animations (default emulates `prefers-reduced-motion: reduce` so pixels are stable) |
| `--axe` | run axe-core (tags wcag2a, wcag2aa, wcag21a, wcag21aa) on every capture |

Files are named `<screen>--<theme>--<width>.png`. `out/<portal>/report.json` lists, per capture: page errors, `blank` (nothing rendered in `#root`), `overflowX` and the first elements that stick out of the viewport (elements inside a scroll container are ignored), and axe violations. **The process exits 1** if any capture crashed, overflowed, or has a serious/critical axe violation — use it as the acceptance gate for each redesigned screen.

## Design-system screen

Both fixture files have a `design-system` screen (`/__design`, anonymous): the living style guide from `src/design/gallery`. Capture it whenever `src/design` changes:

```bash
node snap.mjs --portal candidate --dist ../../frontend/dist --theme both --axe --only design-system
node snap.mjs --portal hr --dist ../../hr-frontend/dist --theme both --axe --only design-system
```

Known limitation: with `@sparticuz/chromium`, full-page captures taller than about 8,192 px repeat the top of the page after that point (the 390 px gallery is about 13,000 px tall). Everything above that line is accurate; for the rest, inspect sections with element screenshots or `--viewport`.

## How the mocking works

The bundle calls `VITE_API_BASE_URL` (default `http://localhost:8080/api/v1`). Puppeteer intercepts any request whose path contains `/api/v1/`, answers CORS preflights, and replies with the backend envelope `{ success, message, data }`. Session restore works because `POST /auth/refresh` returns a session from the fixture; screens marked `anonymous: true` get a 401 refresh instead. Every other request to a third-party origin is aborted (the production CSP forbids them anyway). Unanswered API calls are printed at the end — add a route for each.

Adding a screen:

```js
// fixtures/hr.mjs
{ name: 'job-fairness', path: '/jobs/101?tab=fairness' },
{ name: 'applicants-error', path: '/jobs/101', override: [route('GET', '/hr/jobs/(\\d+)/applicants', fail(503, 'Unavailable'))] },
{ name: 'reveal-dialog', path: '/jobs/101/applicants/3014', action: clickText('Reveal identity') },
{ name: 'admin-overview', path: '/admin', role: 'ADMIN' },
// loading state: hold the answer and capture before network idle
{ name: 'applicants-loading', path: '/jobs/101', waitUntil: 'domcontentloaded', settle: 1200,
  override: [route('GET', '/hr/jobs/(\\d+)/applicants', delayed(20000, page([], { size: 25 })))] },
```

Screen options: `path`, `anonymous`, `role` (HR), `override` (routes tried first), `action(page)` (runs after load, e.g. open a dialog), `waitUntil` (default `networkidle0`), `settle` (ms to wait after load, default 500).

Fixture shapes follow `frontend/src/api/types.ts` and `hr-frontend/src/api/types.ts`. Coverage values are percentages (0–100) on the wire. All people and organisations are fictional.

## Baseline

`docs/design/screenshots/before/` holds the state of both portals on 16 Nov 2026 (light theme, 128-colour PNGs). In that baseline `/resumes` and `/verification` render blank: see UX_REVIEW.md, finding C-0.
