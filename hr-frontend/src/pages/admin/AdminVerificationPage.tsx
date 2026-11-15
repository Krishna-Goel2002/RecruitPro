import { FormEvent, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { qk } from '../../api/queryKeys'
import type { Page, ShadowQueueItem, Verdict, VerificationMetrics, VerificationMode, VerificationSettings, VerificationSourceKind } from '../../api/types'
import { CONNECTOR_KINDS } from '../../api/types'
import { verificationApi } from '../../api/verification'
import { useAuth } from '../../auth/AuthContext'
import { ConfirmDialog } from '../../components/Dialog'
import { Toggle } from '../../components/Field'
import { PageHeader } from '../../components/Layout'
import { useNotices } from '../../components/Notices'
import { Pagination } from '../../components/Pagination'
import { EmptyPanel, ErrorPanel, FormError, LoadingPanel } from '../../components/States'
import { OwnershipIndicator, VerdictBody, VerdictChip, VerdictPicker } from '../../components/Verification'
import { formatDateTime, formatPercent, humanize } from '../../lib/format'
import { usePageTitle } from '../../lib/hooks'
import { useSearchState } from '../../lib/useSearchState'
import { readinessChecks, sourceKindLabel, verdictInfo } from '../../lib/verification'

const QUEUE_PAGE_SIZE = 10

/**
 * /admin/verification — claim verification for the organisation (WP-VBE 2.3): mode and connectors (ADMIN), agreement
 * metrics and the shadow queue where ADMIN / HR_MANAGER label agent verdicts to measure them before switching to VISIBLE.
 */
export function AdminVerificationPage() {
  usePageTitle('Verification')
  const { can } = useAuth()
  const metrics = useQuery({ queryKey: qk.verificationMetrics, queryFn: ({ signal }) => verificationApi.metrics(signal) })
  return (
    <>
      <PageHeader
        eyebrow="ADMINISTRATION"
        title="Claim verification"
        subtitle="Agents check candidates’ claims against sources they connected. In shadow mode results stay hidden from reviewers while your team labels them to measure accuracy."
      />
      {can('verification.settings') && <SettingsSection metrics={metrics.data} />}
      <MetricsSection metrics={metrics.data} pending={metrics.isPending} error={metrics.isError ? metrics.error : null} onRetry={() => void metrics.refetch()} />
      <ShadowQueueSection />
    </>
  )
}

// ---------------------------------------------------------------------------------------------------------------------
// Settings (ADMIN)
// ---------------------------------------------------------------------------------------------------------------------

function SettingsSection({ metrics }: { metrics: VerificationMetrics | undefined }) {
  const settings = useQuery({ queryKey: qk.verificationSettings, queryFn: ({ signal }) => verificationApi.settings(signal) })
  return (
    <section className="jobs-card verification-admin-section" aria-labelledby="verification-settings-heading">
      <div className="jobs-card-head">
        <div>
          <h2 id="verification-settings-heading">Mode and connectors</h2>
          <p>Changes are recorded in the audit log and re-rank affected candidates.</p>
        </div>
      </div>
      <div className="section-body">
        {settings.isPending ? <LoadingPanel compact label="Loading settings…" /> : settings.isError ? (
          <ErrorPanel compact error={settings.error} onRetry={() => void settings.refetch()} />
        ) : <SettingsForm key={settings.dataUpdatedAt} settings={settings.data} readyForVisible={metrics?.readyForVisible ?? false} />}
      </div>
    </section>
  )
}

function SettingsForm({ settings, readyForVisible }: { settings: VerificationSettings; readyForVisible: boolean }) {
  const queryClient = useQueryClient()
  const notices = useNotices()
  const [mode, setMode] = useState<VerificationMode>(settings.mode)
  const [connectors, setConnectors] = useState<Record<VerificationSourceKind, boolean>>({ ...settings.connectors })
  const [confirmOpen, setConfirmOpen] = useState(false)

  const changedConnectors = CONNECTOR_KINDS.filter(kind => Boolean(connectors[kind]) !== Boolean(settings.connectors[kind]))
  const dirty = mode !== settings.mode || changedConnectors.length > 0

  const save = useMutation({
    mutationFn: () => verificationApi.updateSettings({ mode, connectors }),
    onSuccess: updated => {
      setConfirmOpen(false)
      queryClient.setQueryData(qk.verificationSettings, updated)
      notices.success(updated.mode === 'VISIBLE' ? 'Saved. Shared verification results are now visible to reviewers.' : 'Saved.')
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!dirty) return
    if (mode === 'VISIBLE' && settings.mode !== 'VISIBLE' && !readyForVisible) {
      setConfirmOpen(true)
      return
    }
    save.mutate()
  }

  return (
    <form className="settings-form verification-settings" onSubmit={submit} noValidate>
      <fieldset className="mode-choice">
        <legend>Mode</legend>
        <label className={`mode-option${mode === 'SHADOW' ? ' selected' : ''}`}>
          <input type="radio" name="verification-mode" value="SHADOW" checked={mode === 'SHADOW'} onChange={() => setMode('SHADOW')} />
          <span><strong>Shadow</strong><small>Agents run, results are hidden from reviewers and never change rankings. Use the queue below to measure them.</small></span>
        </label>
        <label className={`mode-option${mode === 'VISIBLE' ? ' selected' : ''}`}>
          <input type="radio" name="verification-mode" value="VISIBLE" checked={mode === 'VISIBLE'} onChange={() => setMode('VISIBLE')} />
          <span><strong>Visible</strong><small>Reviewers see results candidates shared. A verified result raises the evidence tier only after a reviewer confirms it.</small></span>
        </label>
      </fieldset>

      <fieldset className="connector-list">
        <legend>Connectors</legend>
        <p className="field-hint">A disabled connector’s results are hidden from your reviewers and never affect your rankings. Candidates can still connect it.</p>
        {CONNECTOR_KINDS.map(kind => (
          <Toggle
            key={kind}
            checked={Boolean(connectors[kind])}
            onChange={checked => setConnectors(current => ({ ...current, [kind]: checked }))}
            label={sourceKindLabel(kind)}
          />
        ))}
      </fieldset>

      {settings.updatedAt && <p className="field-hint">Last changed {formatDateTime(settings.updatedAt)}{settings.updatedByName ? ` by ${settings.updatedByName}` : ''}.</p>}
      <FormError error={confirmOpen ? null : save.error} />
      <div className="form-actions">
        <button type="submit" className="primary-button workspace-primary" disabled={!dirty || save.isPending}>{save.isPending ? 'Saving…' : 'Save changes'}</button>
      </div>

      <ConfirmDialog
        open={confirmOpen}
        title="Switch to visible before the targets are met?"
        message={<>
          The labelled sample does not yet meet the readiness targets (at least 150 labels, false-corroboration rate at most 2%,
          kappa at least 0.70). Reviewers would see results whose accuracy has not been shown for your organisation.
        </>}
        confirmLabel="Switch to visible anyway"
        danger
        busy={save.isPending}
        error={save.error}
        onCancel={() => { setConfirmOpen(false); save.reset() }}
        onConfirm={() => save.mutate()}
      />
    </form>
  )
}

// ---------------------------------------------------------------------------------------------------------------------
// Metrics
// ---------------------------------------------------------------------------------------------------------------------

function formatKappa(value: number | null | undefined) {
  return value == null ? '—' : value.toFixed(2)
}

function formatShare(value: number | null | undefined) {
  return value == null ? '—' : formatPercent(value * 100, 1)
}

function MetricsSection({ metrics, pending, error, onRetry }: { metrics: VerificationMetrics | undefined; pending: boolean; error: unknown; onRetry: () => void }) {
  return (
    <section className="jobs-card verification-admin-section" aria-labelledby="verification-metrics-heading">
      <div className="jobs-card-head">
        <div>
          <h2 id="verification-metrics-heading">Agreement with your reviewers</h2>
          <p>Compares agent verdicts with the labels your team gave in the shadow queue.</p>
        </div>
        {metrics && (
          <span className={`readiness-pill${metrics.readyForVisible ? ' ready' : ''}`}>
            {metrics.readyForVisible ? '✓ Ready for visible mode' : '○ Not ready for visible mode yet'}
          </span>
        )}
      </div>
      <div className="section-body">
        {pending ? <LoadingPanel compact label="Calculating…" /> : error ? <ErrorPanel compact error={error} onRetry={onRetry} /> : metrics ? (
          <>
            <dl className="metric-grid">
              <div><dt>Labelled verdicts</dt><dd>{metrics.labelled}</dd></div>
              <div><dt>Agreement</dt><dd>{formatShare(metrics.agreement)}</dd></div>
              <div><dt>False-corroboration rate</dt><dd>{formatShare(metrics.falseCorroborationRate)}</dd></div>
              <div><dt>Cohen’s kappa</dt><dd>{formatKappa(metrics.kappa)}</dd></div>
            </dl>
            <ul className="readiness-list" aria-label="Readiness for visible mode">
              {readinessChecks(metrics).map(check => (
                <li key={check.key} className={check.met ? 'met' : ''}>
                  <span aria-hidden="true">{check.met ? '✓' : '○'}</span> {check.label}: <strong>{check.detail}</strong>
                  <span className="sr-only">{check.met ? ' (met)' : ' (not met)'}</span>
                </li>
              ))}
            </ul>
            <p className="field-hint">False corroboration: the agent said “Verified” where your reviewer did not. It is the error that matters most, because it would raise a candidate unfairly.</p>

            {metrics.byVerifier.length > 0 && (
              <div className="job-table-wrap">
                <table className="job-table">
                  <caption className="table-caption">By verifier</caption>
                  <thead><tr><th scope="col">VERIFIER</th><th scope="col">LABELLED</th><th scope="col">AGREEMENT</th><th scope="col">FALSE CORROBORATION</th><th scope="col">KAPPA</th></tr></thead>
                  <tbody>
                    {metrics.byVerifier.map(row => (
                      <tr key={row.verifier}>
                        <th scope="row">{row.verifier}</th>
                        <td>{row.labelled}</td>
                        <td>{formatShare(row.agreement)}</td>
                        <td>{formatShare(row.falseCorroborationRate)}</td>
                        <td>{formatKappa(row.kappa)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <div className="job-table-wrap">
              <table className="job-table">
                <caption className="table-caption">By verdict</caption>
                <thead><tr><th scope="col">VERDICT</th><th scope="col">AGENT SAID</th><th scope="col">REVIEWERS SAID</th><th scope="col">BOTH</th><th scope="col">PRECISION</th><th scope="col">RECALL</th></tr></thead>
                <tbody>
                  {metrics.byVerdict.map(row => (
                    <tr key={row.verdict}>
                      <th scope="row"><VerdictChip verdict={row.verdict} /></th>
                      <td>{row.predicted}</td>
                      <td>{row.gold}</td>
                      <td>{row.agreed}</td>
                      <td>{formatShare(row.precision)}</td>
                      <td>{formatShare(row.recall)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        ) : null}
      </div>
    </section>
  )
}

// ---------------------------------------------------------------------------------------------------------------------
// Shadow queue
// ---------------------------------------------------------------------------------------------------------------------

function ShadowQueueSection() {
  const state = useSearchState()
  const page = state.getPage()
  const queue = useQuery({
    queryKey: qk.shadowQueue(page),
    queryFn: ({ signal }) => verificationApi.shadowQueue(page, QUEUE_PAGE_SIZE, signal),
    placeholderData: previous => previous,
  })
  return (
    <section className="jobs-card verification-admin-section" aria-labelledby="shadow-queue-heading">
      <div className="jobs-card-head">
        <div>
          <h2 id="shadow-queue-heading">Shadow queue</h2>
          <p>Shared results from applicants to your jobs, unlabelled first. Read the reasons and evidence, then record what you think the right verdict is.</p>
        </div>
        <span className="record-count" aria-live="polite">{queue.data ? `${queue.data.totalElements} results` : '—'}</span>
      </div>
      <div className="section-body">
        {queue.isPending ? <LoadingPanel compact label="Loading the queue…" /> : queue.isError ? (
          <ErrorPanel compact error={queue.error} onRetry={() => void queue.refetch()} />
        ) : queue.data.content.length === 0 ? (
          <EmptyPanel compact title="Nothing to label">Results appear here when candidates who applied to your jobs share verification results.</EmptyPanel>
        ) : (
          <>
            <ul className="card-list verification-claims">
              {queue.data.content.map(item => <QueueItem key={item.verdictId} item={item} page={page} />)}
            </ul>
            <Pagination page={queue.data} onPage={next => state.set({ page: next })} label="results" />
          </>
        )}
      </div>
    </section>
  )
}

function QueueItem({ item, page }: { item: ShadowQueueItem; page: number }) {
  const queryClient = useQueryClient()
  const notices = useNotices()
  const [label, setLabel] = useState<Verdict | null>(item.myLabel?.labelVerdict ?? null)
  const [note, setNote] = useState(item.myLabel?.note ?? '')
  const [problem, setProblem] = useState<string | null>(null)

  const save = useMutation({
    mutationFn: () => verificationApi.label(item.verdictId, { labelVerdict: label as Verdict, note: note.trim() || undefined }),
    onSuccess: saved => {
      queryClient.setQueryData<Page<ShadowQueueItem>>(qk.shadowQueue(page), current => current && {
        ...current,
        content: current.content.map(entry => entry.verdictId === item.verdictId
          ? { ...entry, myLabel: saved, labelCount: entry.myLabel ? entry.labelCount : entry.labelCount + 1 }
          : entry),
      })
      void queryClient.invalidateQueries({ queryKey: qk.verificationMetrics })
      notices.success(`Label saved: ${verdictInfo(saved.labelVerdict).label}.`)
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!label) {
      setProblem('Choose the verdict you think is right.')
      return
    }
    if (note.length > 1000) {
      setProblem('Keep the note under 1000 characters.')
      return
    }
    setProblem(null)
    save.mutate()
  }

  const noteId = `queue-note-${item.verdictId}`
  return (
    <li className="item-card verdict-card">
      <div className="item-card-head">
        <div>
          <p className="eyebrow">{humanize(item.claimType)} · {item.subjectType === 'CANDIDATE_CLAIM' ? 'Candidate claim' : 'From the resume'}</p>
          <h3>{item.value}</h3>
        </div>
        {item.myLabel ? <span className="label-status labelled">✓ You labelled this</span> : <span className="label-status">Not labelled by you</span>}
      </div>
      <div className="verdict-head">
        <span className="item-meta">Agent said</span>
        <VerdictChip verdict={item.verdict} />
        <OwnershipIndicator proven={item.ownershipProven} />
        <span className="item-meta">{sourceKindLabel(item.sourceKind)} · {item.verifier} · {formatDateTime(item.createdAt)} · {item.labelCount} label{item.labelCount === 1 ? '' : 's'}</span>
      </div>
      <VerdictBody
        reasons={item.reasons}
        integrityNotes={item.integrityNotes}
        probeQuestion={item.probeQuestion}
        evidence={item.evidence}
        evidenceCount={item.evidenceCount}
        hiddenEvidenceText="Sources stay hidden until the candidate is revealed on their application."
      />
      <form className="label-form" onSubmit={submit} noValidate>
        <VerdictPicker value={label} onChange={value => { setLabel(value); setProblem(null) }} legend="Your verdict" invalid={Boolean(problem) && !label} disabled={save.isPending} />
        <div className="field">
          <label htmlFor={noteId}>Note <span className="optional-mark">(optional)</span></label>
          <textarea id={noteId} rows={2} maxLength={1000} value={note} onChange={event => setNote(event.target.value)} disabled={save.isPending} />
        </div>
        {problem && <p className="field-error" role="alert">{problem}</p>}
        <FormError error={save.error} />
        <div className="item-actions">
          <button type="submit" className="outline-button" disabled={save.isPending}>{save.isPending ? 'Saving…' : item.myLabel ? 'Update label' : 'Save label'}</button>
        </div>
      </form>
    </li>
  )
}
