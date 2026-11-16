import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { errorMessage, isApiError } from '../../api/client'
import { qk } from '../../api/queryKeys'
import { CONNECTOR_KINDS, VERDICTS } from '../../api/types'
import type { Page, ShadowQueueItem, Verdict, VerificationMetrics, VerificationSettings, VerificationSourceKind } from '../../api/types'
import { verificationApi } from '../../api/verification'
import { useAuth } from '../../auth/AuthContext'
import { ActionDialog } from '../../components/ActionDialog'
import { DomainListInput } from '../../components/DomainListInput'
import { InlineError, QueryError } from '../../components/QueryError'
import { SaveBar } from '../../components/SaveBar'
import { useLeaveGuard } from '../../lib/leaveGuard'
import { ShortcutSheet, useShortcuts } from '../../components/Shortcuts'
import { OwnershipLine, SOURCE_ICON, VerdictDetails } from '../../components/VerdictParts'
import {
  Button, DataTable, Drawer, EmptyState, Field, LoadingRegion, Meter, PageHeader, Pagination, ProvenanceLine, SegmentedControl, Skeleton, SkeletonText, Switch,
  TabPanel, Tabs, Textarea, VerdictChip, useMediaQuery, useToast, type Column,
} from '../../design/components'
import { BadgeCheck, Check, CircleCheck, Eye, EyeOff, Icon, Info, ShieldCheck } from '../../design/icons'
import { formatNumber, plural, relativeTime } from '../../design/lib/format'
import { VERDICT_LABEL } from '../../design/lib/labels'
import { humanize } from '../../lib/format'
import { READINESS, sourceKindLabel } from '../../lib/verification'
import { useSearchState } from '../../lib/useSearchState'
import './admin.css'

const QUEUE_SIZE = 10
type TabKey = 'readiness' | 'queue' | 'connectors' | 'trusted'

const CONNECTOR_TEXT: Record<VerificationSourceKind, string> = {
  GITHUB: 'Repositories and commits the candidate proved they own.',
  GITLAB: 'Projects and commits the candidate proved they own.',
  WEBSITE: 'Personal sites and portfolios; ownership proven by DNS, a file or a page code.',
  DOCUMENT: 'Certificates and letters the candidate uploaded, checked for consistency.',
  OPEN_BADGE: 'Signed digital badges (Open Badges 2 and 3), checked against the issuer.',
  REFERENCE: 'Answers from referees the candidate nominated, at an employer email domain.',
}

const SHORT: Record<Verdict, string> = { VERIFIED: 'Verified', PARTLY_VERIFIED: 'Partly', NOT_FOUND: 'Not found', CONTRADICTED: 'Contradicted', NOT_CHECKABLE: 'Not checkable' }

/**
 * /admin/verification (FLOWS-HR-B §5): the mode and readiness on top, then Readiness · Labelling queue · Connectors ·
 * Trusted lists. HR managers see readiness and the queue (settings are ADMIN-only on the server).
 */
