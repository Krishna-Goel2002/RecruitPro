/*
 * /__design — the living style guide (hidden route, not linked from navigation; present in dev and prod builds).
 * Renders every design-system component and state in the current theme, plus a side-by-side light/dark pair.
 * The screenshot harness captures it as the `design-system` screen in both portals.
 */
import { useMemo, useState, type CSSProperties, type ReactNode } from 'react'
import brand from '../brand/active'
import { checkBrandContrast } from '../brand/contrast'
import type { BrandTokens, MarkProps } from '../brand/types'
import AssayMark from '../brand/presets/assay/Mark'
import assayTokens from '../brand/presets/assay/tokens.json'
import GradusMark from '../brand/presets/gradus/Mark'
import gradusTokens from '../brand/presets/gradus/tokens.json'
import NeutralMark from '../brand/presets/neutral/Mark'
import neutralTokens from '../brand/presets/neutral/tokens.json'
import PlumblineMark from '../brand/presets/plumbline/Mark'
import plumblineTokens from '../brand/presets/plumbline/tokens.json'
import {
  AccountMenu, Alert, AppShell, Avatar, Badge, Breadcrumb, Button, Checkbox, CodeField, CoverageMeter, DataTable,
  DensityToggle, Dialog, Drawer, EmptyState, EvidenceLadder, Field, FileDropzone, IconButton, Input, Menu, Meter,
  PageHeader, Pagination, Panel, ProvenanceLine, Quote, Radio, RadioCard, RadioGroup, ReqMarker, Section,
  SegmentedControl, Select, Skeleton, SkeletonText, Stat, StatRow, Stepper, Switch, Tabs, TabPanel, Tag, Textarea,
  ThemeSwitch, Timeline, ToastProvider, Tooltip, VerdictChip, useTableDensity, useToast, type Column,
  type DropzoneFile, type RowGroup,
} from '../components'
import {
  ArrowUpRight, Bell, BriefcaseBusiness, Copy, Download, Ellipsis, EyeOff, FileText, Github, Globe,
  Inbox, KeyRound, LayoutGrid, ListChecks, Pencil, Plus, RefreshCw, ScrollText, Search, ShieldCheck, SlidersHorizontal,
  Trash2, UserRoundCheck, Users,
} from '../icons'
import { formatMoney, relativeTime } from '../lib/format'
import { TIERS, VERDICTS, type Tier, type Verdict } from '../lib/labels'
import { useTheme } from '../theme'
import './gallery.css'

const PRESETS: { tokens: BrandTokens; Mark: (p: MarkProps) => JSX.Element }[] = [
  { tokens: neutralTokens as BrandTokens, Mark: NeutralMark },
  { tokens: plumblineTokens as BrandTokens, Mark: PlumblineMark },
  { tokens: assayTokens as BrandTokens, Mark: AssayMark },
  { tokens: gradusTokens as BrandTokens, Mark: GradusMark },
]

const SECTIONS = [
  { id: 'foundations', label: 'Foundations', icon: LayoutGrid },
  { id: 'actions', label: 'Actions', icon: SlidersHorizontal },
  { id: 'forms', label: 'Forms', icon: Pencil },
  { id: 'navigation', label: 'Navigation', icon: ListChecks },
  { id: 'evidence', label: 'Evidence', icon: ShieldCheck },
  { id: 'data', label: 'Data', icon: Users },
  { id: 'feedback', label: 'Feedback', icon: Bell },
  { id: 'overlays', label: 'Overlays', icon: ScrollText },
  { id: 'brand', label: 'Brand', icon: KeyRound },
]

/* ------------------------------------------------------------------------------------------------ helpers */
function Specimen({ label, children, stack }: { label: string; children: ReactNode; stack?: boolean }) {
  return (
    <div className="g-specimen">
      <p className="g-specimen-label">{label}</p>
      <div className={stack ? 'g-specimen-body g-stack' : 'g-specimen-body'}>{children}</div>
    </div>
  )
}

function Swatch({ token, note }: { token: string; note?: string }) {
  return (
    <div className="g-swatch">
      <span className="g-swatch-chip" style={{ background: `var(${token})` }} />
      <span className="g-swatch-name mono">{token}</span>
      {note && <span className="g-swatch-note">{note}</span>}
    </div>
  )
}

/* ------------------------------------------------------------------------------------------------ data */
interface Applicant {
  id: number
  rank: number | null
  name: string
  blind: boolean
  status: { label: string; tone: 'neutral' | 'info' | 'success' }
  strip: { req: string; tier: Tier | null; must: boolean; gate?: boolean }[]
  verified: number
  claimed: number
  applied: string
}

const now = Date.now()
const h = (n: number) => new Date(now - n * 3600_000).toISOString()

const REQS = ['Java 17 and Spring Boot', 'PostgreSQL tuning', 'Backend in production', 'Kubernetes', 'AWS Developer']
const strip = (tiers: (Tier | null)[], gate?: number) =>
  tiers.map((tier, i) => ({ req: REQS[i], tier, must: i < 3, gate: gate === i }))

