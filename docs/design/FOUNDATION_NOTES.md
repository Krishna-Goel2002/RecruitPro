# Design-system foundation: notes for the page engineers

**Status:** foundation built on `redesign` (16 Nov 2026). **Read with:** [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) (the spec), [REDESIGN_PLAN.md](REDESIGN_PLAN.md) (who does which screen).
**Living style guide:** `/__design` in both portals (hidden route, not linked; dev and prod builds). Harness screen name: `design-system`.

Both portals now run inside the new `AppShell` with the new tokens, fonts, icons and theme switching. **No page has been restyled.** Old pages still use their own CSS, now loaded into `@layer legacy` with every colour mapped onto the semantic tokens, so they already work in dark mode.

---

## 1. What exists

```
frontend/src/design/   ==  hr-frontend/src/design/      (byte-identical; tools/check_design_sync.py)
├─ index.css             layer order + imports (import FIRST in main.tsx)
├─ reset.css tokens.css base.css components.css motion.css patterns.css utilities.css
├─ fonts.ts              IBM Plex Sans 400/500/600 + Plex Mono 400/500 (latin + latin-ext, bundled from npm)
├─ icons.ts              the ONLY lucide-react import (0.460.0) + custom CircleHalf, LadderMark, GateOpen; <Icon>
├─ theme.ts              getPreference / setPreference / subscribe / useTheme / initTheme
├─ motion.ts             reduced, countUp, flip, useFlip, stagger, reveal, useInView, withViewTransition
├─ lib/labels.ts         tier + verdict labels by audience ('candidate' | 'staff')
├─ lib/format.ts         en-IN numbers, ₹, plural, relativeTime, formatDate, formatFileSize, initialsOf
├─ brand/                active.ts (one line) · presets/{neutral,plumbline,assay,gradus} · contrast.ts · vite-plugin-brand.ts
├─ components/           43 components + index.ts (import from '../design/components')
└─ gallery/              /__design (lazy-loaded; never in the main bundle)
public/theme-init.js     no-flash theme script (identical in both portals)
src/styles/legacy.css    @imports the old styles/*.css into @layer legacy (delete files as screens migrate)
```

Cascade layers (declared in `index.css`): `reset, tokens, base, legacy, components, patterns, pages, utilities`. Later layers win regardless of selector specificity, so a `.ui-*` component always beats legacy CSS, and your page CSS (in `@layer pages { … }`) beats both.

**Results:** both portals build with `npx --yes pnpm@10 install --frozen-lockfile && npx --yes pnpm@10 build`. The harness (`--theme both --axe`, 1440 and 390) shows 0 page errors, 0 overflow and **0 axe violations** on `design-system`, `login` and `jobs` in both portals. HR `job-applicants` still has the old `aria-prohibited-attr` on `.applicant-rank` (H-4, fixed by B4). JS gzip: candidate 125.9 kB (+12.9), HR 145.8 kB (+12.8), both within the +15 kB budget. CSS gzip will drop once the legacy files go.

---

## 2. Components: short API

Every component takes `className`. Variants use `data-variant` / `data-size`. State comes from native and ARIA attributes. `forwardRef` is used where a ref is useful (Button, IconButton, Input, Textarea, Select, Checkbox, Radio, RadioCard, Switch, Badge, Panel).

