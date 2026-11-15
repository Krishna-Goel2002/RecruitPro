# Design system specification

**Status:** spec for implementation (two engineers, one per portal). **Date:** 16 Nov 2026.
**Applies to:** `frontend/` (candidate + referee) and `hr-frontend/` (staff). **Read with:** [UX_REVIEW.md](UX_REVIEW.md), [REDESIGN_PLAN.md](REDESIGN_PLAN.md), [NAMING.md](NAMING.md).

The product name, logo and accent colour are **not decided**. Nothing in this system may contain the name or a brand colour outside `src/design/brand/`. Section 2 explains the swap.

---

## 1. Principles

Each principle has a test that can be checked in review.

1. **Evidence first, chrome last.** The most important pixels on any screen are the evidence (requirements, tiers, verdicts, provenance), not containers, headers or decoration. *Test:* blur the screenshot; the darkest, highest-contrast marks should be data, not borders, icons or buttons.
2. **One surface per region.** Group with space and hairlines, not boxes. A bordered box is allowed only for something independently actionable or floating: dialogs, drawers, menus, dropzones, selectable rows, the danger zone. *Test:* no bordered element sits inside another bordered element, except a form control inside a dialog.
3. **Meaning is never colour alone.** Every status carries a word, and every verdict and tier also carries a shape (icon, fill level, dash). *Test:* the greyscale screenshot remains fully readable.
4. **Calm by default, precise on demand.** Show the summary (chip, strip, number). Show the why (reasons, quotes, fingerprints) one interaction away: an expanding row, a drawer or a tooltip. Never more than one level of disclosure.
5. **Motion explains causality.** Things move only when state changes or to show where something came from or went. A tier rising, a verdict arriving and a rank moving are the moments worth animating. *Test:* every animation in §8.5 names the state change it explains.
6. **Same truth, two voices.** Candidates and staff see the same components and the same visual encoding; only the labels differ (audience label maps). *Test:* the `EvidenceLadder` for "Corroborated" is pixel-identical in both portals.
7. **Name-agnostic, theme-agnostic.** Components reference semantic tokens only (`--fg-muted`, `--accent`), never ramps or hex values. *Test:* `grep -E '#[0-9a-fA-F]{3,8}' src --include=*.css` finds hex values only in `src/design/tokens.css` and the brand presets.

---

## 2. Brand layer (swappable from one place)

```
src/design/brand/
├─ active.ts                ← THE ONE LINE TO CHANGE: export { default } from './presets/neutral'
├─ presets/
│  ├─ neutral/   tokens.json · fonts.ts · Mark.tsx · index.ts     (working default)
│  ├─ plumbline/ tokens.json · fonts.ts · Mark.tsx · index.ts     (values from NAMING.md §3)
│  ├─ assay/     …
│  └─ gradus/    …
└─ vite-plugin-brand.ts     (build-time: <title>, theme-color metas, brand tokens <style>, contrast gate)
```

**`tokens.json`** (pure data, read by both the app and the Vite plugin):

```json
{
  "name": "RecruitPro",
  "shortName": "RecruitPro",
  "portalLabel": { "candidate": "", "staff": "Workspace" },
  "tagline": "Hiring on evidence",
  "fonts": { "display": "'IBM Plex Sans', var(--font-fallback)", "displayWeight": 600, "displayTracking": "-0.015em" },
  "light": { "accent": "#3A5794", "accentHover": "#2F4A7F", "accentActive": "#263D69", "accentSubtle": "#ECF0F8",
             "accentSubtleBorder": "#C9D4EA", "accentText": "#2F4A7F", "onAccent": "#FFFFFF", "tierFill": "#3A5794" },
  "dark":  { "accent": "#8FA8E6", "accentHover": "#AABDEE", "accentActive": "#C3D1F3", "accentSubtle": "#1A2438",
             "accentSubtleBorder": "#2C3B5C", "accentText": "#A9BCEB", "onAccent": "#0D0F12", "tierFill": "#8FA8E6" },
  "neutral": null
}
```

`neutral` may hold a full override of the light and dark neutral ramps (§3.1). This is what NAMING.md's stone and grey-green ramps need; the default `null` keeps the system ramp.

**`vite-plugin-brand.ts`** (one shared file, about 60 lines, used in both `vite.config.ts`):
1. Finds the preset named in `active.ts` (regex `presets/(\w+)`) and reads its `tokens.json`.
2. `transformIndexHtml`: sets `<title>` to `name` (pages then set `"{Page} · {name}"` at runtime), and injects `<meta name="theme-color" media="(prefers-color-scheme: light)">` and the dark one from the canvas tokens.
3. Injects `<style id="brand-tokens">` with `:root{--accent:…}` and `:root[data-theme="dark"]{…}`. This is allowed by the CSP (`style-src 'self' 'unsafe-inline'`) and avoids any flash.
4. **Contrast gate. The build fails** if `accentText` is below 4.5:1 on `--bg-surface` or `--bg-canvas`, `onAccent` is below 4.5:1 on `accent`, or `accent`/`tierFill` is below 3:1 on `--bg-surface`, in either theme. The WCAG luminance formula is about 10 lines.

**`index.ts`** imports `./fonts` (side-effect `@fontsource` imports for the display face only) and exports `{ ...tokens, Mark }`. App code uses `import brand from '@/design/brand/active'`: `brand.name` in copy, `<brand.Mark size={20}/>` in the shell.

**What never changes with the brand:** neutrals (unless the preset overrides them), semantic colours, verdict colours, body and mono fonts, spacing, radii, motion and components. The ownership protocol strings (`recruitpro-verification=…`, `/.well-known/recruitpro-verification.txt`, `<meta name="recruitpro-verification">`) and the `X-RecruitPro-Portal` header are **protocol constants**, not brand. They stay until the backend accepts a new prefix. Consent sentences that contain the name are versioned server-side, so a rename needs a consent-version bump (UX_REVIEW §5).

**Neutral working mark** (`presets/neutral/Mark.tsx`): a 20×20 rounded-2 square in `--fg-default` containing three rising rungs (heights 5, 8, 11; width 3; gap 2; colour `--bg-surface`), next to `name` set in the display font at 15/20 600. It is deliberately plain; the real marks come from NAMING.md.