const APPLICANTS: Applicant[] = [
  { id: 3014, rank: 1, name: 'Applicant 3014', blind: true, status: { label: 'Submitted', tone: 'neutral' }, strip: strip(['DEMONSTRATED', 'CORROBORATED', 'CORROBORATED', 'RESUME_STATED', null]), verified: 78, claimed: 92, applied: h(26) },
  { id: 3001, rank: 2, name: 'Meera Iyer', blind: false, status: { label: 'Shortlisted', tone: 'info' }, strip: strip(['CORROBORATED', 'RESUME_STATED', 'INTERVIEW_VERIFIED', 'SELF_CLAIMED', 'CORROBORATED']), verified: 64, claimed: 88, applied: h(120) },
  { id: 3022, rank: 3, name: 'Applicant 3022', blind: true, status: { label: 'Submitted', tone: 'neutral' }, strip: strip(['RESUME_STATED', 'CORROBORATED', 'RESUME_STATED', null, null]), verified: 41, claimed: 70, applied: h(9) },
  { id: 3031, rank: null, name: 'Applicant 3031', blind: true, status: { label: 'Submitted', tone: 'neutral' }, strip: strip(['SELF_CLAIMED', 'RESUME_STATED', 'CORROBORATED', 'RESUME_STATED', null], 0), verified: 22, claimed: 74, applied: h(52) },
  { id: 3040, rank: null, name: 'Applicant 3040', blind: true, status: { label: 'Submitted', tone: 'neutral' }, strip: strip([null, 'RESUME_STATED', 'SELF_CLAIMED', null, null], 0), verified: 8, claimed: 46, applied: h(3) },
]

function EvidenceStrip({ items }: { items: Applicant['strip'] }) {
  const evidenced = items.filter(i => i.tier && i.tier !== 'SELF_CLAIMED').length
  const gate = items.find(i => i.gate)
  return (
    <span
      className="g-strip"
      role="group"
      aria-label={`${evidenced} of ${items.length} requirements evidenced${gate ? `; ranking gate open: ${gate.req}` : ''}`}
    >
      {items.map((i, n) => (
        <span key={i.req} className="g-strip-cell" data-gap={n === 3 || undefined}>
          <EvidenceLadder variant="cell" tier={i.tier} gateOpen={i.gate} requirement={i.req} />
        </span>
      ))}
    </span>
  )
}

/* ------------------------------------------------------------------------------------------------ page */
export default function DesignGallery({ portal = 'candidate' }: { portal?: 'candidate' | 'staff' }) {
  return (
    <ToastProvider>
      <GalleryShell portal={portal} />
    </ToastProvider>
  )
}

function GalleryShell({ portal }: { portal: 'candidate' | 'staff' }) {
  const { resolved } = useTheme()
  const [active] = useState('foundations')
  return (
    <AppShell
      portal={portal}
      homeHref="/__design"
      midMode={portal === 'staff' ? 'rail' : 'drawer'}
      nav={[
        { label: 'Design system', items: SECTIONS.map(s => ({ to: '/__design', href: `#${s.id}`, current: s.id === active, label: s.label, icon: s.icon })) },
        {
          label: 'Shell demo',
          items: [
            { to: '/__design', href: '#data', label: 'Applicants', icon: Users, count: 12, countLabel: 'new' },
            { to: '/__design', href: '#feedback', label: 'Inbox', icon: Inbox, count: 3, countLabel: 'unread' },
          ],
        },
      ]}
      topbarEnd={
        <>
          <Tooltip content="Notifications" labelOnly>
            <a href="#feedback" className="p-bell" aria-label="Notifications, 3 unread">
              <Bell size={16} strokeWidth={1.75} absoluteStrokeWidth aria-hidden="true" />
              <span className="p-bell-count" aria-hidden="true">3</span>
            </a>
          </Tooltip>
          <AccountMenu
            name="Priya Raman"
            email="priya.raman@example.com"
            detail={portal === 'staff' ? 'HR manager · Northwind Labs' : 'Candidate'}
            links={[{ label: 'Profile', to: '/__design', icon: UserRoundCheck }]}
            onSignOut={() => undefined}
          />
        </>
      }
      sidebarFooter={portal === 'staff' ? 'Northwind Labs' : undefined}
    >
      <div className="g-page p-wide">
        <PageHeader
          title="Design system"
          breadcrumb={[{ label: 'Design system', to: '/__design' }, { label: 'Foundation' }]}
          status={<Badge tone="success">Foundation v1</Badge>}
          meta={[`Brand preset: ${brand.id}`, `Theme: ${resolved}`, portal === 'staff' ? 'Staff portal' : 'Candidate portal']}
          description="Every component and state the page engineers build with. Semantic tokens only; meaning never rides on colour alone."
          actions={<ThemeSwitch />}
        />
        <ThemePair />
        <Foundations />
        <Actions />
        <Forms />
        <Navigation />
        <EvidenceSection />
        <DataSection />
        <Feedback />
        <Overlays />
        <BrandSection />
      </div>
    </AppShell>
  )
}

/* ------------------------------------------------------------------------------------------------ theme pair */
function PairContent({ theme }: { theme: 'light' | 'dark' }) {
  return (
    <div className="g-pair-pane" data-theme={theme}>
      <p className="g-pair-label">{theme === 'light' ? 'Light' : 'Dark'}</p>
      <div className="g-pair-row">
        <span className="g-pair-req">
          <ReqMarker kind="must" />
          <span className="t-strong">Java 17 and Spring Boot</span>
        </span>
        <EvidenceLadder tier="CORROBORATED" animate={false} />
      </div>
      <div className="g-pair-row">
        <VerdictChip verdict="VERIFIED" size="sm" />
        <VerdictChip verdict="PARTLY_VERIFIED" size="sm" />
        <VerdictChip verdict="NOT_FOUND" size="sm" shortLabel="Not found" />
      </div>
      <CoverageMeter verified={64} claimed={88} note={null} />
      <div className="g-pair-row">
        <Button variant="primary" size="sm">Shortlist</Button>
        <Button size="sm">Add note</Button>
        <Button variant="danger-ghost" size="sm">Reject</Button>
      </div>
    </div>
  )
}

