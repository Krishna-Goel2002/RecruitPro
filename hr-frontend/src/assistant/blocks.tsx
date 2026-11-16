import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type {
  ActionBlock, Block, CandidateGates, CandidatesBlock, CreateJobMode, DraftRequirement, DraftWarning, JobDraft, JobPreviewBlock, LinkBlock,
  MetricsBlock, NoticeBlock, QuestionBlock, TableBlock, TableCell, TableColumn,
} from '../api/assistantTypes'
import { ApplicationStatusBadge } from '../components/StatusBadges'
import { Alert, Badge, Button, CountUp, CoverageMeter, ReqMarker } from '../design/components'
import { ArrowDown, ArrowUp, ArrowUpDown, ArrowUpRight, Check, Clock, EyeOff, Icon, ShieldCheck, TriangleAlert, X } from '../design/icons'
import { formatDate, formatMoney, formatNumber } from '../design/lib/format'
import { groupLabel, humanize } from '../lib/format'
import { SafeLink, safeHref } from './markdown'

/* ------------------------------------------------------------------------------------------------ helpers */

export const ACTION_LABELS: Record<string, string> = {
  create_job: 'Create job',
  update_job_status: 'Change job status',
  decide_application: 'Decision on an application',
  assign_work_sample: 'Assign a work sample',
  schedule_interview: 'Schedule an interview',
  generate_work_sample: 'Generate a work sample',
  generate_interview_kit: 'Generate an interview kit',
}
export const actionLabel = (action: string) => ACTION_LABELS[action] ?? humanize(action)

const STATUS_TEXT: Record<ActionBlock['status'], string> = {
  PENDING: 'Waiting for your confirmation',
  CONFIRMED: 'Done',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
  FAILED: 'Failed',
}

/** Re-renders every `ms` while `active`. */
function useNow(active: boolean, ms = 1000): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!active) return
    const t = window.setInterval(() => setNow(Date.now()), ms)
    return () => window.clearInterval(t)
  }, [active, ms])
  return now
}