export function AdminVerificationPage() {
  const { can } = useAuth()
  const admin = can('verification.settings')
  const search = useSearchState()
  const metrics = useQuery({ queryKey: qk.verificationMetrics, queryFn: ({ signal }) => verificationApi.metrics(signal) })
  const settings = useQuery({ queryKey: qk.verificationSettings, queryFn: ({ signal }) => verificationApi.settings(signal), enabled: admin })
  const queueTotal = useQuery({ queryKey: qk.shadowQueue(0), queryFn: ({ signal }) => verificationApi.shadowQueue(0, QUEUE_SIZE, signal) })
  const tabs = [
    { value: 'readiness' as const, label: 'Readiness' },
    { value: 'queue' as const, label: 'Labelling queue', count: queueTotal.data?.totalElements },
    ...(admin ? [{ value: 'connectors' as const, label: 'Connectors' }, { value: 'trusted' as const, label: 'Trusted domains' }] : []),
  ]
  const requested = search.get('tab', 'readiness') as TabKey
  const active: TabKey = tabs.some(t => t.value === requested) ? requested : 'readiness'

  return (
    <div className="adm" data-width="wide">
      <PageHeader
        title="Verification"
        description="Agents check candidates’ claims against sources they connected. In shadow mode results stay hidden from reviewers while your team labels them, so you can measure accuracy before anyone relies on them."
      />
      <ModeBlock admin={admin} settings={settings.data} settingsError={settings.error} metrics={metrics.data} onOpenTab={t => search.set({ tab: t }, { replace: true })} />
      <Tabs label="Verification sections" idBase="vf" items={tabs} value={active} onChange={v => search.set({ tab: v === 'readiness' ? null : v, page: null }, { replace: true })} />
      <TabPanel idBase="vf" value="readiness" active={active}>
        {metrics.isPending ? <LoadingRegion label="Loading metrics"><div className="adm-meters">{[0, 1, 2].map(i => <Skeleton key={i} height={56} />)}</div></LoadingRegion>
          : metrics.isError ? <QueryError error={metrics.error} title="We couldn’t load agreement metrics" onRetry={() => void metrics.refetch()} retrying={metrics.isFetching} />
            : <Readiness metrics={metrics.data} />}
      </TabPanel>
      <TabPanel idBase="vf" value="queue" active={active}><ShadowQueue /></TabPanel>
      {admin && (
        <>
          <TabPanel idBase="vf" value="connectors" active={active}>{settings.data ? <ConnectorsForm settings={settings.data} /> : settings.isError ? <QueryError error={settings.error} onRetry={() => void settings.refetch()} /> : <SkeletonText lines={6} />}</TabPanel>
          <TabPanel idBase="vf" value="trusted" active={active}>{settings.data ? <TrustedForm settings={settings.data} /> : settings.isError ? <QueryError error={settings.error} onRetry={() => void settings.refetch()} /> : <SkeletonText lines={6} />}</TabPanel>
        </>
      )}
    </div>
  )
}

// ================================================================================================ mode + readiness

type ReadyCheck = { key: string; label: string; met: boolean; meter: { value: number; max: number; target: number; text: string; targetText: string } }

function checks(m: VerificationMetrics): ReadyCheck[] {
  const fcr = m.falseCorroborationRate
  const kappa = m.kappa
  return [
    { key: 'labelled', label: 'Labelled results', met: m.labelled >= READINESS.minLabelled, meter: { value: m.labelled, max: Math.max(READINESS.minLabelled, m.labelled), target: READINESS.minLabelled, text: `${formatNumber(m.labelled)} / ${READINESS.minLabelled}`, targetText: `at least ${READINESS.minLabelled}` } },
    { key: 'fcr', label: 'False corroboration', met: fcr != null && fcr <= READINESS.maxFalseCorroboration, meter: { value: Math.min(fcr ?? 0, 0.05), max: 0.05, target: READINESS.maxFalseCorroboration, text: fcr == null ? 'Not measured' : `${(fcr * 100).toFixed(1)}%`, targetText: 'at most 2% · lower is better' } },
    { key: 'kappa', label: 'Agreement beyond chance (κ)', met: kappa != null && kappa >= READINESS.minKappa, meter: { value: Math.max(0, kappa ?? 0), max: 1, target: READINESS.minKappa, text: kappa == null ? 'Not measured' : kappa.toFixed(2), targetText: 'at least 0.70' } },
  ]
}

function ReadinessMeters({ metrics, compact }: { metrics: VerificationMetrics; compact?: boolean }) {
  return (
    <div className="adm-meters" data-compact={compact || undefined}>
      {checks(metrics).map(c => (
        <div key={c.key} className="adm-meter" data-met={c.met || undefined}>
          <Meter label={c.label} value={c.meter.value} max={c.meter.max} target={c.meter.target} tone={c.met ? 'success' : 'warning'} valueText={c.meter.text} />
          <p className="adm-meter-foot"><Icon icon={c.met ? CircleCheck : Info} size={12} />{c.met ? 'Met' : 'Not yet'} · target {c.meter.targetText}</p>
        </div>
      ))}
    </div>
  )
}