function ThemePair() {
  return (
    <div className="g-pair" aria-label="Light and dark specimens" role="group">
      <PairContent theme="light" />
      <PairContent theme="dark" />
    </div>
  )
}

/* ------------------------------------------------------------------------------------------------ foundations */
function Foundations() {
  return (
    <Section id="foundations" title="Foundations" description="Neutral ramps are hand-tuned per theme. Components only reference the semantic aliases below.">
      <div className="g-cols">
        <Specimen label="Surfaces" stack>
          <Swatch token="--bg-canvas" note="behind everything" />
          <Swatch token="--bg-surface" note="content sheet" />
          <Swatch token="--bg-sunken" note="table header, code" />
          <Swatch token="--bg-raised" note="dialogs, drawers" />
          <Swatch token="--bg-overlay" note="menus, toasts" />
          <Swatch token="--bg-selected" note="selected row, nav" />
        </Specimen>
        <Specimen label="Text and lines" stack>
          <Swatch token="--fg-default" note="body text, data" />
          <Swatch token="--fg-muted" note="labels, secondary" />
          <Swatch token="--fg-subtle" note="hints, AA on canvas" />
          <Swatch token="--border-hairline" note="dividers" />
          <Swatch token="--border-default" note="menus, table edge" />
          <Swatch token="--border-control" note="≥ 3 : 1 controls" />
        </Specimen>
        <Specimen label="Accent and status" stack>
          <Swatch token="--accent" note="primary, focus, tiers" />
          <Swatch token="--success-fg" note="Verified" />
          <Swatch token="--warning-fg" note="Partly verified" />
          <Swatch token="--danger-fg" note="Contradicted" />
          <Swatch token="--info-fg" note="Shortlisted" />
          <Swatch token="--tier-fill" note="evidence rungs" />
        </Specimen>
      </div>

      <Specimen label="Type scale — IBM Plex Sans, tabular numerals in data" stack>
        <p className="t-display">Hiring on evidence</p>
        <p className="t-h1">Senior Backend Engineer</p>
        <p className="t-h2">Requirements and evidence</p>
        <p className="t-h3">Java 17 and Spring Boot</p>
        <p className="t-body-lg g-measure">Body large carries long reading: job descriptions, verdict reasons and the referee page. Maximum line length is 72 characters.</p>
        <p className="t-body">Body is the default interface text at 14 / 20.</p>
        <p className="t-sm muted">Body small · provenance, secondary lines · 13 / 18</p>
        <p className="t-caption muted">Caption · badges, chips, counts · the minimum size anywhere</p>
        <p className="t-overline">Step 2 of 3</p>
        <p className="t-mono">sha256:9f2c4e1ab07d 4f2a9c1 0123456789</p>
      </Specimen>

      <div className="g-cols">
        <Specimen label="Spacing (4 px base)" stack>
          {[2, 4, 6, 8, 12, 16, 20, 24, 32, 40, 48, 64].map(s => (
            <div key={s} className="g-space-row">
              <span className="g-space-bar" style={{ width: s }} />
              <span className="mono t-sm muted">{s}</span>
            </div>
          ))}
        </Specimen>
        <Specimen label="Radii and elevation" stack>
          <div className="g-radii">
            {(['xs', 'sm', 'md', 'lg'] as const).map(r => (
              <span key={r} className="g-radius" style={{ borderRadius: `var(--r-${r})` }}>
                <span className="mono t-caption">r-{r}</span>
              </span>
            ))}
          </div>
          <div className="g-elev">
            <span className="g-elev-box" data-level="surface">Surface<br /><span className="muted t-caption">hairline, no shadow</span></span>
            <span className="g-elev-box" data-level="pop">Overlay<br /><span className="muted t-caption">shadow-pop</span></span>
            <span className="g-elev-box" data-level="dialog">Raised<br /><span className="muted t-caption">shadow-dialog</span></span>
          </div>
        </Specimen>
        <Specimen label="Motion tokens" stack>
          <MotionDemo />
        </Specimen>
      </div>
    </Section>
  )
}

function MotionDemo() {
  const [on, setOn] = useState(false)
  const rows: [string, string, string][] = [
    ['--ease-standard', '200 ms', 'moves, most enters'],
    ['--ease-decelerate', '280 ms', 'reveals, bar fills'],
    ['--ease-exit', '140 ms', 'everything leaving'],
    ['--ease-settle', '220 ms', 'new rung, switch knob'],
  ]
  return (
    <div className="g-motion">
      {rows.map(([ease, dur, use]) => (
        <div key={ease} className="g-motion-row">
          <span className="g-motion-track">
            <span className="g-motion-dot" data-on={on || undefined} style={{ transitionTimingFunction: `var(${ease})`, transitionDuration: dur.replace(' ms', 'ms') }} />
          </span>
          <span className="mono t-caption">{ease}</span>
          <span className="t-caption muted">{use}</span>
        </div>
      ))}
      <Button size="sm" icon={RefreshCw} onClick={() => setOn(v => !v)}>
        Play curves
      </Button>
    </div>
  )
}

