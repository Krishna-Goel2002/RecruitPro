import { FormEvent, ReactNode, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import type { FairnessRange, FairnessReport } from '../../api/types'
import { QueryError } from '../../components/QueryError'
import { Badge, Button, EmptyState, Field, Input, LoadingRegion, SegmentedControl, Skeleton, StatRow, Stat } from '../../design/components'
import { formatDate } from '../../design/lib/format'
import { Scale } from '../../design/icons'
import { DateRangeErrors, hasErrors, isIsoDate, validateDateRange } from '../../lib/dateRange'
import { DIMENSION_LABELS, groupLabel, humanize } from '../../lib/format'
import { useSearchState } from '../../lib/useSearchState'
import './jobs.css'

const pct = (rate: number | null | undefined) => (rate == null ? '—' : `${Math.round(rate * 100)}%`)

/**
 * Adverse-impact report (plan 5.7), shared by the job Fairness tab and /admin/fairness: a stat row, a two-line
 * explainer with a "how to read" disclosure, then one table per dimension. Flagged rows carry a warning rule, the word
 * "Flagged" and the sentence that a flag is a reason to look closer, not proof of bias. Groups under the minimum are
 * suppressed.
 */
export function FairnessReportView({ report }: { report: FairnessReport }) {
  const minimum = report.minimumGroupSize ?? 5
  const flaggedCount = report.dimensions.reduce((n, d) => n + d.stages.filter(s => s.flagged).length, 0)
  return (
    <div className="hra-fair">
      <StatRow className="hra-fair-stats">
        <Stat label="Scope" value={0} animate={false} format={() => (report.scope === 'JOB' ? 'This job' : 'Organisation')} />
        <Stat label="Survey response rate" value={Math.round((report.surveyResponseRate ?? 0) * 100)} format={n => `${Math.round(n)}%`} />
        <Stat label="Smallest group shown" value={minimum} format={n => `${Math.round(n)} people`} />
        <Stat label="Stages flagged" value={flaggedCount} />
      </StatRow>
      <div className="hra-fair-explain">
        <p>
          <strong>Selection rate</strong> is the share of a group that ever reached a stage. The <strong>impact ratio</strong> divides the lowest rate by the
          highest; below <strong>0.80</strong> (the four-fifths rule) the stage is flagged. A flag is a reason to look closer, not proof of bias.
        </p>
        <details className="hra-disclosure">
          <summary>How to read this report</summary>
          <ul>
            <li>“18 reached” counts people of that group who reached the stage. It is not the group’s size.</li>
            <li>Groups smaller than {minimum} people are shown as “&lt;{minimum}” and left out of the ratio, to protect privacy.</li>
            <li>“Prefer not to say” and applicants who skipped the voluntary survey are excluded. Answers are never shown to reviewers and never used in ranking.</li>
            <li>A candidate who applied to two jobs counts twice in the organisation report.</li>
          </ul>
        </details>
        {report.note && <p className="subtle">{report.note}</p>}
      </div>

      {report.dimensions.length === 0 ? (
        <EmptyState icon={Scale} title="No survey responses in this period" description="Selection rates appear once applicants answer the voluntary equal-opportunity survey." />
      ) : report.dimensions.map(dimension => {
        const groups = Array.from(new Set(dimension.stages.flatMap(stage => stage.groups.map(group => group.group))))
        const flagged = dimension.stages.filter(stage => stage.flagged).length
        return (
          <section className="hra-fair-dim" key={dimension.dimension} aria-labelledby={`fair-${dimension.dimension}`}>
            <header className="hra-fair-dim-head">
              <h2 id={`fair-${dimension.dimension}`} className="t-h2">{DIMENSION_LABELS[dimension.dimension] ?? humanize(dimension.dimension)}</h2>
              {flagged > 0 ? <Badge tone="warning">{flagged} flagged</Badge> : <span className="subtle">No stage below 0.80</span>}
            </header>
            <div className="hra-scroll" tabIndex={0} role="region" aria-label={`${DIMENSION_LABELS[dimension.dimension]} table`}>
              <table className="hra-fair-table">
                <caption className="sr-only">{DIMENSION_LABELS[dimension.dimension]}: selection rate and number reached per stage and group, with the impact ratio</caption>
                <thead>
                  <tr>
                    <th scope="col">Stage</th>
                    {groups.map(group => <th scope="col" key={group} data-num="">{groupLabel(group)}</th>)}
                    <th scope="col">Impact ratio</th>
                  </tr>
                </thead>
                <tbody>
                  {dimension.stages.map(stage => (
                    <tr key={stage.stage} data-flagged={stage.flagged || undefined}>
                      <th scope="row">
                        {humanize(stage.stage)}
                        {stage.flagged && <Badge tone="warning" className="hra-flag-badge">Flagged</Badge>}
                      </th>
                      {groups.map(groupName => {
                        const cell = stage.groups.find(group => group.group === groupName)
                        if (!cell) return <td key={groupName} data-num="" className="subtle">—</td>
                        if (cell.count == null) {
                          return (
                            <td key={groupName} data-num="" className="subtle">
                              <span aria-hidden="true">&lt;{minimum}</span>
                              <span className="sr-only">Suppressed: fewer than {minimum} applicants</span>
                            </td>
                          )
                        }
                        return (
                          <td key={groupName} data-num="">
                            <span className="hra-fair-rate numeric">{pct(cell.selectionRate)}</span>
                            <span className="hra-fair-n numeric">{cell.count} reached</span>
                          </td>
                        )
                      })}
                      <td>
                        {stage.adverseImpactRatio == null ? <span className="subtle">Not enough data</span> : (
                          <span className="hra-ratio" data-flagged={stage.flagged || undefined}>
                            <span className="numeric">{stage.adverseImpactRatio.toFixed(2)}</span>
                            <span className="hra-ratio-track" aria-hidden="true">
                              <span className="hra-ratio-fill" style={{ ['--v' as string]: Math.min(1, stage.adverseImpactRatio) }} />
                              <span className="hra-ratio-tick" />
                            </span>
                            {stage.flagged && <span className="sr-only">, below 0.80</span>}
                          </span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )
      })}
    </div>
  )
}

// ---------------------------------------------------------------------------------------------------------- range

type Preset = 'all' | '30' | '90' | 'year' | 'custom'

function isoDaysAgo(days: number): string {
  const date = new Date()
  date.setUTCDate(date.getUTCDate() - days)
  return date.toISOString().slice(0, 10)
}

function today() {
  return new Date().toISOString().slice(0, 10)
}

function presetOf(from: string, to: string): Preset {
  if (!from && !to) return 'all'
  if (to === today() && from === isoDaysAgo(29)) return '30'
  if (to === today() && from === isoDaysAgo(89)) return '90'
  if (to === today() && from === `${today().slice(0, 4)}-01-01`) return 'year'
  return 'custom'
}

/**
 * The report's date range (inclusive ISO days, UTC), kept in the URL as `?from&to` so a shared link shows the same
 * period. Presets cover the usual questions; Custom validates before sending (the server answers 400 otherwise).
 * A hand-edited URL with a bad range falls back to all time and says so.
 */
export function useFairnessRange(): { range: FairnessRange; filter: ReactNode; invalidUrl: boolean } {
  const search = useSearchState()
  const appliedFrom = search.get('from')
  const appliedTo = search.get('to')
  const appliedErrors = validateDateRange(appliedFrom, appliedTo)
  const invalidUrl = hasErrors(appliedErrors)
  const range: FairnessRange = invalidUrl ? {} : { from: appliedFrom || undefined, to: appliedTo || undefined }
  const apply = (from: string | null, to: string | null) => search.set({ from, to }, { replace: true })
  return {
    range,
    invalidUrl,
    filter: <FairnessRangeForm key={`${appliedFrom}|${appliedTo}`} from={invalidUrl ? '' : appliedFrom} to={invalidUrl ? '' : appliedTo} onApply={apply} invalidUrl={invalidUrl} />,
  }
}

function FairnessRangeForm({ from, to, onApply, invalidUrl }: { from: string; to: string; onApply: (from: string | null, to: string | null) => void; invalidUrl: boolean }) {
  const [preset, setPreset] = useState<Preset>(() => presetOf(from, to))
  const [customFrom, setCustomFrom] = useState(from)
  const [customTo, setCustomTo] = useState(to)
  const [errors, setErrors] = useState<DateRangeErrors>({})

  function choose(next: Preset) {
    setPreset(next)
    if (next === 'all') onApply(null, null)
    if (next === '30') onApply(isoDaysAgo(29), today())
    if (next === '90') onApply(isoDaysAgo(89), today())
    if (next === 'year') onApply(`${today().slice(0, 4)}-01-01`, today())
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const next = validateDateRange(customFrom, customTo)
    setErrors(next)
    if (!hasErrors(next)) onApply(customFrom || null, customTo || null)
  }

  const label = from || to
    ? `${from && isIsoDate(from) ? formatDate(`${from}T00:00:00Z`) : 'the beginning'} to ${to && isIsoDate(to) ? formatDate(`${to}T00:00:00Z`) : 'today'}`
    : null

  return (
    <div className="hra-range">
      <div className="hra-range-row">
        <SegmentedControl<Preset>
          label="Report period"
          size="sm"
          value={preset}
          onChange={choose}
          options={[
            { value: '30', label: 'Last 30 days' },
            { value: '90', label: 'Last 90 days' },
            { value: 'year', label: 'This year' },
            { value: 'all', label: 'All time' },
            { value: 'custom', label: 'Custom' },
          ]}
        />
        <p className="hra-range-label subtle" aria-live="polite">{label ? `Applications from ${label} (UTC days, inclusive)` : 'All applications so far'}</p>
      </div>
      {invalidUrl && <p className="hra-range-warn" role="status">The dates in the link were not valid, so the report shows all time.</p>}
      {preset === 'custom' && (
        <form className="hra-range-custom" onSubmit={submit} noValidate aria-label="Custom period">
          <Field label="From" error={errors.from}>
            <Input type="date" value={customFrom} max={customTo || undefined} onChange={event => setCustomFrom(event.target.value)} controlSize="sm" />
          </Field>
          <Field label="To" error={errors.to}>
            <Input type="date" value={customTo} min={customFrom || undefined} onChange={event => setCustomTo(event.target.value)} controlSize="sm" />
          </Field>
          <Button type="submit" size="sm">Apply</Button>
        </form>
      )}
    </div>
  )
}

/** The job Fairness tab (managers). */
export function JobFairnessTab({ jobId }: { jobId: number }) {
  const { range, filter } = useFairnessRange()
  const report = useQuery({ queryKey: qk.jobFairness(jobId, range), queryFn: ({ signal }) => reviewApi.fairness(jobId, range, signal) })
  return (
    <div className="stack-6">
      {filter}
      {report.isPending ? (
        <LoadingRegion label="Calculating selection rates">
          <div className="hra-fair-skel">
            <Skeleton width="100%" height={48} />
            {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} width="100%" height={18} />)}
          </div>
        </LoadingRegion>
      ) : report.isError ? (
        <QueryError error={report.error} title="The fairness report could not be loaded" onRetry={() => void report.refetch()} retrying={report.isFetching} />
      ) : (
        <FairnessReportView report={report.data} />
      )}
    </div>
  )
}