function ModeBlock({ admin, settings, settingsError, metrics, onOpenTab }: { admin: boolean; settings?: VerificationSettings; settingsError: unknown; metrics?: VerificationMetrics; onOpenTab: (t: TabKey) => void }) {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [confirm, setConfirm] = useState<'VISIBLE' | 'SHADOW' | null>(null)
  const met = metrics ? checks(metrics).filter(c => c.met).length : null
  const ready = metrics?.readyForVisible ?? false
  const save = useMutation({
    mutationFn: (mode: 'VISIBLE' | 'SHADOW') => verificationApi.updateSettings({ mode, connectors: settings!.connectors }),
    onSuccess: updated => {
      setConfirm(null)
      queryClient.setQueryData(qk.verificationSettings, updated)
      // Every applicant's Verification tab and the rankings change with the mode.
      void queryClient.invalidateQueries({ queryKey: ['verification', 'application'] })
      void queryClient.invalidateQueries({ queryKey: ['applicants'] })
      toast({ tone: 'success', title: updated.mode === 'VISIBLE' ? 'Verification is visible' : 'Back in shadow mode', body: updated.mode === 'VISIBLE' ? 'Reviewers now see shared results; reviewed candidates are re-ranked.' : 'Results are hidden from reviewers again; rankings ignore them.' })
    },
  })
  const mode = settings?.mode
  return (
    <section className="adm-mode" data-mode={mode ?? 'UNKNOWN'} data-ready={ready || undefined} aria-label="Verification mode">
      <div className="adm-mode-main">
        <Icon icon={mode === 'VISIBLE' ? Eye : EyeOff} size={20} className="adm-mode-icon" />
        <div>
          <p className="adm-mode-title">{mode === 'VISIBLE' ? 'Visible mode' : mode === 'SHADOW' ? 'Shadow mode' : admin && settingsError ? 'Mode unavailable' : admin ? 'Loading mode…' : 'Mode is managed by an administrator'}</p>
          <p className="adm-mode-text">
            {mode === 'VISIBLE' ? 'Reviewers see results candidates shared. A verified result raises the tier only after a reviewer confirms it.'
              : mode === 'SHADOW' ? 'Agents run; results are hidden from reviewers and never change rankings.'
                : 'Label results in the queue to measure the agents. An administrator switches the mode.'}
            {settings?.updatedAt && <span className="adm-faint"> Changed {relativeTime(settings.updatedAt)}{settings.updatedByName ? ` by ${settings.updatedByName}` : ''}.</span>}
          </p>
        </div>
      </div>
      <div className="adm-mode-side">
        {met != null && (
          <button type="button" className="adm-ready" data-ready={ready || undefined} onClick={() => onOpenTab('readiness')}>
            <Icon icon={ready ? ShieldCheck : Info} size={14} />
            {ready ? 'Ready for visible mode' : `${met} of 3 targets met`}
          </button>
        )}
        {admin && mode === 'SHADOW' && <Button variant={ready ? 'primary' : 'secondary'} icon={Eye} onClick={() => { save.reset(); setConfirm('VISIBLE') }}>Switch to visible</Button>}
        {admin && mode === 'VISIBLE' && <Button variant="secondary" icon={EyeOff} onClick={() => { save.reset(); setConfirm('SHADOW') }}>Switch to shadow</Button>}
      </div>
      <ActionDialog
        open={confirm === 'VISIBLE'}
        title={ready ? 'Switch verification to visible?' : 'Switch to visible before the targets are met?'}
        description={ready ? 'Reviewers will see results candidates shared, and confirmed verified results raise evidence tiers. Candidates your team has reviewed are re-ranked.' : 'The agents have not yet shown enough agreement with your reviewers. A false corroboration would lift a candidate unfairly. Candidates your team has reviewed are re-ranked.'}
        confirmLabel={ready ? 'Switch to visible' : 'Switch to visible anyway'}
        tone={ready ? 'primary' : 'danger'}
        size="lg"
        busy={save.isPending}
        error={save.error}
        onCancel={() => setConfirm(null)}
        onConfirm={() => save.mutate('VISIBLE')}
      >
        {metrics && <ReadinessMeters metrics={metrics} compact />}
      </ActionDialog>
      <ActionDialog
        open={confirm === 'SHADOW'}
        title="Switch back to shadow mode?"
        description="Reviewers stop seeing verification results, and rankings stop using them. Labelling continues."
        confirmLabel="Switch to shadow"
        focusCancel
        busy={save.isPending}
        error={save.error}
        onCancel={() => setConfirm(null)}
        onConfirm={() => save.mutate('SHADOW')}
      />
    </section>
  )
}