/* ------------------------------------------------------------------------------------------------ actions */
function Actions() {
  const [busy, setBusy] = useState(false)
  return (
    <Section id="actions" title="Actions" description="One primary per region; verb plus object. Danger solid only inside a confirmation dialog.">
      <Specimen label="Variants">
        <Button variant="primary">Share with employers</Button>
        <Button>Add evidence</Button>
        <Button variant="ghost">Cancel</Button>
        <Button variant="danger-ghost" icon={Trash2}>Withdraw</Button>
        <Button variant="danger">Delete account</Button>
        <Button variant="link" trailingIcon={ArrowUpRight}>View source</Button>
      </Specimen>
      <Specimen label="Sizes, icons, states">
        <Button variant="primary" size="sm" icon={Plus}>Create job</Button>
        <Button variant="primary" icon={Plus}>Create job</Button>
        <Button variant="primary" size="lg">Sign in</Button>
        <Button
          variant="primary"
          loading={busy}
          icon={RefreshCw}
          onClick={() => {
            setBusy(true)
            window.setTimeout(() => setBusy(false), 1600)
          }}
        >
          Re-run matching
        </Button>
        <Button loading>Saving</Button>
        <Button variant="primary" disabled>Submit</Button>
        <Button disabled>Export</Button>
      </Specimen>
      <Specimen label="Icon buttons (tooltip + aria-label)">
        <IconButton label="Edit job" icon={Pencil} />
        <IconButton label="Download audit CSV" icon={Download} />
        <IconButton label="Copy link" icon={Copy} variant="secondary" />
        <IconButton label="More actions" icon={Ellipsis} size="sm" />
        <IconButton label="Delete" icon={Trash2} disabled />
      </Specimen>
    </Section>
  )
}