| Component | Key props | Notes |
|---|---|---|
| `Button` | `variant` primary·secondary·ghost·danger·danger-ghost·link, `size` sm·md·lg, `loading`, `icon`, `trailingIcon`, `block` | `loading` sets `aria-busy`, shows a spinner and locks the width. `buttonProps(variant,size)` styles a `<Link>`. Solid `danger` only inside a confirm dialog. |
| `IconButton` | `label` (required), `icon`, `size`, `variant` ghost·secondary, `loading`, `noTooltip` | `aria-label` + tooltip. 44 px hit area on touch. |
| `Field` | `label`, `hint`, `error`, `optional`, `required`, `hideLabel` | Wires id / `aria-describedby` / `aria-invalid` into the child Input/Textarea/Select automatically. `Fieldset` for groups. |
| `Input` | `leading`, `trailing` (affixes), `controlSize` | `₹`, `https://`, a search icon. |
| `Textarea` | `autoGrow` (default), `rows`, `maxRows`, `showCount` + `maxLength` | Counter "120 / 1,000" under the field. |
| `Select` | `controlSize` | Native `<select>` with a chevron. |
| `Checkbox` | `label`, `description`, `indeterminate` | Tick draws in. Without `label`, give it `aria-label`. |
| `RadioGroup` / `Radio` / `RadioCard` | group: `legend`, `value`, `onChange`, `direction`; radio: `value`, `label`, `description`; card: `+ icon` | RadioCard only when each option needs a description. |
| `Switch` | `label`, `description`, `labelFirst` | Changes a setting immediately; otherwise use Checkbox. |
| `SegmentedControl` | `label`, `options[{value,label,icon,iconOnly}]`, `value`, `onChange`, `size`, `block` | Native radios; sliding thumb. Referee answers, ratings, verdict picker, density. |
| `Tabs` + `TabPanel` | `label`, `idBase`, `items[{value,label,count,disabled}]`, `value`, `onChange`; panel: `idBase`, `value`, `active` | Roving focus, sliding indicator, scroll with edge fades. Keep `?tab=` in the URL in `onChange`. |
| `DataTable<T>` | `caption`, `columns[{key,header,cell,sortValue,numeric,align,width,primary,hideWhenStacked}]`, `rows` or `groups`, `rowKey`, `density`, `loading`, `empty`, `onRowActivate`, `activeRowKey`, `selectable`/`selected`/`onSelectionChange`, `toolbar`, `bulkBar`, `stickyHeader`, `maxHeight`, `manualSort`/`sort`/`onSortChange`, `animateReorder` | Sticky header (the wrapper scrolls; max height ≈ viewport), ↑/↓/Home/End/Enter/Space, stacked rows below 600 px. `useTableDensity()` + `<DensityToggle>`. |
| `Badge` | `tone` neutral·success·warning·danger·info·accent, `variant` solid·outline·struck·plain, `dot` (`'hollow'`) | Statuses only. Map statuses per DESIGN_SYSTEM §3.8. |
| `Tag`, `ReqMarker` | Tag: `icon`, `onRemove`; ReqMarker: `kind` must·nice·gate | Attributes without a box; removable chips get a background. |
| `VerdictChip` | `verdict`, `state` checking·done, `size`, `shortLabel` | Switch `state` from `checking` to `done` to play M6. |
| `EvidenceLadder` | `tier`, `audience`, `variant` chip·cell·legend, `gateOpen`, `previousTier`, `animate` 'view'·false, `requirement`, `compact` | Fill = tier ordinal; Self-claimed = dashed hollow rung. A tier change plays M5. `cell` + `requirement` gives a tooltip. |
| `CoverageMeter` | `verified`, `claimed` (0–100), `compact`, `note` | Count-up + bar fill on first view (M4). |
| `ProvenanceLine`, `Quote` | `icon`, `source`, `actor`, `at`, `href`; Quote `large` | Always directly under the quote. |
| `Section`, `SectionHeader`, `Panel`, `Stat`/`StatRow` | Section: `title`, `description`, `actions`, `level`; Panel: `title`, `actions`, `tone` danger, `flush` | **Sections, not cards.** Panel only for independently actionable or floating things. |
| `PageHeader` | `title`, `breadcrumb[{label,to}]`, `status`, `meta[]`, `description`, `actions`, `documentTitle` | Focusable H1 (`data-page-heading`); sets `document.title` to "{title} · {brand}". |
| `Breadcrumb`, `Pagination`, `Stepper`, `Timeline` | `items`; `page,pageSize,total,onPageChange`; `steps,current`; `items[{title,meta,at,state,children}]` | |
| `Dialog` | `open`, `onClose`, `title`, `description`, `size` sm·md·lg, `footer`, `busy`, `dismissible`, `alert`, `overline` | Native `<dialog>`: inert background, Escape, focus returns to the trigger. Initial focus goes to `[autoFocus]` or the first field/button. Destructive: `alert` + `autoFocus` on Cancel. |
| `Drawer` | `open`, `onClose`, `title`, `description`, `size` md·lg, `footer`, `busy`, `headerExtra` | Right drawer; a bottom sheet below 600 px. |
| `ToastProvider` / `useToast()` | `toast({tone,title,body,action,duration})`, `dismiss(id)` | Already mounted in both apps. Success/info auto-dismiss after 6 s; errors stay. |
| `Tooltip` | `content`, `side`, `align`, `delay`, `labelOnly` | One focusable child. Never essential content. |
| `Menu`, `Popover` | Menu: `trigger(props)`, `items[{label,icon,onSelect,to,danger,disabled,separated}]`; Popover: `anchor`, `open`, `onClose`, `label` | Menu is `role="menu"` with arrow keys. Spread the trigger props on a Button or IconButton. |
| `Alert` | `tone`, `title`, `action`, `onDismiss`, `variant` inline·banner, `live` | |
| `Skeleton`, `SkeletonText`, `LoadingRegion` | sizes; `lines`; `label` | Appear after 150 ms (CSS). Wrap page skeletons in `LoadingRegion`. |
| `EmptyState` | `icon`, `title`, `description`, `action`, `compact` | Left-aligned, one sentence, one action. |
| `FileDropzone` | `prompt`, `hint`, `accept`, `maxSize`, `multiple`, `maxFiles`, `onFiles`, `files[{id,name,size,progress,error}]`, `onRemove`, `error`, `compact` | Client-side type/size checks before `onFiles`. |
| `CodeField` (= `CopyField`) | `value`, `label`, `truncate` 'middle', `copyable` | Copy → check, "Copied" announced. |
| `Avatar` | `name`, `size` 24·28·32, `blind`, `label` | Blind gets EyeOff, never initials. |
| `Meter` | `label`, `value`, `max`, `target`, `targetLabel`, `tone`, `valueText` | Readiness, timer. |
| `CountUp` | `value`, `format`, `from`, `ms` | The final value is always in the accessible text. |
| `AppShell` | `portal`, `nav[{label,items[{to,label,icon,count,countLabel,end}]}]`, `homeHref`, `topbarStart`, `topbarEnd`, `sidebarFooter`, `banner`, `midMode` rail·drawer | Already wired in both `components/Layout.tsx`. |
| `AccountMenu`, `ThemeSwitch` | `name,email,detail,links,onSignOut,signingOut`; `variant` segmented·icon | Use the `icon` ThemeSwitch in the corner of auth pages and the referee page. |