function Readiness({ metrics }: { metrics: VerificationMetrics }) {
  const pct = (v: number | null) => (v == null ? '—' : `${(v * 100).toFixed(1)}%`)
  const k = (v: number | null) => (v == null ? '—' : v.toFixed(2))
  const verifierCols: Column<VerificationMetrics['byVerifier'][number]>[] = [
    { key: 'v', header: 'Verifier', primary: true, sortValue: r => r.verifier, cell: r => <span className="mono">{r.verifier}</span> },
    { key: 'l', header: 'Labelled', numeric: true, sortValue: r => r.labelled, cell: r => formatNumber(r.labelled) },
    { key: 'a', header: 'Agreement', numeric: true, sortValue: r => r.agreement ?? -1, cell: r => pct(r.agreement) },
    { key: 'f', header: 'False corroboration', numeric: true, sortValue: r => r.falseCorroborationRate ?? -1, cell: r => <span data-bad={r.falseCorroborationRate != null && r.falseCorroborationRate > READINESS.maxFalseCorroboration ? '' : undefined} className="adm-rate">{pct(r.falseCorroborationRate)}</span> },
    { key: 'k', header: 'κ', numeric: true, sortValue: r => r.kappa ?? -1, cell: r => <span data-bad={r.kappa != null && r.kappa < READINESS.minKappa ? '' : undefined} className="adm-rate">{k(r.kappa)}</span> },
  ]
  const verdictCols: Column<VerificationMetrics['byVerdict'][number]>[] = [
    { key: 'v', header: 'Verdict', primary: true, cell: r => <VerdictChip verdict={r.verdict} size="sm" shortLabel={SHORT[r.verdict]} /> },
    { key: 'p', header: 'Agents said', numeric: true, cell: r => formatNumber(r.predicted) },
    { key: 'g', header: 'Reviewers said', numeric: true, cell: r => formatNumber(r.gold) },
    { key: 'b', header: 'Both', numeric: true, cell: r => formatNumber(r.agreed) },
    { key: 'pr', header: 'Precision', numeric: true, cell: r => pct(r.precision) },
    { key: 're', header: 'Recall', numeric: true, cell: r => pct(r.recall) },
  ]
  return (
    <div className="adm-readiness">
      <p className="adm-sub">Measured over every label your team saved. Overall agreement {pct(metrics.agreement)}.</p>
      <ReadinessMeters metrics={metrics} />
      <p className="adm-explain"><strong>False corroboration</strong> means the agent said “Verified” where your reviewer did not. It is the costly error: it would raise a candidate unfairly, so its target is the strictest.</p>
      <div className="adm-two">
        <section aria-labelledby="by-verifier"><h3 id="by-verifier" className="t-h3">By verifier</h3>
          <DataTable caption="Agreement by verifier" columns={verifierCols} rows={metrics.byVerifier} rowKey={r => r.verifier} density="compact" empty={<EmptyState compact icon={BadgeCheck} title="No labels yet" description="Label results in the queue to see per-verifier agreement." />} />
        </section>
        <section aria-labelledby="by-verdict"><h3 id="by-verdict" className="t-h3">By verdict</h3>
          <DataTable caption="Agreement by verdict" columns={verdictCols} rows={metrics.byVerdict} rowKey={r => r.verdict} density="compact" empty={<EmptyState compact icon={BadgeCheck} title="No labels yet" description="Precision and recall appear once results are labelled." />} />
        </section>
      </div>
    </div>
  )
}

// ================================================================================================ shadow queue