/* ------------------------------------------------------------------------------------------------ forms */
function Forms() {
  const [mode, setMode] = useState('visible')
  const [answer, setAnswer] = useState<string | null>('confirm')
  const [kind, setKind] = useState('github')
  const [notify, setNotify] = useState(true)
  const [text, setText] = useState('I led the migration of the settlement ledger to PostgreSQL 15 and cut p95 latency by 38%.')
  return (
    <Section id="forms" title="Forms" description="Label, control, hint, error. Inside a Field every control is wired to its label, hint and error automatically.">
      <div className="g-form-grid">
        <Field label="Job title" hint="Shown to candidates exactly as written.">
          <Input placeholder="Senior Backend Engineer" defaultValue="Senior Backend Engineer" />
        </Field>
        <Field label="Work email" error="Enter an email address like name@company.com.">
          <Input type="email" defaultValue="priya@" />
        </Field>
        <Field label="Minimum salary" optional hint="Per year, before tax.">
          <Input leading="₹" inputMode="numeric" defaultValue={formatMoney(2800000).replace('₹', '')} />
        </Field>
        <Field label="Portfolio" optional>
          <Input leading={<span className="mono t-sm">https://</span>} placeholder="meera.dev" />
        </Field>
        <Field label="Search applicants" hideLabel>
          <Input leading={<Search size={16} strokeWidth={1.75} absoluteStrokeWidth aria-hidden="true" />} placeholder="Search by id or requirement" />
        </Field>
        <Field label="Experience level">
          <Select defaultValue="SENIOR">
            <option value="ENTRY">Entry</option>
            <option value="MID">Mid</option>
            <option value="SENIOR">Senior</option>
            <option value="LEAD">Lead</option>
          </Select>
        </Field>
        <Field label="Cover note" hint="Grows as you type." className="g-span-2">
          <Textarea value={text} onChange={e => setText(e.target.value)} maxLength={1000} showCount rows={3} />
        </Field>
        <Field label="Read-only reference">
          <Input readOnly value="REQ-101-v3" />
        </Field>
        <Field label="Disabled">
          <Input disabled value="Locked after publishing" />
        </Field>
      </div>

      <div className="g-cols">
        <Specimen label="Checkbox" stack>
          <Checkbox label="Must-have" defaultChecked />
          <Checkbox label="Ranking gate" description="Only for must-haves. Applicants without evidence stay out of the ranked group." />
          <Checkbox label="Select all (mixed)" indeterminate />
          <Checkbox label="Disabled" disabled />
          <Checkbox label="Disabled and checked" disabled defaultChecked />
        </Specimen>
        <Specimen label="Radio group" stack>
          <RadioGroup legend="Verification mode" value={mode} onChange={setMode}>
            <Radio value="shadow" label="Shadow" description="Agents run; results are hidden from reviewers." />
            <Radio value="visible" label="Visible" description="Reviewers see results after a person confirms them." />
            <Radio value="off" label="Off" disabled />
          </RadioGroup>
        </Specimen>
        <Specimen label="Switch (applies immediately)" stack>
          <Switch label="Email me when a check finishes" checked={notify} onChange={e => setNotify(e.target.checked)} />
          <Switch label="GitHub connector" description="Reads public repositories only." defaultChecked labelFirst />
          <Switch label="Open Badges connector" labelFirst />
          <Switch label="Disabled" disabled />
        </Specimen>
      </div>

      <div className="g-cols">
        <Specimen label="Segmented control" stack>
          <SegmentedControl
            label="Referee answer"
            value={answer}
            onChange={setAnswer}
            options={[
              { value: 'confirm', label: 'Confirm' },
              { value: 'partly', label: 'Partly' },
              { value: 'unsure', label: "Can't confirm" },
              { value: 'disagree', label: 'Disagree' },
            ]}
          />
          <SegmentedControl
            label="Rating"
            size="sm"
            value="3"
            onChange={() => undefined}
            options={['1', '2', '3', '4'].map(v => ({ value: v, label: v }))}
          />
        </Specimen>
        <Specimen label="Radio cards (multi-line choices only)" stack>
          <RadioGroup legend="Source kind" value={kind} onChange={setKind} hideLegend>
            <RadioCard value="github" icon={Github} label="GitHub" description="Sign in to prove ownership; we read public repositories." />
            <RadioCard value="website" icon={Globe} label="Website" description="Prove ownership with a DNS record, a file or a code on a page." />
          </RadioGroup>
        </Specimen>
      </div>
    </Section>
  )
}

/* ------------------------------------------------------------------------------------------------ navigation */
function Navigation() {
  const [tab, setTab] = useState('evidence')
  const [page, setPage] = useState(3)
  return (
    <Section id="navigation" title="Navigation" description="Underline tabs with a sliding indicator, breadcrumb, pagination, stepper and the overflow menu.">
      <Tabs
        label="Applicant sections"
        idBase="g-tabs"
        value={tab}
        onChange={setTab}
        items={[
          { value: 'evidence', label: 'Evidence', count: 6 },
          { value: 'claims', label: 'Claims', count: 4 },
          { value: 'work', label: 'Work samples', count: 1 },
          { value: 'interviews', label: 'Interviews' },
          { value: 'verification', label: 'Verification', count: 3 },
          { value: 'fairness', label: 'Fairness', disabled: true },
        ]}
      />
      {['evidence', 'claims', 'work', 'interviews', 'verification'].map(v => (
        <TabPanel key={v} idBase="g-tabs" value={v} active={tab}>
          <p className="muted">Panel content for “{v}”. The panel crossfades in over 140 ms.</p>
        </TabPanel>
      ))}
      <div className="g-cols g-mt">
        <Specimen label="Breadcrumb" stack>
          <Breadcrumb items={[{ label: 'Jobs', to: '/__design' }, { label: 'Senior Backend Engineer', to: '/__design' }, { label: 'Applicant 3014' }]} />
        </Specimen>
        <Specimen label="Stepper" stack>
          <Stepper steps={['Resume', 'Review', 'Sent']} current={1} label="Application progress" />
        </Specimen>
        <Specimen label="Overflow menu">
          <Menu
            label="Job actions"
            trigger={props => (
              <Button {...props} icon={Ellipsis}>
                More
              </Button>
            )}
            items={[
              { label: 'Edit job', icon: Pencil },
              { label: 'Export audit CSV', icon: Download },
              { label: 'Close job', icon: Trash2, danger: true, separated: true },
            ]}
          />
        </Specimen>
      </div>
      <Pagination page={page} pageSize={25} total={238} onPageChange={setPage} />
    </Section>
  )
}

/* ------------------------------------------------------------------------------------------------ evidence */
function EvidenceSection() {
  const [tier, setTier] = useState<Tier>('RESUME_STATED')
  const [checking, setChecking] = useState(false)
  const [verdict, setVerdict] = useState<Verdict>('VERIFIED')
  const next = TIERS[Math.min(TIERS.indexOf(tier) + 1, TIERS.length - 1)]
  return (
    <Section id="evidence" title="Evidence" description="Tiers are ordinal (fill level); verdicts are semantic (shape + icon + word). The two systems never share a hue.">
      <Specimen label="Verdict chips — identical in both portals">
        {VERDICTS.map(v => (
          <VerdictChip key={v} verdict={v} />
        ))}
        <VerdictChip state="checking" />
      </Specimen>
      <Specimen label="Verdict reveal (M6): a check finishes">
        <VerdictChip verdict={verdict} state={checking ? 'checking' : 'done'} />
        <Button
          size="sm"
          icon={RefreshCw}
          onClick={() => {
            setChecking(true)
            window.setTimeout(() => {
              setVerdict(v => (v === 'VERIFIED' ? 'PARTLY_VERIFIED' : 'VERIFIED'))
              setChecking(false)
            }, 1200)
          }}
        >
          Re-check
        </Button>
      </Specimen>
      <div className="g-cols">
        <Specimen label="Evidence ladder — staff labels" stack>
          {TIERS.map(t => (
            <EvidenceLadder key={t} tier={t} audience="staff" />
          ))}
          <EvidenceLadder tier={null} audience="staff" />
        </Specimen>
        <Specimen label="Evidence ladder — candidate labels" stack>
          {TIERS.map(t => (
            <EvidenceLadder key={t} tier={t} audience="candidate" />
          ))}
          <EvidenceLadder tier={null} audience="candidate" />
        </Specimen>
        <Specimen label="Tier upgrade (M5)" stack>
          <EvidenceLadder tier={tier} audience="staff" />
          <div className="cluster">
            <Button size="sm" onClick={() => setTier(next)} disabled={tier === 'INTERVIEW_VERIFIED'}>
              Verify claim
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setTier('RESUME_STATED')}>
              Reset
            </Button>
          </div>
        </Specimen>
      </div>
      <Specimen label="Ladder legend" stack>
        <EvidenceLadder variant="legend" tier={null} audience="candidate" />
      </Specimen>
      <div className="g-cols">
        <Specimen label="Coverage (count-up on first view)" stack>
          <CoverageMeter verified={64} claimed={88} />
        </Specimen>
        <Specimen label="Requirement markers and tags" stack>
          <div className="cluster">
            <ReqMarker kind="must" />
            <ReqMarker kind="nice" />
            <ReqMarker kind="gate" />
          </div>
          <div className="cluster">
            <Tag icon={BriefcaseBusiness}>Full-time</Tag>
            <Tag icon={Globe}>Bengaluru (hybrid)</Tag>
            <Tag icon={EyeOff}>Blind</Tag>
            <Tag onRemove={() => undefined}>Amazon ECS</Tag>
            <Tag onRemove={() => undefined}>Nomad</Tag>
          </div>
        </Specimen>
      </div>
      <Specimen label="Quote and provenance" stack>
        <div className="g-measure">
          <Quote>Cut p95 query time 38% by partitioning ledger tables.</Quote>
          <ProvenanceLine icon={Github} source="GitHub · ana-r/ledger-sync" actor="checked by agent, confirmed by Priya S." at={h(5)} href="https://example.com" />
        </div>
      </Specimen>
    </Section>
  )
}