**Presets for tomorrow** (transcribed from NAMING.md; the plugin's contrast gate re-checks them):

| Preset | light accent / hover / subtle | dark accent / hover / subtle | display font |
|---|---|---|---|
| plumbline | `#2447C8` / `#1C39A6` / `#EBEFFC` | `#8FA6F7` / `#B0C0FA` / `#1B2448` | Instrument Sans 600 |
| assay | `#211F1B` / `#322F2A` / `#F0EEEA` (links always underlined) | `#F0EEEA` / `#FFFFFF` / `#322F2A` | IBM Plex Sans 600 |
| gradus | `#0C5E6E` / `#094B58` / `#E6F2F4` | `#6CC6D8` / `#9BDAE6` / `#12313A` | Source Serif 4 600 (H1, H2 only) |

---

## 3. Colour

### 3.1 Neutral ramps (hand-tuned per theme, not inverted)

Hue about 220° (cool slate), chroma very low so it sits under any of the three brand accents. The dark ramp is its own scale, tuned for these differences:
- text is about 90% white (`#E7E9EC`), never `#FFF`, to stop halation;
- surfaces step +3 to +5 L* per elevation, because shadows are nearly invisible on dark;
- hairlines are relatively **stronger** than in light;
- accents are lighter and less saturated.

| Step | Light `--n-*` | Light role | Dark `--d-*` | Dark role |
|---|---|---|---|---|
| 0 | `#FFFFFF` | surface | `#0D0F12` | canvas |
| 25 | `#FAFBFC` | table stripe (optional) | `#111317` | sunken, sidebar |
| 50 | `#F5F6F8` | canvas, sunken | `#14171B` | surface |
| 100 | `#EDEFF2` | hover, neutral chip bg | `#1A1E23` | raised (cards on canvas, dialogs) |
| 150 | `#E3E6EA` | hairline | `#21252B` | overlay (popover, menu), hover |
| 200 | `#D3D8DE` | border | `#24282E` | hairline |
| 300 | `#B4BBC4` | strong border, disabled text | `#30353C` | border |
| 400 | `#848C98` | control border (3.39:1 on 0) | `#434951` | strong border |
| 500 | `#646C78` | subtle text (5.31 on 0, 4.91 on 50) | `#6B737E` | control border (3.75:1 on 50), disabled text |
| 600 | `#4F5763` | muted text (7.30 on 0) | `#8C939D` | subtle text (5.80 on 50) |
| 700 | `#3B424C` | strong secondary | `#AEB4BD` | muted text (8.61 on 50) |
| 800 | `#272C33` | — | `#CDD2D8` | — |
| 900 | `#16191E` | default text (17.6 on 0) | `#E7E9EC` | default text (14.8 on 50) |
| 950 | `#0D0F12` | — | `#F5F6F7` | emphasis text |

### 3.2 Semantic aliases (the only colour tokens components may use)

| Token | Light | Dark | Notes |
|---|---|---|---|
| `--bg-canvas` | n-50 `#F5F6F8` | d-0 `#0D0F12` | page behind everything |
| `--bg-surface` | n-0 `#FFFFFF` | d-50 `#14171B` | main content sheet, sidebar in light |
| `--bg-sunken` | n-50 | d-25 `#111317` | table header, code field, quote |
| `--bg-raised` | n-0 + `--shadow-pop` | d-100 `#1A1E23` + 1 px `--border-default` | dialog, drawer |
| `--bg-overlay` | n-0 + `--shadow-pop` | d-150 `#21252B` | menu, popover, tooltip (dark tooltip in light theme: n-900) |
| `--bg-hover` | n-50 | d-100 | row/button hover on surface |
| `--bg-active` | n-100 | d-150 | pressed |
| `--bg-selected` | `--accent-subtle` | `--accent-subtle` | selected row, active nav |
| `--fg-default` | n-900 | d-900 | body text, data |
| `--fg-muted` | n-600 | d-700 | secondary text, labels |
| `--fg-subtle` | n-500 | d-600 | hints, timestamps (AA on surface **and** canvas) |
| `--fg-disabled` | n-300 | d-500 | disabled only (WCAG-exempt) |
| `--fg-on-accent` | brand `onAccent` | brand `onAccent` | |
| `--border-hairline` | n-150 | d-200 | dividers, panel edges |
| `--border-default` | n-200 | d-300 | menus, dialogs, table outer |
| `--border-strong` | n-300 | d-400 | hover borders, dashed "not checkable" |
| `--border-control` | n-400 | d-500 | input/checkbox borders (≥ 3:1, WCAG 1.4.11) |
| `--focus` | `--accent` | `--accent` | ring colour (7.07 / 7.63 on surface) |
| `--scrim` | `rgb(13 15 18 / .32)` | `rgb(0 0 0 / .56)` | behind dialogs/drawers |

**Never use** `--fg-subtle` on `--bg-active` or on tinted status backgrounds; use `--fg-muted` there.

### 3.3 Surfaces and elevation

| Level | Light | Dark | Used by |
|---|---|---|---|
| 0 canvas | n-50 | d-0 | app background, outside the content sheet |
| 1 surface | white, **no shadow**, hairline edge | d-50, hairline edge | sidebar, top bar, page sheet |
| 2 raised | white + `--shadow-pop` | d-100 + `--border-default` + inner highlight | dialogs, drawers, sticky footers |
| 3 overlay | white + `--shadow-pop` | d-150 + `--border-default` | menus, popovers, toasts, tooltips |

**App shell:** in light theme the sidebar and the content sheet are both white, separated by a hairline, on an n-50 canvas that shows only as a 0 px gutter on desktop (the sheet runs edge to edge) and around floating layers. In dark theme the sidebar is d-25 (sunken) and the content sheet d-50, so the content reads slightly lifted. Inside the sheet, sections are separated by hairlines and 32 px of space. **No card grid.**

### 3.4 Accent (from the brand preset)

`--accent`, `--accent-hover`, `--accent-active`, `--accent-subtle` (selected and hover-tint backgrounds), `--accent-subtle-border`, `--accent-text` (links, active nav text), `--fg-on-accent`, `--tier-fill`. Working neutral values are in §2. The accent is used for: the primary button, links, the focus ring, the selected nav item (subtle background + 2 px left bar), selected rows, tier fill and the verified coverage bar. **Nothing else.** Charts and status use their own tokens.

### 3.5 Status (semantic)

| Role | Light fg / bg / border | Dark fg / bg / border | fg on own bg (L / D) |
|---|---|---|---|
| success | `#17663A` / `#E6F3EB` / `#B9DCC6` | `#6FD39A` / `#11291B` / `#1F4A31` | 6.13 / 8.45 |
| warning | `#8A4B00` / `#FBEFD9` / `#EBCB94` | `#F0B45E` / `#2E2210` / `#57401B` | 5.98 / 8.43 |
| danger | `#B42318` / `#FCEBE9` / `#F2C1BC` | `#FF8A80` / `#351715` / `#5E2622` | 5.70 / 7.16 |
| info | `#1F5AA6` / `#E7EFFA` / `#BCD0EE` | `#8DB6F0` / `#132238` / `#23406A` | 5.90 / 7.68 |
| neutral | `#3B424C` / `#EDEFF2` / `#D3D8DE` | `#CDD2D8` / `#21252B` / `#30353C` | 9.0 / 10.6 |

Solid danger button: background `#B42318` with white text (6.57:1); in dark, `#FF8A80` with `#0D0F12` text (8.41:1).

### 3.6 Verdict colours (identical in both portals; never colour alone)

| Verdict | Label (both portals) | Icon (16 px) | Shape | Light fg / bg | Dark fg / bg |
|---|---|---|---|---|---|
| VERIFIED | Verified | `CircleCheck` (lucide), filled stroke | solid chip | success | success |
| PARTLY_VERIFIED | Partly verified | custom `CircleHalf` (left half filled) | solid chip | warning | warning |
| NOT_FOUND | Not found in connected sources | `Circle` (hollow) | **outline** chip: 1 px `--border-default`, bg transparent | `--fg-muted` | `--fg-muted` |
| CONTRADICTED | Contradicted | `TriangleAlert` | solid chip | danger | danger |
| NOT_CHECKABLE | Not checkable by agents | `CircleMinus` | **dashed** 1 px `--border-strong`, transparent | `--fg-muted` | `--fg-muted` |

The "Not found" chip is deliberately the quietest: neutral, hollow and outlined. It must never look negative.

### 3.7 Evidence-tier ladder

Five rungs. **Fill level is the encoding**; colour is the brand `--tier-fill` (≥ 3:1 by the plugin gate); unfilled rungs are `--border-default`. Tier 1 (self-claimed) is a **hollow dashed** first rung in `--fg-subtle`, meaning "nothing checked yet", not "a little evidence".

| Tier (ordinal) | Rungs shown | Candidate label | Staff label |
|---|---|---|---|
| SELF_CLAIMED (1) | rung 1 **dashed hollow**, rungs 2–5 empty | Your claim | Claimed |
| RESUME_STATED (2) | rungs 1–2 filled | On your resume | Resume |
| CORROBORATED (3) | rungs 1–3 filled | Corroborated | Corroborated |
| DEMONSTRATED (4) | rungs 1–4 filled | Demonstrated | Demonstrated |
| INTERVIEW_VERIFIED (5) | all 5 filled | Verified in interview | Interview-verified |
| no evidence (`tier: null`) | all 5 empty | Not found yet | No evidence |

Rule: **number of rungs = tier ordinal**, and the first rung is drawn dashed-hollow (not filled) only for SELF_CLAIMED. The step from claim to resume is therefore visible as the first solid rung.

Rung geometry: width 4 px, heights 6 / 8 / 10 / 12 / 14 px, gap 2 px, bottom-aligned, radius 1 px, total 28×14 px. **Strip cell** (ranking table): 10×18 px cell, fill from the bottom to `tier/5` of the height, 1 px `--border-hairline` outline. An open ranking gate gets a 1.5 px `warning-fg` outline.

### 3.8 Status badges

| Status | Tone |
|---|---|
| Application: Submitted | neutral · Shortlisted: info · Interviewing: info · Selected: success · **Not progressed** (REJECTED): neutral (not danger: it is a candidate's outcome, not an error) · Withdrawn: neutral, outline |
| Job: Draft: neutral outline · Published: success · On hold: warning · Closed: neutral · Cancelled: neutral, struck outline |
| Work sample: Not started: neutral outline · In progress: info · Submitted: info · Reviewed: success · Expired: neutral · Cancelled: neutral outline |
| Service health: Up: success dot + "Up" · Down: danger dot + "Down" · Unconfigured: neutral hollow dot + "Not configured" |

### 3.9 Data visualisation

Coverage bars: verified = `--accent` solid; "with claims" = a `--accent-subtle-border` 45° hatch (2 px stripes, 4 px pitch) drawn **behind** the verified fill to the claimed value. The numbers sit right-aligned in tabular numerals. Fairness tables: flagged rows use a warning left rule plus a "Flagged" text badge; no heatmap.

---

## 4. Typography

**Fonts** (OFL-1.1, from npm; latin plus latin-ext for ₹, which loads only when used thanks to `unicode-range`):

| Role | Family | Package | Weights | Why |
|---|---|---|---|---|
| UI and body | **IBM Plex Sans** | `@fontsource/ibm-plex-sans` 5.x | 400, 500, 600 | Engineered for interfaces; true tabular figures; distinctive grotesque detailing that does not read as the default "Inter look"; excellent at 13–14 px in dense tables. |
| Mono | **IBM Plex Mono** | `@fontsource/ibm-plex-mono` 5.x | 400, 500 | Hashes, codes, URLs, ids; same skeleton as the UI face. |
| Display | brand token (working default: IBM Plex Sans 600) | per preset | 600 | swapped with the name |

Import only `latin-{400,500,600}.css` and `latin-ext-{400,500,600}.css` (about 75 kB of woff2 in total, cached immutably). Use `font-display: swap` (the default). Fallback stack: `--font-fallback: ui-sans-serif, system-ui, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif`.

**Type scale** (px; line-height; weight; tracking):

| Token | Size / LH | Weight | Tracking | Use |
|---|---|---|---|---|
| `display` | 32 / 40 | 600 display | −0.02em | auth pages, referee heading |
| `h1` | 24 / 32 | 600 display | −0.015em | page title (one per page) |
| `h2` | 18 / 26 | 600 | −0.01em | section title |
| `h3` | 15 / 22 | 600 | 0 | subsection, panel title, dialog title |
| `body-lg` | 15 / 24 | 400 | 0 | long text: job descriptions, verdict reasons, referee page |
| `body` | 14 / 20 | 400 | 0 | default UI text |
| `body-strong` | 14 / 20 | 500 | 0 | names in tables, requirement text |
| `body-sm` | 13 / 18 | 400 | 0 | secondary lines, provenance, compact tables |
| `label` | 13 / 18 | 500 | 0 | form labels, table headers (sentence case) |
| `caption` | 12 / 16 | 500 | +0.01em | badges, chips, counts. **The minimum size anywhere.** |
| `overline` | 11 / 16 | 600 | +0.06em, uppercase | at most one per screen (e.g. dialog step "Step 2 of 3") |
| `mono` | 13 / 20 | 400 mono | 0, `"zero"` on | hashes, codes, URLs |
| `numeric` | inherit | inherit | — | `font-variant-numeric: tabular-nums lining-nums` on every table cell, coverage value, count, date column, timer and rank |

Rules: sentence case everywhere (table headers too); weights 400/500/600 only, never 700; maximum line length 72ch (`--w-read`); never letter-space body text.

---

## 5. Space, size, radius, border, shadow

**Spacing (4 px base):** `--s-0:0 · --s-1:2px · --s-2:4px · --s-3:6px · --s-4:8px · --s-5:12px · --s-6:16px · --s-7:20px · --s-8:24px · --s-9:32px · --s-10:40px · --s-11:48px · --s-12:64px`.
Rhythm: control internal gap 8; field to field 16; group to group 24; section to section 32 (plus a hairline); page top padding 24 (desktop) / 16 (phone).

**Control heights:** sm 28 · md 32 (default) · lg 40 (auth pages, phone primary). On `(pointer: coarse)` every control's hit area is ≥ 44 px (padding or `::before` extension).

**Radii (restrained):** `--r-xs: 2px` (rungs, kbd, focus inner) · `--r-sm: 4px` (buttons, inputs, badges, chips, segmented) · `--r-md: 6px` (menus, popovers, toasts, tooltips, dropzone, panels that must be boxed) · `--r-lg: 8px` (dialogs; drawers on their open edge only) · `--r-full` (avatars, switch track, status dots). **Nothing above 8 px except full-round.** Tables, page sheets and sections have **0** radius.

**Borders:** 1 px for everything. 2 px only for the focus ring, the selected nav/row indicator (a left bar, not a full border) and the open-gate outline. Dashed only for "not checkable", the self-claimed rung and the dropzone.

**Shadows:**

| Token | Light | Dark |
|---|---|---|
| `--shadow-xs` | `0 1px 0 rgb(16 20 26 / .04)` (secondary button bottom edge) | `none` |
| `--shadow-pop` | `0 1px 2px rgb(16 20 26 / .06), 0 6px 16px -4px rgb(16 20 26 / .14)` | `0 8px 24px -6px rgb(0 0 0 / .6), inset 0 1px 0 rgb(255 255 255 / .04)` |
| `--shadow-dialog` | `0 2px 6px rgb(16 20 26 / .08), 0 24px 48px -12px rgb(16 20 26 / .28)` | `0 24px 64px -12px rgb(0 0 0 / .7), inset 0 1px 0 rgb(255 255 255 / .05)` |

**Focus ring:** `outline: 2px solid var(--focus); outline-offset: 2px;` on `:focus-visible` only; inside tables, the offset is −2 px (inset) so it is not clipped. Never remove outlines.

---

## 6. Layout and grid

**Breakpoints (mobile-first, `min-width`):** `sm 600 · md 900 · lg 1200 · xl 1440`.

**App shell (both portals, one component `AppShell`):**

| | < 900 | 900–1199 | ≥ 1200 |
|---|---|---|---|
| Sidebar | hidden; **Menu** opens a left drawer (280 px) | 64 px icon rail with tooltips (HR); candidate shows the drawer | 240 px, collapsible to 64 px (persisted per viewer) |
| Top bar | 52 px: Menu · brand mark · bell · account | 52 px: breadcrumb · bell · account | same |
| Page padding | 16 | 24 | 32 |

Sidebar anatomy: brand (52 px tall, aligned with the top bar) · nav groups (group label = `caption` `--fg-subtle`, not uppercase) · items 32 px tall, 16 px icon, `body` 500, active = `--bg-selected` + 2 px accent left bar + `--accent-text` · footer: organisation name (HR) or nothing. The account menu lives in the top bar.

**Content widths:** `--w-read: 680px` (prose, verdict drawer body) · `--w-form: 640px` (single-column forms) · `--w-page: 1200px` (default) · `--w-wide: 1600px` (ranking table, applicant detail). Pages are left-aligned within the sheet, not centred, so tables and headers line up with the sidebar.

**Grid:** 12 columns, 24 px gutter from `md`; 4 columns, 16 px gutter below. Applicant detail: `minmax(0,1fr) 320px` from `lg`, with **every wide child in its own `overflow-x:auto` wrapper** (fixes H-1); a single column below `lg`, where the rail content becomes a tab.

**Page header pattern:** breadcrumb (`body-sm`) → H1 + status badge on one line → meta line (`body-sm`, `--fg-muted`, separated by `·`) → actions right-aligned (primary last, at most two visible, the rest in "⋯").

**Table density:** `data-density="comfortable" | "compact"` on `DataTable`, persisted per viewer in `localStorage['table-density']` (try/catch).

| | comfortable | compact |
|---|---|---|
| Row height | 44 px | 32 px |
| Cell padding x | 12 | 8 |
| Text | `body` | `body-sm` |
| Default for | candidate, admin tables | ranking table, audit log |

---

## 7. Iconography

- **Set:** `lucide-react@0.460.0` (ISC licence, tree-shakable, about 0.4 kB per icon). Import each icon by name through `src/design/icons.ts`, the only file that imports `lucide-react`, so the set stays curated and both portals use the same glyphs.
- **Sizes and strokes:** 16 px with `strokeWidth={1.75}` (inline, tables, buttons) · 20 px with `1.5` (nav, empty states) · `absoluteStrokeWidth` on. Colour is `currentColor`.
- **Custom icons** (same 24 grid, 1.75 stroke, in `icons.ts`): `CircleHalf` (partly verified), `LadderMark` (neutral brand mark), `GateOpen` (lucide `LockOpen` alias).
- **Mapping (excerpt):** Jobs `BriefcaseBusiness` · Applications `FileText` · Tasks `ListChecks` · My evidence `ShieldCheck` · Inbox `Inbox` · Interviews `CalendarClock` · Admin `Settings2` · Audit `ScrollText` · Fairness `Scale` · Verification `BadgeCheck` · GitHub/GitLab `Github`/`Gitlab` · Website `Globe` · Document `FileText` · Open Badge `Award` · Reference `UserRoundCheck` · Blind `EyeOff` · Reveal `Eye` · Copy `Copy` → `Check` · External `ArrowUpRight` · Ownership proven `KeyRound` · not proven `KeySquare` + muted.
- **Rules:** icons never replace a visible label in nav (rail mode uses a tooltip **and** `aria-label`). Decorative icons get `aria-hidden`. No emoji or Unicode symbols for meaning anywhere (fixes X-3).

---

## 8. Motion system

### 8.1 Decision: pure CSS + a 2 kB `motion.ts`, no `motion`/framer-motion

The bundles are already 113 kB (candidate) and 133 kB (HR) gzipped. `motion` (framer-motion v11) adds about 34 kB gz for `motion.*` components, or about 20 kB with `LazyMotion` + `domAnimation`. That is a 15–30% increase for effects we can do natively:
- **Enter and exit:** CSS transitions on `[data-state]` plus `@starting-style` and `transition-behavior: allow-discrete` for `<dialog>` and popovers (Chrome 117, Safari 17.5, Firefox 129). Older browsers simply skip the animation.
- **Rank reorder:** a FLIP helper using the Web Animations API (about 40 lines).
- **Count-up:** a `requestAnimationFrame` tween (about 20 lines).
- **Theme switch:** the View Transitions API.

Revisit this only if we add drag-to-reorder lists or shared-element layout animations across routes.

### 8.2 Tokens

| Token | Value | Use |
|---|---|---|
| `--dur-instant` | 80 ms | press feedback, checkbox tick |
| `--dur-fast` | 140 ms | hover, tooltip in, toast out, menu out |
| `--dur-base` | 200 ms | dialog in, tab indicator, crossfades, route in |
| `--dur-slow` | 280 ms | drawer in, verdict reveal, row expand |
| `--dur-slower` | 420 ms | ladder fill sequence (total), coverage bar fill |
| `--dur-count` | 700 ms | number count-up |
| `--ease-standard` | `cubic-bezier(0.2, 0, 0, 1)` | moves, expands, most enters |
| `--ease-decelerate` | `cubic-bezier(0.05, 0.7, 0.1, 1)` | reveals, bar fills, count-up, drawers in |
| `--ease-exit` | `cubic-bezier(0.4, 0, 1, 1)` | everything leaving |
| `--ease-settle` | `cubic-bezier(0.34, 1.36, 0.64, 1)` | **only** the new rung landing and the switch knob (≈4% overshoot) |
| `--ease-linear` | `linear` | shimmer, progress, timers |

### 8.3 Choreography rules

1. Enter with `--ease-decelerate`/`--ease-standard`; exit with `--ease-exit` at about 70% of the enter duration.
2. Animate `transform` and `opacity` only. Bars use `transform: scaleX()` with `transform-origin: left`, never `width`.
3. Travel distance is 4–8 px. Only drawers travel their full width. Dialogs scale from 0.98, never from 0.9.
4. Stagger lists by 24 ms per item for the first 8 items; the rest arrive with item 8. Only on first load of a list, never on refetch or filter change (those crossfade the table body over 140 ms).
5. One signature animation at a time. When several verdicts arrive together, reveal them in reading order with a 60 ms stagger.
6. Nothing loops except the skeleton shimmer, indeterminate progress and the timer pulse in the final minute.
7. Animations never delay input. Focus moves immediately; content becomes interactive at frame 0.
8. Every animation in §8.5 has a reduced-motion version (§8.6).

### 8.4 `motion.ts` API (shared file)

```ts
export const reduced = () => matchMedia('(prefers-reduced-motion: reduce)').matches
export function countUp(el: HTMLElement, to: number, opts?: { from?: number; ms?: number; format?: (n: number) => string }): void
export function flip(container: HTMLElement, mutate: () => void, opts?: { ms?: number }): Promise<void> // FLIP keyed by [data-flip-id]
export function stagger(items: Iterable<HTMLElement>, opts?: { step?: number; max?: number }): void        // sets --i, adds .is-entering
export function withViewTransition(update: () => void, origin?: { x: number; y: number }): void           // theme switch
```

Every function checks `reduced()` first and applies the end state synchronously when it is true.

### 8.5 Catalogue: what animates, and why

| # | Interaction | Spec | Explains |
|---|---|---|---|
| M1 | Route change | content wrapper keyed by `pathname`: opacity 0→1, translateY 4→0, 200 ms `--ease-standard`; no exit | "new place" |
| M2 | List first load | rows: opacity + translateY 4→0, 200 ms, stagger 24 ms × 8 | content arriving in reading order |
| M3 | Skeleton | `linear-gradient(90deg, transparent, var(--skeleton-sheen), transparent)` sweeping 1400 ms linear infinite over `--bg-active` blocks; shown only after 150 ms; replaced by a 140 ms crossfade | "loading, here is the shape" |
| M4 | **Coverage count-up** | bar `scaleX` 0→value over 420 ms `--ease-decelerate`; the number counts up over 700 ms in tabular numerals; the claimed hatch follows 80 ms later | magnitude, and verified before claimed |
| M5 | **Evidence-ladder fill** | rungs fill bottom-up, each `scaleY` 0→1 from the bottom, 120 ms, stagger 50 ms (5 rungs = 320 ms). On a **tier upgrade** (claim verified, verdict confirmed, scorecard counted) only the new rungs fill, the top one landing with `--ease-settle`, then the label crossfades | "this evidence just got stronger": the product's core idea |
| M6 | **Verdict reveal** | a "Checking…" chip (spinner icon) morphs into the verdict chip: the width tweens via a FLIP of the chip box over 280 ms, the label crossfades, and the icon draws (`stroke-dashoffset` 100%→0 over 240 ms) | an outside check finished |
| M7 | **Rank change** | after a re-match the ranking table rows FLIP to their new positions over 280 ms `--ease-standard`; the rank number crossfades; a row that changed group gets a 1.2 s `--accent-subtle` background fade-out | why the order changed |
| M8 | Dialog | backdrop opacity 0→1, 200 ms; panel opacity + scale 0.98→1 + translateY 4→0, 200 ms `--ease-decelerate`; exit 140 ms `--ease-exit` | modal context |
| M9 | Drawer | translateX 100%→0, 280 ms `--ease-decelerate`; exit 200 ms `--ease-exit`; content inside fades 140 ms after a 60 ms delay | side context; the list stays visible |
| M10 | Toast | in: translateY 8→0 + opacity, 200 ms; out: opacity + translateX 8, 140 ms; a 2 px auto-dismiss progress line at the bottom, paused on hover/focus | confirmation |
| M11 | Tabs | the active indicator (2 px bar) slides between tabs with a transform, 200 ms `--ease-standard`; the panel crossfades 140 ms | where you are |
| M12 | Row expand (evidence row, verification claim) | height via the `grid-template-rows: 0fr→1fr` technique, 280 ms `--ease-standard`, content opacity 140 ms delayed 60 ms | disclosure |
| M13 | Identity reveal (blind → revealed) | the blind card crossfades to identity fields over 240 ms; fields stagger 24 ms | an audited state change |
| M14 | Theme switch | View Transition: the new theme is revealed by a circular `clip-path` from the toggle's centre, 360 ms `--ease-standard`; fallback is a 200 ms colour transition on `html.theme-switching *` (removed after 250 ms) | the whole UI changed theme |
| M15 | Button press | `scale(0.98)` over 80 ms; no hover lift or glow | tactile feedback |
| M16 | Timer, final 5 minutes | the timer bar turns warning; in the final 60 s the numeral pulses opacity 1↔0.7 at 1 Hz | urgency |
| M17 | Copy | the icon swaps Copy→Check with a 140 ms crossfade, "Copied" announced, reverting after 1.6 s | success |

### 8.6 Reduced motion

`@media (prefers-reduced-motion: reduce)` and `reduced()`:
- M1/M2/M8/M9/M10/M11/M13: opacity only, 120 ms; no translate or scale.
- M3: static `--bg-active` blocks, no sweep.
- M4/M5/M7: final state immediately (no count-up, no FLIP, no fill); M7 keeps the background fade as a static 1.2 s highlight.
- M6: instant swap.
- M14: instant.
- M16: colour change only, no pulse.

The harness captures with reduced motion by default; check the full motion manually with `--motion` for the signature screens.

---

## 9. Components

Every component lives in `src/design/components/`, is identical in both portals, takes `className` and forwards `ref`, and styles itself from `components.css` via `.ui-*` classes. Variants are set with `data-variant`/`data-size`, and states come from ARIA/HTML attributes (`aria-pressed`, `aria-invalid`, `aria-busy`, `:disabled`, `[data-state=open]`). Styling never hangs off a class that duplicates an ARIA state.

### 9.1 Button · `ui-btn`
- **Anatomy:** [icon 16] label [trailing icon/kbd]. Height 32 (md), 28 (sm), 40 (lg); padding x 12 (sm 8, lg 16); radius 4; `label` type.
- **Variants:** `primary` (accent bg, on-accent text) · `secondary` (surface bg, `--border-default`, `--shadow-xs`) · `ghost` (transparent, `--fg-muted`, hover `--bg-hover`) · `danger` (danger solid; **only** inside a confirmation dialog) · `danger-ghost` (danger text; for Reject/Withdraw/Delete on pages) · `link`.
- **States:** hover (bg one step), active (M15), focus-visible ring, disabled (`--fg-disabled`, no pointer events, **never** used to mean "not saved yet": Save stays enabled and validates), `aria-busy` (spinner replaces the leading icon, label kept, width locked).
- **Do:** one primary per region; the verb plus the object ("Share with employers"). **Don't:** a primary and a danger side by side; icon-only buttons without `aria-label` and a tooltip.

### 9.2 IconButton · `ui-icon-btn`
28/32 px square, radius 4, ghost by default, tooltip required. Hit area 44 on coarse pointers.

### 9.3 Text input, textarea · `ui-input`
- Height 32; padding x 10; 1 px `--border-control`; radius 4; bg `--bg-surface`; placeholder `--fg-subtle`.
- Hover: `--border-strong`. Focus: border `--accent` + ring. Invalid: `aria-invalid` gives `--danger` border plus an error line with an icon. Read-only: `--bg-sunken`, no border.
- Affixes inside (₹, `https://`, search icon). Textarea auto-grows from `rows` to `max-rows`, with the counter in the hint line ("120 / 1,000").

### 9.4 Field · `ui-field`
Label (`label`) → control → hint (`body-sm`, `--fg-subtle`) → error (`body-sm`, danger, icon). Wires `id`, `aria-describedby`, `aria-invalid`. "(optional)" is written after optional labels; required fields are not starred. Server errors map per field through the shared `fieldErrorMap`.

### 9.5 Select
A native `<select>` styled as `ui-input` with a 16 px chevron (no custom listbox; this keeps platform accessibility). For long lists with search (interviewer picker, audit action), use `Combobox` (phase 2; a native `<input list>` until then).

### 9.6 DataTable · `ui-table`
- **Anatomy:** toolbar (filters left, count + density + actions right) · sticky header (`--bg-sunken`, `label`, sentence case, sortable headers are buttons with an `ArrowUpDown` → `ArrowUp`/`ArrowDown` icon and `aria-sort`) · body rows (hairline separators, no zebra by default) · optional group header rows (`--bg-sunken`, `h3` + count + one-line explanation) · footer (pagination).
- **Row:** the whole row is clickable when there is one destination (the primary cell holds the real `<a>`; the row uses `:has(a:hover)` styling and a `::after` overlay link). Hover `--bg-hover`; selected `--bg-selected` + 2 px accent left bar; keyboard ↑/↓ moves row focus (roving `tabindex`), Enter opens, Space toggles selection.
- **Selection:** a checkbox column (sticky); the header checkbox is tri-state; a bulk bar replaces the toolbar while a selection exists ("3 selected · Assign work sample · Clear").
- **Numbers:** right-aligned, tabular numerals. Dates: relative ("3 d ago") with the absolute date in a tooltip and `<time datetime>`.
- **Responsive:** below 600 px, rows become stacked blocks (primary cell, then a `·`-separated meta line).
- **Ranking-table specialisation:** columns `#` (32 px, tabular) · Applicant · Status · **Evidence strip** · Verified % · With claims % · Applied. The strip shows one `EvidenceLadder variant="cell"` per requirement (must-haves first, separated by a 6 px gap from nice-to-haves), each with a tooltip "Java 17 and Spring Boot · Corroborated · ranking gate". Its accessible name is "4 of 6 requirements evidenced; ranking gate open: Java 17".

### 9.7 Checkbox, radio, switch, segmented
- **Checkbox/radio:** 16 px box, 1 px `--border-control`, radius 2 (checkbox) / full (radio); checked = accent fill + white check (an SVG path drawn with `stroke-dashoffset` over 80 ms). The label is clickable; hit row ≥ 32 px.
- **RadioCard:** only where each option needs a description of more than one line (verification mode, source kind). Hairline border, radius 6, selected = accent border + `--bg-selected`.
- **Switch:** 28×16 track, 12 px knob, `--ease-settle` 140 ms; it changes a setting immediately (otherwise use a checkbox). Always paired with a visible label.
- **SegmentedControl** (`role="radiogroup"`): 28/32 px, radius 4, sunken track, the selected segment raised (surface + `--shadow-xs`; in dark, d-150). Used for: referee answers, the Confirm/Overrule review, the 1–4 rating scale, the verdict picker, the density toggle.

### 9.8 Tabs · `ui-tabs`
Underline style: 40 px tall, `body` 500, `--fg-muted` → active `--fg-default` with a 2 px accent indicator (M11). Counts in a `caption` neutral badge. Overflow: horizontal scroll with 24 px edge fade masks; the active tab is scrolled into view. WAI-ARIA roving focus (kept from HR `Tabs`).

### 9.9 Badge (status) · `ui-badge` and Tag · `ui-tag`
- **Badge:** height 20, padding x 6, radius 4, `caption`, tone bg + fg (§3.5, §3.8), optional 6 px dot. Not interactive.
- **Tag:** for attributes such as type, location or "Blind". Plain `caption` text in `--fg-muted` with an optional 12 px icon, **no background** unless removable (chips input: `--bg-active`, an `X` icon button 16 px with a 24 px hit area).
- **Requirement markers:** must-have = a 6×6 solid `--fg-default` square + "Must-have"; nice-to-have = a hollow square + "Nice to have"; ranking gate = `LockKeyhole` 12 px + "Gate".

### 9.10 VerdictChip · `ui-verdict`
Height 22, padding x 8, radius 4, icon 14 + label (`caption`); shapes and colours per §3.6. Props: `verdict`, `size`, `state="checking"` (spinner + "Checking…"), `animateFrom` (enables M6). Accessible text: the label plus the description in `title` and an sr-only span ("Verified: the source supports the claim and belongs to the candidate").

### 9.11 EvidenceLadder · `ui-ladder`
- **`variant="chip"`:** rungs (28×14) + the audience label (`caption`, `--fg-muted`), e.g. `▁▃▅▇█ Corroborated`.
- **`variant="cell"`:** the 10×18 strip cell (§3.7).
- **`variant="legend"`:** all five tiers in a row with labels and one-line descriptions. Used on My evidence and inside the "How the ladder works" popover.
- Props: `tier`, `audience: 'candidate' | 'staff'`, `gateOpen`, `previousTier` (enables M5 upgrade). `role="img"` with an `aria-label` like "Evidence: corroborated (tier 3 of 5)".

### 9.12 CoverageMeter · `ui-coverage`
Two stacked 4 px bars, 120 px wide (64 in the table); labels "Verified" and "With claims" on the left (omitted in the table, which uses column headers); values right in tabular numerals. Animated per M4 on first view only (IntersectionObserver). An info tooltip carries the "not a probability of success" sentence.

### 9.13 ProvenanceLine · `ui-provenance`
`[source icon] Source kind · actor/verifier · <time>` in `body-sm` `--fg-muted`, with an optional "View source ↗". It always sits directly under the quote it qualifies. Quotes: `body-lg` (candidate) / `body` (HR), with a 2 px `--border-strong` left rule and `--bg-sunken` background, no box border.

### 9.14 Section and panel (instead of cards)
- **`Section`:** `h2` + optional description + actions, then content; separated from the next section by a hairline and 32 px.
- **`Panel`:** boxed (hairline, radius 6, padding 16) **only** for the allowed cases in principle 2.
- **Stat:** label (`body-sm` muted) above a value (`h2` numeric), with an optional delta; in a row, separated by vertical hairlines and not boxed.

### 9.15 Dialog · `ui-dialog`
Native `<dialog>` + `showModal()` (inert background, focus trap, Escape). Widths 400 (confirm) / 560 (form) / 720 (rich). Header: `h3` title + close IconButton. Body padding 20. Footer: right-aligned buttons, Cancel first; Cancel is focused first for destructive confirmations. Busy state blocks closing. Motion M8. Destructive confirmations name the object and the consequence in the title ("Withdraw your application for Senior Backend Engineer?").

### 9.16 Drawer · `ui-drawer`
`<dialog>` positioned right; width `min(480px, 100vw)` (verdict detail, add source, reference request) or 640 (shadow-queue detail). Sticky header and footer, scrolling body. On phones it becomes a bottom sheet (90 vh, radius 8 on the top corners, drag handle decorative). Motion M9.

### 9.17 Toast · `ui-toast`
A region portalled **outside `#root`** (kept from HR), bottom-right (bottom-centre on phones), at most 3 visible. 360 px wide, `--bg-overlay`, radius 6, a 3 px left bar in the tone colour, icon, one-line title, optional body and **one** action (e.g. Undo). Success/info auto-dismiss after 6 s (paused on hover/focus); errors stay. Polite vs assertive live regions as today.

### 9.18 Tooltip and popover
- **Tooltip:** `--bg-overlay` (light theme: n-900 bg with d-900 text for punch), `caption`, radius 4, 6 px offset, delay 400 ms in and 0 out. Shown on hover **and** focus; never holds essential or interactive content.
- **Popover/menu:** radius 6, padding 4, items 32 px, `--shadow-pop`. Built on `popover` + anchor positioning where supported, otherwise a small positioning hook.

### 9.19 Inline alert and banner · `ui-alert`
A tone left bar (3 px) + icon + text, on the tone background, radius 6. The page-level banner (verify email, shadow mode) is a single line with an action link and a dismiss control (session-persisted).

### 9.20 Skeleton · `ui-skeleton`
Blocks in `--bg-active`, radius 2 (text lines 10 px tall at the line-height spacing), motion M3. Each page provides a skeleton that matches its final layout (header, table rows, side rail).

### 9.21 Pagination
"Showing 1–25 of 38" (tabular) · Previous / page numbers (max 7 with an ellipsis) / Next as ghost buttons. Kept in the URL.

### 9.22 Breadcrumb
`body-sm`, `--fg-muted` links, a `ChevronRight` 12 px separator, the current page in `--fg-default` without a link. Truncates the middle on phones.

### 9.23 Stepper
Horizontal: numbered 20 px circles (done = check, current = accent ring, upcoming = hairline) joined by 1 px lines; labels `body-sm`. Used in the apply dialog, add-source drawer and reference request.

### 9.24 Timeline
A vertical 1 px hairline; 8 px dots (done = `--fg-default` filled, current = accent with a 4 px `--accent-subtle` halo, future = hollow); title `body-strong`, meta `body-sm` muted, comment as a quote. Used for application progress and decision history.

### 9.25 Empty state
Left-aligned within its region (not a centred illustration). An icon (20 px, `--fg-subtle`) + one `h3` line ("No applicants yet") + one sentence saying why or what happens next + one action. No illustrations, no tinted blobs. For filtered-empty: "No jobs match 'zzzz'" + **Clear filters**.

### 9.26 FileDropzone
Dashed 1 px `--border-strong`, radius 6, 96 px tall (64 compact); icon + "Drop a PDF, DOC or DOCX here, or **choose a file**" + limits line. Drag-over: accent dashed border + `--bg-selected`. After choosing: a file row (icon, name, size, progress bar, remove). Client checks run before upload with field-level errors.

### 9.27 CodeField and copy · `ui-code`
`--bg-sunken`, mono 13, radius 4, padding 6 10; a truncate-middle option for hashes; a trailing copy IconButton (M17). A label above if it stands alone (DNS host, TXT value).

### 9.28 Avatar and initials
24/32 px circle, `--bg-active` background, `caption` 600 initials in `--fg-muted`. Blind applicants never get initials: they get the `EyeOff` icon in the same circle.

### 9.29 Meter (readiness, timer)
A 6 px track (`--bg-active`), a fill in a tone, a target tick (2 px `--fg-default`), labels "118 / 150" and target. Used for shadow-mode readiness and the work-sample timer bar.

### 9.30 Command palette (optional, HR, phase 2)
⌘K / Ctrl K: jump to a job, applicant id, admin page. A `dialog` 560 px wide, `body` input, results grouped. Only after the core screens are done.

---

## 10. Theme switching

- **Preference:** `system | light | dark`, stored per viewer in `localStorage['theme-preference']` with every access in `try/catch` (private windows, blocked storage). The default is `system`. It is applied as `data-theme="light|dark"` on `<html>` plus `color-scheme: light|dark`.
- **No flash:** the CSP forbids inline scripts (`script-src 'self'`), so ship **`public/theme-init.js`** (same origin, about 400 bytes) and load it as the first thing in `<head>`: `<script src="/theme-init.js"></script>` (blocking, not a module). It reads the preference, resolves `system` with `matchMedia`, and sets `data-theme` before the CSS paints:

```js
(function () {
  var p = 'system'
  try { p = localStorage.getItem('theme-preference') || 'system' } catch (e) {}
  var dark = p === 'dark' || (p === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  var d = document.documentElement
  d.setAttribute('data-theme', dark ? 'dark' : 'light')
  d.style.colorScheme = dark ? 'dark' : 'light'
})()
```

- **`theme.ts`:** `getPreference()`, `setPreference(p, originEl?)` (persist, apply through `withViewTransition`, M14), `subscribe(fn)`. It listens to `matchMedia` changes while the preference is `system`. The UI lives in the account menu as a 3-option segmented control (Monitor / Sun / Moon icons + labels). The referee page and auth pages get a small icon toggle in the corner.
- **Tokens** are defined under `:root, :root[data-theme="light"]` and `:root[data-theme="dark"]`. A `@media (prefers-color-scheme: dark) { :root:not([data-theme]) {…} }` block covers the case where the script did not run.
- Both `theme-color` metas are injected by the brand plugin.

---

## 11. CSS architecture

**Decision:** plain global CSS with **cascade layers** and design tokens as custom properties, plus page-level CSS files. No CSS modules, no Tailwind, no preprocessor. This keeps the current Vite setup with zero build changes. Layers make specificity predictable without BEM gymnastics, and the shared `src/design/` folder can be copied byte for byte.

```css
/* src/design/index.css — imported once in main.tsx */
@layer reset, tokens, base, components, patterns, pages, utilities;
@import './reset.css' layer(reset);
@import './tokens.css' layer(tokens);
@import './base.css' layer(base);         /* html/body, type scale classes, focus, links, selection, scrollbars */
@import './components.css' layer(components); /* .ui-* */
@import './motion.css' layer(components); /* keyframes, [data-state] transitions, reduced-motion overrides */
@import './patterns.css' layer(patterns); /* AppShell, PageHeader, Section, auth layout */
@import './utilities.css' layer(utilities); /* .sr-only, .numeric, .truncate, .stack-*, .cluster */
```

Each page imports its own `Page.css` wrapped in `@layer pages { … }`. Naming: `ui-` for design-system components, `p-` for patterns, page classes prefixed with the page (`ranking-…`). Variants use `[data-variant]`, states use ARIA. **No raw hex, px font sizes or durations outside `tokens.css`**; a stylelint rule (`color-no-hex`, `declaration-property-value-disallowed-list`) enforces it in `pages` and `components`.

The old `styles/*.css` files are deleted screen by screen as pages migrate. They load in an `@layer legacy` placed **before** `components`, so new styles always win during the migration.

---

## 12. File plan (identical in both portals)

```
src/design/                       ← byte-identical in frontend/ and hr-frontend/
├─ index.css  reset.css  tokens.css  base.css  components.css  motion.css  patterns.css  utilities.css
├─ brand/     active.ts · presets/{neutral,plumbline,assay,gradus}/ · vite-plugin-brand.ts
├─ theme.ts   motion.ts   icons.ts   fonts.ts (Plex Sans + Mono imports)
├─ lib/       format.ts (dates, money ₹ en-IN, plural, relative time) · fieldErrors.ts · password.ts · labels.ts (tier/verdict/status labels by audience)
└─ components/
   Button.tsx IconButton.tsx Field.tsx Input.tsx Textarea.tsx Select.tsx Checkbox.tsx Radio.tsx RadioCard.tsx Switch.tsx
   SegmentedControl.tsx Tabs.tsx DataTable.tsx Badge.tsx Tag.tsx VerdictChip.tsx EvidenceLadder.tsx CoverageMeter.tsx
   ProvenanceLine.tsx Section.tsx Panel.tsx Stat.tsx Dialog.tsx Drawer.tsx Toast.tsx Tooltip.tsx Menu.tsx Alert.tsx
   Skeleton.tsx Pagination.tsx Breadcrumb.tsx Stepper.tsx Timeline.tsx EmptyState.tsx FileDropzone.tsx CodeField.tsx
   Avatar.tsx Meter.tsx AppShell.tsx PageHeader.tsx AccountMenu.tsx ThemeSwitch.tsx index.ts
public/theme-init.js              ← identical in both
```

Portal-specific code (navigation config, pages, API) stays outside `src/design/`. Components never import from `api/` or `pages/`.

**Sync check** (`tools/check_design_sync.py`, to be added by the first engineer; it runs in CI and before every commit that touches `src/design`):

```python
#!/usr/bin/env python3
"""Fail if frontend/src/design and hr-frontend/src/design (and public/theme-init.js) differ."""
import filecmp, pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
PAIRS = [("frontend/src/design", "hr-frontend/src/design"), ("frontend/public/theme-init.js", "hr-frontend/public/theme-init.js")]
def walk(base):
    return {p.relative_to(base).as_posix() for p in base.rglob("*") if p.is_file() and "__pycache__" not in p.parts}
problems = []
for a, b in PAIRS:
    pa, pb = ROOT / a, ROOT / b
    if pa.is_file() or pb.is_file():
        if not (pa.is_file() and pb.is_file() and filecmp.cmp(pa, pb, shallow=False)):
            problems.append(f"differs: {a} <> {b}")
        continue
    fa, fb = walk(pa), walk(pb)
    problems += [f"only in {a}: {f}" for f in sorted(fa - fb)] + [f"only in {b}: {f}" for f in sorted(fb - fa)]
    problems += [f"differs: {f}" for f in sorted(fa & fb) if not filecmp.cmp(pa / f, pb / f, shallow=False)]
print("\n".join(problems) or "design folders in sync")
sys.exit(1 if problems else 0)
```

Workflow: change `src/design` in one portal, copy the folder to the other, run the check, then rebuild and snapshot both portals.

---

## 13. Budgets and guard rails

| Budget | Limit |
|---|---|
| JS gzip per portal after redesign | ≤ current + 15 kB (lucide icons + design components); route-level `lazy()` for admin pages, fairness, interview kit and the referee page |
| Fonts | ≤ 6 woff2 files on first load (Plex Sans 400/500/600 latin, Mono 400 latin; latin-ext on demand) |
| CSS gzip per portal | ≤ 14 kB once the legacy files are deleted |
| Contrast | every text token pair listed in §3; enforced by axe in the harness and by the brand plugin for accents |
| Harness | `node snap.mjs … --theme both --axe` exits 0 for every migrated screen |

---

## Appendix A. `tokens.css` starting point (semantic layer, both themes)

The brand tokens (`--accent*`, `--tier-fill`, `--font-display*`) are injected by the brand plugin and are not repeated here.

```css
:root, :root[data-theme="light"] {
  color-scheme: light;
  --n-0:#FFFFFF; --n-25:#FAFBFC; --n-50:#F5F6F8; --n-100:#EDEFF2; --n-150:#E3E6EA; --n-200:#D3D8DE; --n-300:#B4BBC4;
  --n-400:#848C98; --n-500:#646C78; --n-600:#4F5763; --n-700:#3B424C; --n-800:#272C33; --n-900:#16191E; --n-950:#0D0F12;

  --bg-canvas:var(--n-50); --bg-surface:var(--n-0); --bg-sunken:var(--n-50); --bg-raised:var(--n-0); --bg-overlay:var(--n-0);
  --bg-hover:var(--n-50); --bg-active:var(--n-100); --bg-selected:var(--accent-subtle);
  --fg-default:var(--n-900); --fg-muted:var(--n-600); --fg-subtle:var(--n-500); --fg-disabled:var(--n-300);
  --border-hairline:var(--n-150); --border-default:var(--n-200); --border-strong:var(--n-300); --border-control:var(--n-400);
  --focus:var(--accent); --scrim:rgb(13 15 18 / .32); --skeleton-sheen:rgb(255 255 255 / .6);
  --tooltip-bg:var(--n-900); --tooltip-fg:#E7E9EC;

  --success-fg:#17663A; --success-bg:#E6F3EB; --success-border:#B9DCC6;
  --warning-fg:#8A4B00; --warning-bg:#FBEFD9; --warning-border:#EBCB94;
  --danger-fg:#B42318;  --danger-bg:#FCEBE9;  --danger-border:#F2C1BC;  --danger-solid:#B42318; --on-danger:#FFFFFF;
  --info-fg:#1F5AA6;    --info-bg:#E7EFFA;    --info-border:#BCD0EE;
  --neutral-fg:#3B424C; --neutral-bg:#EDEFF2; --neutral-border:#D3D8DE;

  --shadow-xs:0 1px 0 rgb(16 20 26 / .04);
  --shadow-pop:0 1px 2px rgb(16 20 26 / .06), 0 6px 16px -4px rgb(16 20 26 / .14);
  --shadow-dialog:0 2px 6px rgb(16 20 26 / .08), 0 24px 48px -12px rgb(16 20 26 / .28);
}

:root[data-theme="dark"] {
  color-scheme: dark;
  --d-0:#0D0F12; --d-25:#111317; --d-50:#14171B; --d-100:#1A1E23; --d-150:#21252B; --d-200:#24282E; --d-300:#30353C;
  --d-400:#434951; --d-500:#6B737E; --d-600:#8C939D; --d-700:#AEB4BD; --d-800:#CDD2D8; --d-900:#E7E9EC; --d-950:#F5F6F7;

  --bg-canvas:var(--d-0); --bg-surface:var(--d-50); --bg-sunken:var(--d-25); --bg-raised:var(--d-100); --bg-overlay:var(--d-150);
  --bg-hover:var(--d-100); --bg-active:var(--d-150); --bg-selected:var(--accent-subtle);
  --fg-default:var(--d-900); --fg-muted:var(--d-700); --fg-subtle:var(--d-600); --fg-disabled:var(--d-500);
  --border-hairline:var(--d-200); --border-default:var(--d-300); --border-strong:var(--d-400); --border-control:var(--d-500);
  --focus:var(--accent); --scrim:rgb(0 0 0 / .56); --skeleton-sheen:rgb(255 255 255 / .05);
  --tooltip-bg:var(--d-150); --tooltip-fg:var(--d-900);

  --success-fg:#6FD39A; --success-bg:#11291B; --success-border:#1F4A31;
  --warning-fg:#F0B45E; --warning-bg:#2E2210; --warning-border:#57401B;
  --danger-fg:#FF8A80;  --danger-bg:#351715;  --danger-border:#5E2622;  --danger-solid:#FF8A80; --on-danger:#0D0F12;
  --info-fg:#8DB6F0;    --info-bg:#132238;    --info-border:#23406A;
  --neutral-fg:#CDD2D8; --neutral-bg:#21252B; --neutral-border:#30353C;

  --shadow-xs:none;
  --shadow-pop:0 8px 24px -6px rgb(0 0 0 / .6), inset 0 1px 0 rgb(255 255 255 / .04);
  --shadow-dialog:0 24px 64px -12px rgb(0 0 0 / .7), inset 0 1px 0 rgb(255 255 255 / .05);
}

/* No-JS fallback: follow the OS when theme-init.js did not run. Copy the dark block's declarations here. */
@media (prefers-color-scheme: dark) { :root:not([data-theme]) { /* same declarations as [data-theme="dark"] */ } }

:root {
  --font-sans:'IBM Plex Sans', var(--font-fallback); --font-mono:'IBM Plex Mono', ui-monospace, 'Cascadia Mono', Consolas, monospace;
  --font-fallback:ui-sans-serif, system-ui, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  --s-1:2px; --s-2:4px; --s-3:6px; --s-4:8px; --s-5:12px; --s-6:16px; --s-7:20px; --s-8:24px; --s-9:32px; --s-10:40px; --s-11:48px; --s-12:64px;
  --r-xs:2px; --r-sm:4px; --r-md:6px; --r-lg:8px; --r-full:999px;
  --h-sm:28px; --h-md:32px; --h-lg:40px;
  --w-read:680px; --w-form:640px; --w-page:1200px; --w-wide:1600px; --sidebar:240px; --rail:64px; --topbar:52px;
  --dur-instant:80ms; --dur-fast:140ms; --dur-base:200ms; --dur-slow:280ms; --dur-slower:420ms; --dur-count:700ms;
  --ease-standard:cubic-bezier(.2,0,0,1); --ease-decelerate:cubic-bezier(.05,.7,.1,1); --ease-exit:cubic-bezier(.4,0,1,1);
  --ease-settle:cubic-bezier(.34,1.36,.64,1);
}
@media (prefers-reduced-motion: reduce) {
  :root { --dur-slow:120ms; --dur-slower:0ms; --dur-count:0ms; --dur-base:120ms; }
}
```

## Appendix B. Contrast record (WCAG 2.1, computed)

| Pair | Light | Dark |
|---|---|---|
| fg-default on surface / canvas | 17.62 / 16.29 | 14.78 / 15.78 |
| fg-muted on surface / canvas / active | 7.30 / 6.75 / 6.34 | 8.61 / 9.20 / 8.03 (raised) |
| fg-subtle on surface / canvas | 5.31 / 4.91 | 5.80 / 6.19 (5.40 on raised) |
| accent-text on surface | 8.73 | 9.49 |
| on-accent on accent | 7.07 | 8.14 |
| border-control on surface (non-text ≥ 3) | 3.39 | 3.75 |
| tier-fill / accent on surface (non-text ≥ 3) | 7.07 | 7.63 |
| status fg on own bg (success, warning, danger, info) | 6.13, 5.98, 5.70, 5.90 | 8.45, 8.43, 7.16, 7.68 |
| current HR `--muted #718087` on white / page bg (for reference) | **4.09 / 3.80 (fails)** | — |