function ShadowQueue() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const search = useSearchState()
  const page = search.getPage()
  const wide = useMediaQuery('(min-width: 900px)')
  const q = useQuery({ queryKey: qk.shadowQueue(page), queryFn: ({ signal }) => verificationApi.shadowQueue(page, QUEUE_SIZE, signal), placeholderData: keepPreviousData })
  const items = q.data?.content ?? []
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [help, setHelp] = useState(false)
  useEffect(() => {
    if (!items.length) return
    if (selectedId == null || !items.some(i => i.verdictId === selectedId)) setSelectedId((items.find(i => !i.myLabel) ?? items[0]).verdictId)
  }, [items, selectedId])
  const index = items.findIndex(i => i.verdictId === selectedId)
  const selected = index >= 0 ? items[index] : null
  const labelledHere = items.filter(i => i.myLabel).length

  const label = useMutation({
    mutationFn: ({ item, verdict, note }: { item: ShadowQueueItem; verdict: Verdict; note: string }) => verificationApi.label(item.verdictId, { labelVerdict: verdict, note: note.trim() || undefined }),
    onMutate: ({ item, verdict, note }) => {
      const key = qk.shadowQueue(page)
      const previous = queryClient.getQueryData<Page<ShadowQueueItem>>(key)
      queryClient.setQueryData<Page<ShadowQueueItem>>(key, cur => cur && {
        ...cur,
        content: cur.content.map(i => (i.verdictId === item.verdictId ? { ...i, myLabel: { verdictId: i.verdictId, labelVerdict: verdict, note: note.trim() || null, at: new Date().toISOString() }, labelCount: i.myLabel ? i.labelCount : i.labelCount + 1 } : i)),
      })
      const next = items.slice(index + 1).find(i => !i.myLabel) ?? items.find(i => !i.myLabel && i.verdictId !== item.verdictId)
      if (next) setSelectedId(next.verdictId)
      return { previous }
    },
    onError: (cause, _v, ctx) => {
      if (ctx?.previous) queryClient.setQueryData(qk.shadowQueue(page), ctx.previous)
      toast({ tone: 'danger', title: 'The label was not saved', body: errorMessage(cause) })
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: qk.verificationMetrics })
      // The tab count (unlabelled first) comes from page 0.
      if (page !== 0) void queryClient.invalidateQueries({ queryKey: qk.shadowQueue(0) })
    },
  })

  const move = (d: number) => {
    const next = items[index + d]
    if (next) setSelectedId(next.verdictId)
  }
  useShortcuts({ j: () => move(1), k: () => move(-1), ArrowDown: () => move(1), ArrowUp: () => move(-1), '?': () => setHelp(true) }, wide)

  if (q.isPending) return <LoadingRegion label="Loading the queue"><div className="adm-queue">{[0, 1, 2, 3].map(i => <SkeletonText key={i} lines={2} />)}</div></LoadingRegion>
  if (q.isError) return <QueryError error={q.error} title="We couldn’t load the labelling queue" onRetry={() => void q.refetch()} retrying={q.isFetching} />
  if (items.length === 0) return <EmptyState icon={CircleCheck} title="Nothing to label" description="Results appear here when candidates who applied to your jobs share them." />

  const detail = selected && (
    <QueueDetail
      key={selected.verdictId}
      item={selected}
      saving={label.isPending && label.variables?.item.verdictId === selected.verdictId}
      keysEnabled={wide || drawerOpen}
      onSave={(verdict, note) => label.mutate({ item: selected, verdict, note })}
    />
  )

  return (
    <div className="adm-queue-wrap">
      <p className="adm-sub">
        {plural(q.data.totalElements, 'shared result')} · unlabelled first. This page: <strong className="numeric">{labelledHere}</strong> of {items.length} labelled.
        {wide && <> <button type="button" className="ap-textlink" onClick={() => setHelp(true)}>Keyboard: 1–5, Enter, J/K</button></>}
      </p>
      <div className="adm-queue">
        <ul className="adm-q-list" aria-label="Results to label">
          {items.map(i => (
            <li key={i.verdictId}>
              <button
                type="button"
                className="adm-q-item"
                aria-current={i.verdictId === selectedId ? 'true' : undefined}
                onClick={() => { setSelectedId(i.verdictId); if (!wide) setDrawerOpen(true) }}
              >
                <span className="adm-q-value">{i.value}</span>
                <span className="adm-q-meta">{humanize(i.claimType)} · {sourceKindLabel(i.sourceKind)}</span>
                <span className="adm-q-row">
                  <VerdictChip verdict={i.verdict} size="sm" shortLabel={SHORT[i.verdict]} />
                  {i.myLabel ? <span className="adm-q-done"><Icon icon={Check} size={12} />You: {SHORT[i.myLabel.labelVerdict]}</span> : <span className="adm-q-todo">To label</span>}
                </span>
              </button>
            </li>
          ))}
        </ul>
        {wide && <div className="adm-q-detail">{detail}</div>}
      </div>
      {q.data.totalElements > QUEUE_SIZE && <Pagination page={page + 1} pageSize={QUEUE_SIZE} total={q.data.totalElements} onPageChange={p => search.set({ page: p - 1 })} />}
      {!wide && <Drawer open={drawerOpen} onClose={() => setDrawerOpen(false)} title={selected?.value ?? 'Result'}>{detail}</Drawer>}
      <ShortcutSheet open={help} onClose={() => setHelp(false)} groups={[
        { title: 'Labelling', items: [{ keys: ['1', '2', '3', '4', '5'], label: 'Choose a verdict' }, { keys: ['Enter'], label: 'Save and go to the next result' }] },
        { title: 'Moving', items: [{ keys: ['J', '↓'], label: 'Next result' }, { keys: ['K', '↑'], label: 'Previous result' }] },
      ]} />
    </div>
  )
}