/* ------------------------------------------------------------------------------------------------ data */
function DataSection() {
  const [density, setDensity] = useTableDensity('compact')
  const [selected, setSelected] = useState<Set<string | number>>(new Set())
  const [active, setActive] = useState<number | null>(3001)
  const columns: Column<Applicant>[] = useMemo(
    () => [
      { key: 'rank', header: '#', width: '44px', numeric: true, sortValue: a => a.rank ?? 999, cell: a => (a.rank ? <span className="numeric">{a.rank}</span> : <span><span aria-hidden="true">—</span><span className="sr-only">Not ranked</span></span>) },
      {
        key: 'name', header: 'Applicant', primary: true, sortValue: a => a.name,
        cell: a => (
          <span className="g-applicant">
            <Avatar name={a.name} blind={a.blind} size={24} />
            <span>{a.name}</span>
            {a.blind && <Tag icon={EyeOff}>Blind</Tag>}
          </span>
        ),
      },
      { key: 'status', header: 'Status', sortValue: a => a.status.label, cell: a => <Badge tone={a.status.tone}>{a.status.label}</Badge> },
      { key: 'strip', header: 'Evidence', hideWhenStacked: true, cell: a => <EvidenceStrip items={a.strip} /> },
      { key: 'verified', header: 'Verified', numeric: true, sortValue: a => a.verified, cell: a => `${a.verified}%` },
      { key: 'claimed', header: 'With claims', numeric: true, sortValue: a => a.claimed, hideWhenStacked: true, cell: a => `${a.claimed}%` },
      { key: 'applied', header: 'Applied', sortValue: a => a.applied, cell: a => <time dateTime={a.applied}>{relativeTime(a.applied)}</time> },
    ],
    [],
  )
  const groups: RowGroup<Applicant>[] = [
    { id: 'ranked', title: 'Ranked', description: 'Every must-have has evidence.', rows: APPLICANTS.filter(a => a.rank) },
    { id: 'gated', title: 'Must-haves not yet verified', description: 'A ranking gate is open; verify the claim to rank.', rows: APPLICANTS.filter(a => !a.rank) },
  ]
  return (
    <Section id="data" title="Data" description="Sticky header, sortable columns, density, keyboard rows (↑ ↓ Enter), selection with a bulk bar, group rows, skeleton and empty states.">
      <DataTable
        caption="Applicants for Senior Backend Engineer"
        columns={columns}
        groups={groups}
        rowKey={a => a.id}
        density={density}
        selectable
        selected={selected}
        onSelectionChange={setSelected}
        activeRowKey={active}
        onRowActivate={a => setActive(a.id)}
        toolbar={
          <>
            <span className="t-sm muted">5 applicants · 2 groups</span>
            <span className="grow" />
            <DensityToggle value={density} onChange={setDensity} />
            <Button size="sm" icon={RefreshCw}>Re-run matching</Button>
          </>
        }
        bulkBar={(n, clear) => (
          <>
            <span>{n} selected</span>
            <Button size="sm" variant="ghost">Assign work sample</Button>
            <span className="grow" />
            <Button size="sm" variant="ghost" onClick={clear}>Clear</Button>
          </>
        )}
      />
      <div className="g-cols g-mt">
        <Specimen label="Loading (skeleton rows)" stack>
          <DataTable caption="Loading jobs" columns={columns.slice(0, 3)} rows={[]} rowKey={a => a.id} loading loadingRows={3} stickyHeader={false} />
        </Specimen>
        <Specimen label="Empty" stack>
          <DataTable
            caption="No applicants"
            columns={columns.slice(0, 3)}
            rows={[]}
            rowKey={a => a.id}
            stickyHeader={false}
            empty={<EmptyState compact title="No applicants yet" description="Applications appear here as soon as candidates apply." />}
          />
        </Specimen>
      </div>
      <div className="g-cols g-mt">
        <Specimen label="Stats (unboxed, count-up once)" stack>
          <StatRow>
            <Stat label="Open jobs" value={6} />
            <Stat label="Applicants" value={238} delta={{ value: '12 this week', direction: 'up' }} />
            <Stat label="Waiting for review" value={14} />
          </StatRow>
        </Specimen>
        <Specimen label="Meters (readiness, timer)" stack>
          <Meter label="Labelled shadow results" value={118} max={150} target={150} targetLabel="Target 150" />
          <Meter label="Time left" value={4} max={90} tone="warning" valueText="4 min left" />
        </Specimen>
      </div>
    </Section>
  )
}