/** "29:41" (under an hour) or "1 h 5 min"; null when already past. */
export function remaining(expiresAt: string | null | undefined, now = Date.now()): string | null {
  if (!expiresAt) return null
  const ms = new Date(expiresAt).getTime() - now
  if (!Number.isFinite(ms) || ms <= 0) return null
  const s = Math.floor(ms / 1000)
  if (s >= 3600) return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/** PENDING whose expiry has passed is shown as EXPIRED (the server agrees on the next read). */
export function effectiveStatus(block: ActionBlock, now = Date.now()): ActionBlock['status'] {
  if (block.status === 'PENDING' && block.expiresAt && new Date(block.expiresAt).getTime() <= now) return 'EXPIRED'
  return block.status
}

function statusTone(status: ActionBlock['status']) {
  return status === 'CONFIRMED' ? 'success' : status === 'FAILED' ? 'danger' : status === 'PENDING' ? 'info' : 'neutral'
}

/* ------------------------------------------------------------------------------------------------ metrics */

export function MetricsView({ block }: { block: MetricsBlock }) {
  return (
    <dl className="as-metrics">
      {(block.items ?? []).map((item, i) => (
        <div key={`${item.label}-${i}`} className="as-metric">
          <dt>{item.label}</dt>
          <dd className="as-metric-value">
            {typeof item.value === 'number' ? <CountUp value={item.value} format={n => formatNumber(Math.round(n))} /> : <span className="numeric">{item.value}</span>}
          </dd>
          {item.hint && <dd className="as-metric-hint">{item.hint}</dd>}
        </div>
      ))}
    </dl>
  )
}

/* ------------------------------------------------------------------------------------------------ table */

function columnLabel(c: TableColumn): string {
  return typeof c === 'string' ? c : c.label ?? c.header ?? c.key ?? ''
}
function cellSortValue(cell: TableCell): string | number {
  if (cell == null) return ''
  if (typeof cell === 'number') return cell
  if (typeof cell === 'boolean') return cell ? 1 : 0
  if (typeof cell === 'object') return cell.label
  const n = Number(String(cell).replace(/[%,₹\s]/g, ''))
  return String(cell).trim() !== '' && Number.isFinite(n) ? n : String(cell).toLowerCase()
}
function renderCell(cell: TableCell): ReactNode {
  if (cell == null || cell === '') return <span className="subtle">–</span>
  if (typeof cell === 'boolean') return cell ? 'Yes' : 'No'
  if (typeof cell === 'number') return formatNumber(cell)
  if (typeof cell === 'object') return cell.href ? <SafeLink href={cell.href}>{cell.label}</SafeLink> : cell.label
  return String(cell)
}

export function TableView({ block }: { block: TableBlock }) {
  const columns = block.columns ?? []
  const rows = block.rows ?? []
  const [sort, setSort] = useState<{ col: number; dir: 'asc' | 'desc' } | null>(null)
  const numeric = useMemo(
    () => columns.map((c, i) => (typeof c === 'object' && c.numeric != null ? c.numeric : rows.length > 0 && rows.every(r => r[i] == null || typeof cellSortValue(r[i]) === 'number'))),
    [columns, rows],
  )
  const sorted = useMemo(() => {
    if (!sort) return rows
    const copy = [...rows]
    copy.sort((a, b) => {
      const x = cellSortValue(a[sort.col])
      const y = cellSortValue(b[sort.col])
      const r = typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y))
      return sort.dir === 'asc' ? r : -r
    })
    return copy
  }, [rows, sort])
  return (
    <div className="as-table-wrap">
      <table className="as-table">
        {block.caption && <caption>{block.caption}</caption>}
        <thead>
          <tr>
            {columns.map((c, i) => {
              const active = sort?.col === i
              const label = columnLabel(c)
              return (
                <th key={i} scope="col" data-numeric={numeric[i] || undefined} aria-sort={active ? (sort!.dir === 'asc' ? 'ascending' : 'descending') : undefined}>
                  <button
                    type="button"
                    className="as-th-sort"
                    onClick={() => setSort(cur => (cur?.col === i ? (cur.dir === 'asc' ? { col: i, dir: 'desc' } : null) : { col: i, dir: numeric[i] ? 'desc' : 'asc' }))}
                  >
                    {label}
                    <Icon icon={active ? (sort!.dir === 'asc' ? ArrowUp : ArrowDown) : ArrowUpDown} size={12} />
                    <span className="sr-only">{active ? `, sorted ${sort!.dir === 'asc' ? 'ascending' : 'descending'}` : ', sort'}</span>
                  </button>
                </th>
              )
            })}
          </tr>
        </thead>
        <tbody>
          {sorted.map((row, r) => (
            <tr key={r}>
              {columns.map((_, i) => (
                <td key={i} data-numeric={numeric[i] || undefined}>
                  {renderCell(row[i] ?? null)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/* ------------------------------------------------------------------------------------------------ candidates */

function gatesText(gates: CandidateGates | undefined): { text: string; ok: boolean | null } | null {
  if (gates == null) return null
  if (typeof gates === 'boolean') return { text: gates ? 'Gates verified' : 'Gate open', ok: gates }
  if (typeof gates === 'number') return { text: gates === 0 ? 'Gates verified' : `${gates} gate${gates === 1 ? '' : 's'} open`, ok: gates === 0 }
  if (typeof gates === 'string') return { text: gates, ok: null }
  // The backend sends the open gates by name (["Java"]); an empty list means every gate is verified.
  if (Array.isArray(gates)) {
    const names = gates.filter(g => typeof g === 'string' && g.trim())
    return names.length === 0 ? { text: 'Gates verified', ok: true } : { text: `Gate open: ${names.slice(0, 2).join(', ')}${names.length > 2 ? ` +${names.length - 2}` : ''}`, ok: false }
  }
  const total = gates.total ?? null
  const open = gates.open ?? (total != null && gates.verified != null ? total - gates.verified : null)
  if (open == null) return null
  if (total === 0) return null
  return { text: open === 0 ? 'Gates verified' : `${open} of ${total ?? '?'} gates open`, ok: open === 0 }
}

export function CandidatesView({ block }: { block: CandidatesBlock }) {
  const items = block.items ?? []
  if (items.length === 0) return <p className="as-muted">No applicants to show.</p>
  return (
    <ul className="as-cands" role="list">
      {items.map(c => {
        const gates = gatesText(c.gates)
        const name = c.revealedName?.trim()
        const safe = safeHref(c.link)
        const title = (
          <>
            {name ? <span className="as-cand-name">{name}</span> : <span className="as-cand-name mono">{c.code}</span>}
            {!name && (
              <span className="as-cand-blind">
                <Icon icon={EyeOff} size={12} />
                Blind
              </span>
            )}
          </>
        )
        return (
          <li key={c.applicationId} className="as-cand">
            <div className="as-cand-main">
              {safe?.kind === 'internal' ? (
                <Link to={safe.to} className="as-cand-link">
                  {title}
                </Link>
              ) : (
                <span className="as-cand-link">{title}</span>
              )}
              <span className="as-cand-meta">
                {name && <span className="mono">{c.code}</span>}
                <ApplicationStatusBadge status={c.status} />
                {c.group && <span>{groupLabel(c.group)}</span>}
                {gates && (
                  <span className="as-cand-gates" data-ok={gates.ok ?? undefined}>
                    {gates.ok === true && <Icon icon={ShieldCheck} size={12} />}
                    {gates.ok === false && <Icon icon={TriangleAlert} size={12} />}
                    {gates.text}
                  </span>
                )}
              </span>
            </div>
            {c.verified != null && <CoverageMeter verified={c.verified} claimed={Math.max(c.verified, c.claimed ?? c.verified)} note={null} className="as-cand-cov" />}
          </li>
        )
      })}
    </ul>
  )
}

/* ------------------------------------------------------------------------------------------------ job preview */

function warningText(w: DraftWarning): { text: string; suggestion: string | null } {
  if (typeof w === 'string') return { text: w, suggestion: null }
  const base = w.text ?? w.message ?? (w.phrase ? `“${w.phrase}” may discourage some applicants.` : 'Check this wording.')
  return { text: base, suggestion: w.suggestion ?? null }
}

function salary(d: JobDraft): string | null {
  const min = d.salaryMin ?? null
  const max = d.salaryMax ?? null
  if (min == null && max == null) return null
  if (min != null && max != null) return `${formatMoney(min)} – ${formatMoney(max)}`
  return min != null ? `From ${formatMoney(min)}` : `Up to ${formatMoney(max!)}`
}

function RequirementRow({ r }: { r: DraftRequirement }) {
  const alternatives = (r.alternatives ?? []).filter(Boolean)
  return (
    <li className="as-req">
      <span className="as-req-markers">
        <ReqMarker kind={r.mandatory ? 'must' : 'nice'} />
        {r.knockout && <ReqMarker kind="gate" />}
      </span>
      <span className="as-req-text">
        {r.description}
        <span className="as-req-sub">
          {[r.type ? humanize(r.type) : null, r.minimumYears ? `${r.minimumYears}+ years` : null, r.proficiency ? humanize(r.proficiency) : null, alternatives.length ? `also accepts ${alternatives.join(', ')}` : null]
            .filter(Boolean)
            .join(' · ')}
        </span>
      </span>
    </li>
  )
}

export function JobPreviewView({
  block, action, interactive, busy, onConfirm, onCancel,
}: {
  block: JobPreviewBlock
  /** The create_job action this preview confirms. */
  action: ActionBlock | null
  interactive: boolean
  busy: boolean
  onConfirm: (action: ActionBlock, mode: CreateJobMode) => void
  onCancel: (action: ActionBlock) => void
}) {
  const d = block.draft ?? {}
  const [publishing, setPublishing] = useState(false)
  const [chosen, setChosen] = useState<CreateJobMode | null>(null)
  const now = useNow(action?.status === 'PENDING')
  const status = action ? effectiveStatus(action, now) : null
  const left = action && status === 'PENDING' ? remaining(action.expiresAt, now) : null
  const responsibilities = Array.isArray(d.responsibilities) ? d.responsibilities : (d.responsibilities ?? '').split('\n').map(s => s.replace(/^\s*[-*•]\s*/, '').trim()).filter(Boolean)
  const reqs = d.requirements ?? []
  const must = reqs.filter(r => r.mandatory)
  const nice = reqs.filter(r => !r.mandatory)
  const warnings = (d.warnings ?? []).map(warningText)
  const meta = [d.department, d.location, d.jobType ? humanize(d.jobType) : null, d.experienceLevel ? humanize(d.experienceLevel) : null,
    d.openings ? `${d.openings} opening${d.openings === 1 ? '' : 's'}` : null, salary(d), d.deadline ? `Apply by ${formatDate(d.deadline)}` : null].filter(Boolean)
  const resultLink = action?.result?.link ? safeHref(action.result.link) : null

  return (
    <article className="as-card as-job" aria-label={`Job preview: ${d.title ?? 'untitled'}`}>
      <header className="as-card-head">
        <p className="as-overline">Job posting preview</p>
        <h3 className="as-job-title">{d.title || 'Untitled job'}</h3>
        {meta.length > 0 && <p className="as-job-meta">{meta.join(' · ')}</p>}
      </header>
      {warnings.length > 0 && (
        <Alert tone="warning" title={warnings.length === 1 ? 'One thing to check' : `${warnings.length} things to check`} className="as-job-warn">
          <ul>
            {warnings.map((w, i) => (
              <li key={i}>
                {w.text}
                {w.suggestion && <> Try: <em>{w.suggestion}</em></>}
              </li>
            ))}
          </ul>
        </Alert>
      )}
      {(d.summary || d.description) && <p className="as-job-summary">{d.summary || d.description}</p>}
      {responsibilities.length > 0 && (
        <section className="as-job-sec">
          <h4>What you will do</h4>
          <ul className="as-job-list">{responsibilities.map((r, i) => <li key={i}>{r}</li>)}</ul>
        </section>
      )}
      {reqs.length > 0 && (
        <section className="as-job-sec">
          <h4>
            Requirements <span className="subtle numeric">{must.length} must-have · {nice.length} nice to have</span>
            {d.requirementsSuggested && <span className="as-job-suggested">Suggested from the description</span>}
          </h4>
          <ul className="as-reqs">
            {[...must, ...nice].map((r, i) => <RequirementRow key={i} r={r} />)}
          </ul>
        </section>
      )}
      {typeof d.compilerNote === 'string' && d.compilerNote.trim() && <p className="as-muted as-job-note">{d.compilerNote}</p>}
      {action && (
        <footer className="as-card-foot">
          {status === 'PENDING' && interactive ? (
            publishing ? (
              <div className="as-confirm-row" role="group" aria-label="Confirm publishing">
                <p className="as-confirm-q">Publish now? Candidates can see and apply to it straight away.</p>
                <div className="as-btns">
                  <Button size="sm" onClick={() => setPublishing(false)} disabled={busy}>Not yet</Button>
                  <Button size="sm" variant="primary" loading={busy} onClick={() => { setChosen('PUBLISH'); onConfirm(action, 'PUBLISH') }} autoFocus>
                    Publish now
                  </Button>
                </div>
              </div>
            ) : (
              <div className="as-confirm-row">
                <p className="as-expiry">
                  <Icon icon={Clock} size={12} />
                  {left ? <>Expires in <span className="numeric">{left}</span></> : 'Waiting for your choice'}
                </p>
                <div className="as-btns">
                  <Button size="sm" variant="ghost" onClick={() => onCancel(action)} disabled={busy}>Discard</Button>
                  <Button size="sm" onClick={() => { setChosen('DRAFT'); onConfirm(action, 'DRAFT') }} loading={busy}>Save as draft</Button>
                  <Button size="sm" variant="primary" onClick={() => setPublishing(true)} disabled={busy}>Publish</Button>
                </div>
              </div>
            )
          ) : (
            <p className="as-action-state" data-status={status ?? undefined}>
              <Badge tone={statusTone(status!)} dot>{status === 'CONFIRMED' ? (chosen === 'PUBLISH' || /publish/i.test(action.result?.kind ?? '') ? 'Published' : 'Saved as a draft') : STATUS_TEXT[status!]}</Badge>
              {resultLink?.kind === 'internal' && (
                <Link to={resultLink.to} className="as-inline-link">
                  Open the job <Icon icon={ArrowUpRight} size={12} />
                </Link>
              )}
            </p>
          )}
        </footer>
      )}
    </article>
  )
}

/* ------------------------------------------------------------------------------------------------ action */

function PreviewValue({ value }: { value: unknown }): ReactNode {
  if (value == null || value === '') return <span className="subtle">–</span>
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (typeof value === 'number') return formatNumber(value)
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value.map(v => (typeof v === 'object' ? JSON.stringify(v) : String(v))).join(', ')
  return JSON.stringify(value)
}

export function ActionPreview({ preview }: { preview: unknown }) {
  if (preview == null || preview === '') return null
  if (typeof preview === 'string') return <p className="as-action-preview-text">{preview}</p>
  let pairs: [string, unknown][] = []
  if (Array.isArray(preview)) {
    pairs = preview.map((p, i) =>
      p && typeof p === 'object' && 'label' in p ? [String((p as { label: unknown }).label), (p as { value?: unknown }).value] : [`${i + 1}`, p],
    )
  } else if (typeof preview === 'object') {
    pairs = Object.entries(preview as Record<string, unknown>).map(([k, v]) => [humanize(k.replace(/([a-z])([A-Z])/g, '$1_$2')), v])
  }
  if (pairs.length === 0) return null
  return (
    <dl className="as-action-preview">
      {pairs.map(([k, v], i) => (
        <div key={`${k}-${i}`}>
          <dt>{k}</dt>
          <dd><PreviewValue value={v} /></dd>
        </div>
      ))}
    </dl>
  )
}

export function ActionView({
  block, interactive, busy, compact, onConfirm, onCancel,
}: {
  block: ActionBlock
  interactive: boolean
  busy: boolean
  /** Shown under a job preview: the preview holds the buttons, this only reports the state. */
  compact?: boolean
  onConfirm: (block: ActionBlock) => void
  onCancel: (block: ActionBlock) => void
}) {
  const now = useNow(block.status === 'PENDING')
  const status = effectiveStatus(block, now)
  const left = status === 'PENDING' ? remaining(block.expiresAt, now) : null
  const resultLink = block.result?.link ? safeHref(block.result.link) : null
  if (compact) return null
  return (
    <article className="as-card as-action" data-status={status} aria-label={`Proposed action: ${actionLabel(block.action)}`}>
      <header className="as-card-head as-action-head">
        <p className="as-overline">{actionLabel(block.action)}</p>
        <Badge tone={statusTone(status)} dot={status === 'PENDING' ? 'hollow' : true}>{STATUS_TEXT[status]}</Badge>
      </header>
      <p className="as-action-summary">{block.summary}</p>
      <ActionPreview preview={block.preview} />
      {status === 'FAILED' && block.error && <p className="as-action-error">{block.error}</p>}
      <footer className="as-card-foot">
        {status === 'PENDING' && interactive ? (
          <div className="as-confirm-row">
            <p className="as-expiry">
              <Icon icon={Clock} size={12} />
              {left ? <>Expires in <span className="numeric">{left}</span></> : 'Nothing changes until you confirm'}
            </p>
            <div className="as-btns">
              <Button size="sm" variant="ghost" icon={X} onClick={() => onCancel(block)} disabled={busy}>Cancel</Button>
              <Button size="sm" variant="primary" icon={Check} onClick={() => onConfirm(block)} loading={busy}>Confirm</Button>
            </div>
          </div>
        ) : status === 'PENDING' ? (
          <p className="as-expiry">Waiting for the assistant to finish…</p>
        ) : (
          <p className="as-action-state">
            {status === 'CONFIRMED' ? 'Confirmed and done. Recorded in the audit log as an assistant action.' : status === 'EXPIRED' ? 'Nothing was changed. Ask again to get a fresh proposal.' : status === 'CANCELLED' ? 'Nothing was changed.' : 'Nothing was changed.'}
            {resultLink?.kind === 'internal' && (
              <Link to={resultLink.to} className="as-inline-link">
                Open <Icon icon={ArrowUpRight} size={12} />
              </Link>
            )}
          </p>
        )}
      </footer>
    </article>
  )
}

/* ------------------------------------------------------------------------------------------------ question, link, notice */

export function QuestionView({ block, interactive, onAnswer }: { block: QuestionBlock; interactive: boolean; onAnswer: (text: string) => void }) {
  return (
    <div className="as-question">
      {block.text && <p className="as-question-text">{block.text}</p>}
      {(block.options ?? []).length > 0 && (
        <div className="as-chips" role="group" aria-label={block.text || 'Choose an answer'}>
          {block.options.map(o => (
            <button key={o} type="button" className="as-chip ui-lift" onClick={() => onAnswer(o)} disabled={!interactive}>
              {o}
            </button>
          ))}
        </div>
      )}
      {interactive && <p className="as-hint">Or type your own answer.</p>}
    </div>
  )
}

export function LinkView({ block }: { block: LinkBlock }) {
  const safe = safeHref(block.href)
  if (!safe) return <p className="as-link-card as-link-card--plain">{block.label}</p>
  const inner = (
    <>
      <span>{block.label}</span>
      <Icon icon={ArrowUpRight} size={16} />
    </>
  )
  return safe.kind === 'internal' ? (
    <Link to={safe.to} className="as-link-card ui-lift">{inner}</Link>
  ) : (
    <a href={safe.href} target="_blank" rel="noopener noreferrer" className="as-link-card ui-lift">{inner}</a>
  )
}

export function NoticeView({ block }: { block: NoticeBlock }) {
  const wire = block.tone === 'error' ? 'danger' : block.tone
  const tone = (['info', 'success', 'warning', 'danger', 'neutral'] as const).find(t => t === wire) ?? 'info'
  return <Alert tone={tone} className="as-notice">{block.text}</Alert>
}

/* ------------------------------------------------------------------------------------------------ dispatcher */

export interface BlockHandlers {
  /** Question chips answer only in the latest assistant message. */
  interactive: boolean
  /** Pending actions can be confirmed from any message (the server re-validates); false while a message is sending. */
  canAct: boolean
  busyAction: string | null
  onAnswer: (text: string) => void
  onConfirm: (block: ActionBlock, mode?: CreateJobMode) => void
  onCancel: (block: ActionBlock) => void
}

/** Renders a message's blocks. A job_preview takes over the buttons of its create_job action. */
export function Blocks({ blocks, handlers }: { blocks: Block[]; handlers: BlockHandlers }) {
  const preview = blocks.find((b): b is JobPreviewBlock => b.kind === 'job_preview')
  const linked = preview
    ? blocks.find((b): b is ActionBlock => b.kind === 'action' && (preview.actionId != null ? String(b.actionId) === String(preview.actionId) : b.action === 'create_job')) ?? null
    : null
  return (
    <div className="as-blocks">
      {blocks.map((b, i) => {
        const key = `${b.kind}-${i}`
        switch (b.kind) {
          case 'metrics':
            return <MetricsView key={key} block={b} />
          case 'table':
            return <TableView key={key} block={b} />
          case 'candidates':
            return <CandidatesView key={key} block={b} />
          case 'job_preview':
            return (
              <JobPreviewView
                key={key}
                block={b}
                action={linked}
                interactive={handlers.canAct}
                busy={linked != null && handlers.busyAction === String(linked.actionId)}
                onConfirm={(a, mode) => handlers.onConfirm(a, mode)}
                onCancel={handlers.onCancel}
              />
            )
          case 'action':
            return (
              <ActionView
                key={key}
                block={b}
                compact={linked != null && String(linked.actionId) === String(b.actionId)}
                interactive={handlers.canAct}
                busy={handlers.busyAction === String(b.actionId)}
                onConfirm={a => handlers.onConfirm(a)}
                onCancel={handlers.onCancel}
              />
            )
          case 'question':
            return <QuestionView key={key} block={b} interactive={handlers.interactive} onAnswer={handlers.onAnswer} />
          case 'link':
            return <LinkView key={key} block={b} />
          case 'notice':
            return <NoticeView key={key} block={b} />
          default:
            return null
        }
      })}
    </div>
  )
}