function QueueDetail({ item, saving, keysEnabled, onSave }: { item: ShadowQueueItem; saving: boolean; keysEnabled: boolean; onSave: (verdict: Verdict, note: string) => void }) {
  const [verdict, setVerdict] = useState<Verdict | null>(item.myLabel?.labelVerdict ?? null)
  const [note, setNote] = useState(item.myLabel?.note ?? '')
  const [problem, setProblem] = useState<string | null>(null)

  // Only while the detail is on screen (side panel, or the open bottom sheet on phones): a closed sheet must not label.
  const keys = ['1', '2', '3', '4', '5', 'Enter']
  useShortcuts({
    ...Object.fromEntries(VERDICTS.map((v, i) => [String(i + 1), () => setVerdict(v)])),
    Enter: (event: KeyboardEvent) => { if (event.target === document.body || (event.target as HTMLElement | null)?.closest?.('dialog') === event.target) submit() },
  }, keysEnabled, keys)

  function submit(event?: FormEvent) {
    event?.preventDefault()
    if (!verdict) return setProblem('Choose what you think the right verdict is.')
    setProblem(null)
    onSave(verdict, note)
  }

  return (
    <form className="adm-q-form" onSubmit={submit} noValidate aria-label={`Label: ${item.value}`}>
      <header className="adm-q-head">
        <p className="adm-q-kind">{humanize(item.claimType)} · {item.subjectType === 'CANDIDATE_CLAIM' ? 'candidate claim' : 'from the resume'}</p>
        <h3 className="t-h2">{item.value}</h3>
        <div className="adm-q-chips">
          <span className="adm-faint">Agent said</span>
          <VerdictChip verdict={item.verdict} />
          <OwnershipLine proven={item.ownershipProven} />
        </div>
        <ProvenanceLine icon={item.sourceKind ? SOURCE_ICON[item.sourceKind] : undefined} source={sourceKindLabel(item.sourceKind)} actor={item.verifier} at={item.createdAt} />
      </header>
      <VerdictDetails verdict={item} revealed={item.revealed} blindText="sources appear once the candidate is revealed on their application" />
      <div className="adm-q-label">
        <SegmentedControl
          label="Your verdict"
          value={verdict}
          onChange={v => { setVerdict(v as Verdict); setProblem(null) }}
          options={VERDICTS.map((v, i) => ({ value: v, label: <><span className="adm-key numeric" aria-hidden="true">{i + 1}</span>{SHORT[v]}</> }))}
          block
        />
        <p className="adm-faint">{verdict ? VERDICT_LABEL[verdict] : 'What does the evidence actually support?'}{item.labelCount > 0 && ` · ${plural(item.labelCount, 'label')} so far`}</p>
        <Field label="Note" optional>
          <Textarea rows={1} maxRows={4} maxLength={1000} value={note} onChange={e => setNote(e.target.value)} />
        </Field>
        {problem && <InlineError error={problem} />}
        <div className="adm-q-actions">
          <Button type="submit" variant="primary" loading={saving}>{item.myLabel ? 'Update and next' : 'Save and next'}</Button>
        </div>
      </div>
    </form>
  )
}

// ================================================================================================ settings tabs

function useSettingsSave(onDone?: () => void) {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  return useMutation({
    mutationFn: verificationApi.updateSettings,
    onSuccess: updated => {
      queryClient.setQueryData(qk.verificationSettings, updated)
      void queryClient.invalidateQueries({ queryKey: qk.verificationMetrics })
      void queryClient.invalidateQueries({ queryKey: ['verification', 'application'] })
      void queryClient.invalidateQueries({ queryKey: ['applicants'] })
      toast({ tone: 'success', title: 'Verification settings saved', body: 'Audited; reviewed candidates are re-ranked.' })
      onDone?.()
    },
  })
}