/* ------------------------------------------------------------------------------------------------ feedback */
function Feedback() {
  const { toast } = useToast()
  const [files, setFiles] = useState<DropzoneFile[]>([
    { id: 1, name: 'Meera_Iyer_Backend_2026.pdf', size: 184_320 },
    { id: 2, name: 'Portfolio_case_study.pdf', size: 2_400_000, progress: 0.62 },
  ])
  return (
    <Section id="feedback" title="Feedback" description="Alerts sit in place; toasts confirm; skeletons hold the final layout; empty states say why and what next.">
      <div className="g-stack">
        <Alert tone="info" title="Shadow mode is on.">Agents run checks, but reviewers do not see the results yet.</Alert>
        <Alert tone="success" title="Ownership proven.">GitHub account ana-r is linked to this candidate.</Alert>
        <Alert tone="warning" title="Matching is taking longer than usual." action={<Button size="sm">Check again</Button>} />
        <Alert tone="danger" title="We could not load the applicants." onDismiss={() => undefined}>The service did not answer. The list below may be out of date.</Alert>
        <Alert variant="banner" tone="warning" title="Verify your email address." action={<Button size="sm" variant="ghost">Resend link</Button>} onDismiss={() => undefined}>
          Some employers accept applications only from verified addresses.
        </Alert>
      </div>
      <Specimen label="Toasts (bottom-right; success auto-dismisses, errors stay)">
        <Button onClick={() => toast({ tone: 'success', title: 'Marked Verified.', body: 'Your name and the time are saved with the citation.', action: { label: 'Undo', onClick: () => undefined } })}>
          Success toast
        </Button>
        <Button onClick={() => toast({ tone: 'info', title: 'Matching started.' })}>Info toast</Button>
        <Button onClick={() => toast({ tone: 'danger', title: 'Could not save the review.', body: 'Check your connection and try again.' })}>Error toast</Button>
      </Specimen>
      <div className="g-cols">
        <Specimen label="Skeleton (appears after 150 ms)" stack>
          <Skeleton width={160} height={14} />
          <SkeletonText lines={3} />
        </Specimen>
        <Specimen label="Empty state" stack>
          <EmptyState icon={Search} title="No jobs match ‘zzzz’" description="Try a shorter search or clear the filters." action={<Button size="sm">Clear filters</Button>} compact />
        </Specimen>
      </div>
      <div className="g-cols">
        <Specimen label="File dropzone" stack>
          <FileDropzone
            prompt="Drop a PDF, DOC or DOCX here, or"
            hint="Up to 5 MB. We read the text; the file is never shared without your consent."
            accept=".pdf,.doc,.docx"
            maxSize={5 * 1024 * 1024}
            onFiles={fs => setFiles(list => [...list, ...fs.map((f, i) => ({ id: Date.now() + i, name: f.name, size: f.size }))])}
            files={files}
            onRemove={id => setFiles(list => list.filter(f => f.id !== id))}
          />
        </Specimen>
        <Specimen label="Code field and copy" stack>
          <CodeField label="TXT record value" value="recruitpro-verification=8f3a91c2d7e64b0f" />
          <CodeField label="Evidence fingerprint" value="sha256:9f2c4e1ab07d5c33e8a1f02b6d4c9e7a1b3f5d7c9e0a2b4c" truncate="middle" />
        </Specimen>
      </div>
      <div className="g-cols">
        <Specimen label="Timeline" stack>
          <Timeline
            label="Application history"
            items={[
              { id: 1, title: 'Applied', meta: 'Resume v2', at: h(120) },
              { id: 2, title: 'Shortlisted', meta: 'by Priya S.', at: h(20), children: <Quote>Strong evidence on the payments ledger work.</Quote> },
              { id: 3, title: 'Work sample due', meta: 'Design an idempotent refunds endpoint', state: 'current' },
              { id: 4, title: 'Interview', state: 'future' },
            ]}
          />
        </Specimen>
        <Specimen label="Avatars and panel" stack>
          <div className="cluster">
            <Avatar name="Meera Iyer" />
            <Avatar name="Sana Khan" size={24} />
            <Avatar blind label="Blind applicant" />
          </div>
          <Panel tone="danger" title="Delete your account">
            <p className="muted t-sm">Your applications are withdrawn and your evidence is erased after 30 days.</p>
            <div className="g-mt-sm">
              <Button variant="danger-ghost" icon={Trash2}>Delete account…</Button>
            </div>
          </Panel>
        </Specimen>
      </div>
    </Section>
  )
}