Patterns (`patterns.css`): `.p-content > *` is capped at `--w-page` (1200 px). Put `data-width="wide|read|form|full"` (or class `p-wide`) on your page's root element to change that. `.p-bell` / `.p-bell-count` style the top-bar bell link.

Utilities: `.sr-only`, `.numeric`, `.mono`, `.truncate`, `.muted`, `.subtle`, `.measure`, `.stack-{2,4,6,8}`, `.cluster`, `.spread`, `.grow`, `.scroll-x`. Type classes: `.t-display .t-h1 .t-h2 .t-h3 .t-body-lg .t-body .t-strong .t-sm .t-label .t-caption .t-overline .t-mono`.

---

## 3. Theme and brand

- **Theme:** `localStorage['theme-preference']` = `system` (default, key removed) | `light` | `dark`. `public/theme-init.js` runs first in `<head>` (external file because the CSP is `script-src 'self'`; no nginx change was needed) and sets `data-theme` before paint. Use `useTheme()` or `setPreference(p, originElement)` (circular view-transition reveal from the toggle, M14; a 200 ms crossfade fallback; instant under reduced motion). Any element with `data-theme="light|dark"` forces that theme for its subtree (the gallery's side-by-side pair uses this).
- **Brand:** change ONE line in `src/design/brand/active.ts` (`neutral` = "RecruitPro" today; also `plumbline`, `assay`, `gradus`), then copy `src/design` to the other portal. At build time `vite-plugin-brand.ts` sets `<title>` and `%BRAND_NAME%` in `index.html`, injects the theme-color metas and `<style id="brand-tokens">`, and **fails the build** if the accent pairs miss AA (accent text on surface/canvas/subtle ≥ 4.5, on-accent on accent and hover ≥ 4.5, accent and tier fill on surface ≥ 3). All four presets pass. In code use `import brand from '../design/brand/active'` → `brand.name`, `brand.portalLabel`, `<brand.Mark size={20} />`. The `/__design` "Brand presets" section shows all four marks in both themes with their gate result.

## 4. Motion helpers

Durations and easings are tokens (`--dur-*`, `--ease-*`; `DUR`/`EASE` in JS). Everything checks `prefers-reduced-motion`; the harness captures reduced motion by default, so use `--motion` to see the real thing.

| Need | Use |
|---|---|
| Route entrance (M1) | automatic (AppShell keys the content by pathname) |
| List first load (M2) | `DataTable` does it; elsewhere `stagger(elements)` once, never on refetch or filter |
| Count-up (M4) | `<CountUp>`, `<CoverageMeter>`, `<Stat>`; or `countUp(el, to, {format})` |
| Ladder fill / tier upgrade (M5) | change `tier` on `<EvidenceLadder>` (or pass `previousTier`) |
| Verdict reveal (M6) | `<VerdictChip state="checking">` → `state="done"` |
| Rank reorder (M7) | `<DataTable animateReorder>`; elsewhere `useFlip(ref, orderKey)` + `data-flip-id`; add `.is-moved` to a row that changed group |
| Scroll reveal | `reveal(el, fn)` / `useInView(ref)` |
| Timer, final minute (M16) | add `.is-urgent` to the numeral |
| Theme switch (M14) | `setPreference(p, buttonEl)` |

## 5. Deviations from the spec (and why)

1. **Legacy layer position:** `legacy` sits after `base` (the spec only says "before components"), so old pages keep their own element styling during the migration.
2. **Legacy CSS was tokenised:** every colour literal in the old `styles/*.css` is now a semantic token, and the old shell rules were removed. This was needed so old pages do not fight the new tokens and read correctly in dark mode (it also fixes the HR contrast failures, H-2, on the screens checked).
3. **Input hover** uses `--fg-subtle`, not `--border-strong`: in the spec ramp `border-strong` (n-300) is *lighter* than `border-control` (n-400), so hover would fade the border.
4. **Input focus:** a 2 px accent edge (border + 1 px outline, offset 0) plus a 4 px `--accent-subtle` halo, instead of the 2 px / 2 px-offset ring. The ring stays tight to the field and keeps ≥ 3:1. Every other control uses the spec ring.
5. **Alerts and toasts** have a 1 px tone border (alerts) or no tone bar (toasts; the icon and progress line carry the tone) instead of the 3 px left bar. Coloured left-bar boxes were the most "generated-looking" element in the review screenshots, and the owner asked that nothing feel AI-made.
6. **Coverage hatch** stripes use `color-mix(accent 50%)` instead of `--accent-subtle-border`. The spec colour was nearly invisible on the track in both themes. The ladder strip cell outline uses `--border-default` instead of `--border-hairline` for the same reason.
7. **Dark `--tier-empty`** is d-400, not d-300, so empty rungs are visible on d-50.
8. **Stepper "done"** dots are filled in `--fg-default`, not the accent (§3.4 restricts the accent to its listed uses).
9. **`/__design` ships in production builds** (the brief asked for that; the plan said dev only). It is lazy-loaded, so it costs nothing until visited.
10. **Not built yet** (in the spec, not in this brief): `lib/fieldErrors.ts`, `lib/password.ts`, the stylelint rule, `Combobox`, the command palette and a shared `AuthLayout` pattern. HR's legacy auth screens show a neutral `LadderMark` in the old brand box until B1 replaces them.
11. `vite-plugin-brand.ts` is excluded from each `tsconfig.json`: it runs in Node, and adding `@types/node` would change timer types in the browser code.
12. Tokens added beyond Appendix A: `--bg-sidebar`, `--bg-stripe`, `--bg-overlay-hover`, `--fg-emphasis`, `--tooltip-border`, `--selection-bg`, `--scrollbar*`, `--inner-highlight`, `--danger-solid-hover`, `--tier-empty`, `--tier-claimed`, `--shadow-raised-edge`, `--text-*`/`--lh-*`, `--z-*`, `--page-pad`, `--travel`, `--stagger-step`, `--mark-*` (from presets).

## 6. Checklist for every screen you migrate

1. Build it from `src/design/components` and tokens only. Page CSS goes in its own file wrapped in `@layer pages { … }`, with no hex values, px font sizes or raw durations.
2. Use `PageHeader` (it gives the focusable H1 and the document title). Use Sections and hairlines, not cards. Use Panel only for actionable or floating things.
3. Use VerdictChip, EvidenceLadder, CoverageMeter, ProvenanceLine and CodeField for evidence. Never hand-write tier or verdict labels: use `audience`.
4. Statuses go in `Badge` per §3.8 (Not progressed = neutral). No emoji or Unicode glyphs for meaning; icons come only from `design/icons.ts`.
5. Provide loading (skeleton matching the layout), empty, error and success states, each with a fixture screen.
6. Keyboard: the primary task works with the keyboard alone; overlays close on Escape and return focus.
7. Delete the legacy rules your screen no longer uses from `src/styles/*.css`. When a file is empty, remove it and its line in `src/styles/legacy.css`.
8. Copy page copy that names the product through `brand.name`. **Do not** change consent sentences (versioned server-side) or the protocol constants (`X-RecruitPro-Portal`, `recruitpro-verification`).
9. If you touch `src/design`, change it in one portal, copy the folder to the other, and run `python3 tools/check_design_sync.py`. Then rebuild both portals and capture `design-system`.
10. Gate: `node snap.mjs --portal <p> --dist <dist> --theme both --axe --only <screens>` exits 0 at 1440 and 390, in light and dark. Look at every PNG. Phone captures taller than about 8,192 px repeat after that line (a harness limitation; see the harness README).