function ConnectorsForm({ settings }: { settings: VerificationSettings }) {
  const [connectors, setConnectors] = useState<Record<VerificationSourceKind, boolean>>({ ...settings.connectors })
  useEffect(() => { setConnectors({ ...settings.connectors }) }, [settings])
  const changed = CONNECTOR_KINDS.filter(k => Boolean(connectors[k]) !== Boolean(settings.connectors[k])).length
  const save = useSettingsSave()
  const leaveGuard = useLeaveGuard(changed > 0 && !save.isPending)
  return (
    <>
    <form id="vf-connectors" onSubmit={e => { e.preventDefault(); save.mutate({ mode: settings.mode, connectors }) }} noValidate>
      <p className="adm-sub">A disabled connector’s results are hidden from your reviewers and never affect your rankings. Candidates can still connect it.</p>
      <div className="adm-connectors">
        {CONNECTOR_KINDS.map(kind => (
          <div key={kind} className="adm-connector">
            <Icon icon={SOURCE_ICON[kind]} size={20} className="adm-conn-icon" />
            <Switch label={sourceKindLabel(kind)} description={CONNECTOR_TEXT[kind]} checked={Boolean(connectors[kind])} onChange={e => setConnectors(c => ({ ...c, [kind]: e.target.checked }))} />
          </div>
        ))}
      </div>
      <InlineError error={save.error} />
      <SaveBar changes={changed} busy={save.isPending} form="vf-connectors" onDiscard={() => { setConnectors({ ...settings.connectors }); save.reset() }} />
    </form>
    {leaveGuard}
    </>
  )
}

function TrustedForm({ settings }: { settings: VerificationSettings }) {
  const initial = useMemo(() => ({ employers: settings.trustedEmployerDomains ?? [], issuers: settings.trustedBadgeIssuers ?? [] }), [settings])
  const [employers, setEmployers] = useState(initial.employers)
  const [issuers, setIssuers] = useState(initial.issuers)
  useEffect(() => { setEmployers(initial.employers); setIssuers(initial.issuers) }, [initial])
  const changed = (JSON.stringify(employers) !== JSON.stringify(initial.employers) ? 1 : 0) + (JSON.stringify(issuers) !== JSON.stringify(initial.issuers) ? 1 : 0)
  const save = useSettingsSave()
  const message = save.error ? errorMessage(save.error) : ''
  const fieldError = (field: string) => (isApiError(save.error, 400) && message.includes(field) ? message.replace(`${field}: `, '') : undefined)
  const unsupported = settings.trustedEmployerDomains === undefined
  const leaveGuard = useLeaveGuard(changed > 0 && !save.isPending)
  return (
    <>
    <form id="vf-trusted" className="adm-trusted" noValidate onSubmit={e => { e.preventDefault(); save.mutate({ mode: settings.mode, trustedEmployerDomains: employers, trustedBadgeIssuers: issuers }) }}>
      {unsupported && <p className="adm-sub">This server does not report trusted lists yet; saving sends them anyway.</p>}
      <section className="adm-trusted-block" aria-labelledby="tr-emp">
        <h3 id="tr-emp" className="t-h3">Trusted employer domains</h3>
        <p className="adm-sub">References from people at these domains count as verified even when the domain cannot be checked automatically. Subdomains are included. Only your organisation’s view changes; the stored result does not.</p>
        <DomainListInput label="Employer domains" value={employers} onChange={setEmployers} error={fieldError('trustedEmployerDomains')} placeholder="acme.com" />
      </section>
      <section className="adm-trusted-block" aria-labelledby="tr-iss">
        <h3 id="tr-iss" className="t-h3">Trusted badge issuers</h3>
        <p className="adm-sub">Badges issued from these domains count as verified (subdomains included). Well-known badge platforms such as Credly and Badgr are already trusted.</p>
        <DomainListInput label="Issuer domains" value={issuers} onChange={setIssuers} error={fieldError('trustedBadgeIssuers')} placeholder="badges.university.edu" />
      </section>
      {!fieldError('trustedEmployerDomains') && !fieldError('trustedBadgeIssuers') && <InlineError error={save.error} />}
      <SaveBar changes={changed} busy={save.isPending} form="vf-trusted" onDiscard={() => { setEmployers(initial.employers); setIssuers(initial.issuers); save.reset() }} note="re-ranks reviewed candidates" />
    </form>
    {leaveGuard}
    </>
  )
}