/* ------------------------------------------------------------------------------------------------ overlays */
function Overlays() {
  const [dialog, setDialog] = useState<null | 'form' | 'confirm'>(null)
  const [drawer, setDrawer] = useState(false)
  const [busy, setBusy] = useState(false)
  return (
    <Section id="overlays" title="Overlays" description="Native <dialog>: inert background, focus contained, Escape closes, focus returns to the trigger.">
      <Specimen label="Dialog, confirmation, drawer, tooltip">
        <Button onClick={() => setDialog('form')}>Open dialog</Button>
        <Button variant="danger-ghost" onClick={() => setDialog('confirm')}>Withdraw application…</Button>
        <Button onClick={() => setDrawer(true)} icon={FileText}>Open verdict drawer</Button>
        <Tooltip content="Coverage is not a probability of success.">
          <Button variant="ghost">Hover or focus me</Button>
        </Tooltip>
      </Specimen>

      <Dialog
        open={dialog === 'form'}
        onClose={() => setDialog(null)}
        title="Shortlist Applicant 3014?"
        overline="Step 1 of 2"
        description="Name, contact details, resume file and cover letter become visible to 4 reviewers."
        busy={busy}
        footer={
          <>
            <Button variant="ghost" onClick={() => setDialog(null)} disabled={busy}>Cancel</Button>
            <Button
              variant="primary"
              loading={busy}
              onClick={() => {
                setBusy(true)
                window.setTimeout(() => {
                  setBusy(false)
                  setDialog(null)
                }, 900)
              }}
            >
              Shortlist and reveal
            </Button>
          </>
        }
      >
        <Field label="Comment" optional hint="Recorded in the audit log.">
          <Textarea rows={3} placeholder="Why this applicant moves forward" />
        </Field>
      </Dialog>

      <Dialog
        open={dialog === 'confirm'}
        onClose={() => setDialog(null)}
        size="sm"
        alert
        title="Withdraw your application for Senior Backend Engineer?"
        description="The employer is told you withdrew. You cannot apply to this job again."
        footer={
          <>
            <Button variant="ghost" autoFocus onClick={() => setDialog(null)}>Cancel</Button>
            <Button variant="danger" onClick={() => setDialog(null)}>Withdraw</Button>
          </>
        }
      />

      <Drawer
        open={drawer}
        onClose={() => setDrawer(false)}
        title="Partly verified · Java 17 and Spring Boot"
        description="GitHub · ana-r/ledger-sync"
        footer={
          <>
            <Button variant="ghost" onClick={() => setDrawer(false)}>Hide</Button>
            <span className="grow" />
            <Button>Re-check</Button>
            <Button variant="primary">Share with employers</Button>
          </>
        }
      >
        <div className="g-stack">
          <VerdictChip verdict="PARTLY_VERIFIED" />
          <h3 className="t-h3">Why</h3>
          <p className="t-body-lg">The repository uses Spring Boot 3 on Java 17, but commits by this account stop in 2023.</p>
          <Quote>build.gradle: sourceCompatibility = JavaVersion.VERSION_17</Quote>
          <ProvenanceLine icon={Github} source="GitHub" actor="agent" at={h(2)} />
          <CodeField label="Fingerprint" value="sha256:4f2a9c10e2b4d6f8a1c3e5b7d9f0a2c4e6b8d0f2" truncate="middle" />
        </div>
      </Drawer>
    </Section>
  )
}

/* ------------------------------------------------------------------------------------------------ brand */
function markVars(tokens: BrandTokens, theme: 'light' | 'dark'): CSSProperties {
  return Object.fromEntries(
    Object.entries(tokens[theme]).map(([k, v]) => [k === 'onAccent' ? '--fg-on-accent' : `--${k.replace(/[A-Z]/g, m => `-${m.toLowerCase()}`)}`, v]),
  ) as CSSProperties
}

function BrandSection() {
  return (
    <Section
      id="brand"
      title="Brand presets"
      description={
        <>
          Switch with one line in <code>src/design/brand/active.ts</code>. The build fails if a preset misses the contrast gate. Active: <strong>{brand.name}</strong>.
        </>
      }
    >
      <div className="g-presets">
        {PRESETS.map(({ tokens, Mark }) => {
          const failures = checkBrandContrast(tokens).filter(c => !c.ok)
          return (
            <div key={tokens.id} className="g-preset" data-active={tokens.id === brand.id || undefined}>
              {(['light', 'dark'] as const).map(theme => {
                const t = tokens[theme]
                return (
                  <div key={theme} className="g-preset-pane" data-theme={theme} style={markVars(tokens, theme)}>
                    <span className="g-preset-brand" style={{ fontFamily: tokens.fonts.display, letterSpacing: tokens.wordmark.tracking, textTransform: tokens.wordmark.case }}>
                      <Mark size={24} />
                      {tokens.name}
                    </span>
                    <span className="g-preset-swatches">
                      <span style={{ background: t.accent }} title="accent" />
                      <span style={{ background: t.accentSubtle }} title="accent subtle" />
                      <span style={{ background: t.tierFill }} title="tier fill" />
                    </span>
                    <span className="g-preset-btn" style={{ background: t.accent, color: t.onAccent }}>Primary</span>
                  </div>
                )
              })}
              <p className="g-preset-meta t-caption">
                <span className="mono">{tokens.id}</span> · {tokens.tagline} · contrast gate {failures.length ? <Badge tone="danger">fails</Badge> : <Badge tone="success">passes</Badge>}
              </p>
            </div>
          )
        })}
      </div>
      <Specimen label="Marks at 16 px (small-size drawings)">
        {PRESETS.map(({ tokens, Mark }) => (
          <span key={tokens.id} className="g-mark-small" data-theme="light" style={markVars(tokens, 'light')}>
            <Mark size={16} title={`${tokens.name} mark`} />
          </span>
        ))}
        {PRESETS.map(({ tokens, Mark }) => (
          <span key={`${tokens.id}-d`} className="g-mark-small" data-theme="dark" style={markVars(tokens, 'dark')}>
            <Mark size={16} title={`${tokens.name} mark, dark`} />
          </span>
        ))}
      </Specimen>
    </Section>
  )
}
