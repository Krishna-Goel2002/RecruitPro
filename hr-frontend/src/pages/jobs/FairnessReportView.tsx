import { FormEvent, ReactNode, useState } from 'react'
import type { FairnessRange, FairnessReport } from '../../api/types'
import { Field } from '../../components/Field'
import { DateRangeErrors, hasErrors, validateDateRange } from '../../lib/dateRange'
import { useSearchState } from '../../lib/useSearchState'
import { DIMENSION_LABELS, formatRate, groupLabel, humanize } from '../../lib/format'

/**
 * Adverse-impact monitoring (plan 5.7), shared by the job Fairness tab and /admin/fairness. Per dimension and stage:
 * counts, selection rates, the adverse-impact ratio, flagged rows, and the small-group suppression note.
 */
export function FairnessReportView({ report }: { report: FairnessReport }) {
  const minimum = report.minimumGroupSize ?? 5
  return (
    <div className="fairness-report">
      <section className="detail-card fairness-intro">
        <div className="fairness-summary">
          <div>
            <span className="stat-label">Scope</span>
            <strong>{report.scope === 'JOB' ? 'This job' : 'Whole organisation'}</strong>
          </div>
          <div>
            <span className="stat-label">Survey response rate</span>
            <strong>{formatRate(report.surveyResponseRate)}</strong>
          </div>
          <div>
            <span className="stat-label">Smallest group shown</span>
            <strong>{minimum} people</strong>
          </div>
        </div>
        <p className="fairness-explainer">
          <strong>Selection rate</strong> is the share of a group that ever reached the stage; the number under it (<strong>“N reached”</strong>) is how many
          applicants of that group reached the stage, not the size of the group. The <strong>adverse-impact ratio</strong> divides
          the lowest rate by the highest among groups with at least {minimum} people; below 0.80 (the four-fifths rule) the row is flagged
          for review. A flag is a reason to look closer, not proof of bias. Groups smaller than {minimum} are suppressed to protect privacy,
          and “prefer not to say” and non-respondents are excluded. Survey answers are voluntary and never shown to reviewers.
        </p>
        {report.note && <p className="fairness-note" role="note">{report.note}</p>}
      </section>

      {report.dimensions.length === 0 ? (
        <section className="detail-card"><p className="muted-copy">No survey responses yet, so there is nothing to compare.</p></section>
      ) : report.dimensions.map(dimension => {
        const groups = Array.from(new Set(dimension.stages.flatMap(stage => stage.groups.map(group => group.group))))
        const anyFlagged = dimension.stages.some(stage => stage.flagged)
        return (
          <section className="jobs-card fairness-dimension" key={dimension.dimension} aria-labelledby={`fairness-${dimension.dimension}`}>
            <div className="jobs-card-head">
              <div>
                <h2 id={`fairness-${dimension.dimension}`}>{DIMENSION_LABELS[dimension.dimension] ?? humanize(dimension.dimension)}</h2>
                <p>{anyFlagged ? 'At least one stage is below the 0.80 ratio. Review how decisions were made at that stage.' : 'No stage is below the 0.80 ratio.'}</p>
              </div>
              {anyFlagged && <span className="status-badge status-flagged"><i aria-hidden="true" />Flagged</span>}
            </div>
            <div className="job-table-wrap">
              <table className="job-table fairness-table">
                <caption className="sr-only">{DIMENSION_LABELS[dimension.dimension]}: selection rate and number of applicants who reached each stage, by group</caption>
                <thead>
                  <tr>
                    <th scope="col">STAGE</th>
                    {groups.map(group => <th scope="col" key={group}>{groupLabel(group).toUpperCase()}</th>)}
                    <th scope="col">IMPACT RATIO</th>
                  </tr>
                </thead>
                <tbody>
                  {dimension.stages.map(stage => (
                    <tr key={stage.stage} className={stage.flagged ? 'flagged-row' : undefined}>
                      <th scope="row">{humanize(stage.stage)}</th>
                      {groups.map(groupName => {
                        const cell = stage.groups.find(group => group.group === groupName)
                        if (!cell) return <td key={groupName}>—</td>
                        if (cell.count == null) return <td key={groupName} className="suppressed"><span aria-hidden="true">&lt;{minimum}</span><span className="sr-only">Suppressed: this group has fewer than {minimum} applicants</span></td>
                        return (
                          <td key={groupName}>
                            <strong>{formatRate(cell.selectionRate)}</strong>
                            {/* WP-BE-C: `count` is the number of this group's applicants who reached the stage, not the group size. */}
                            <span className="cell-sub">{cell.count} reached</span>
                          </td>
                        )
                      })}
                      <td>
                        {stage.adverseImpactRatio == null ? <span className="muted-copy">Not enough data</span> : (
                          <span className={stage.flagged ? 'ratio flagged' : 'ratio'}>
                            {stage.adverseImpactRatio.toFixed(2)}
                            {stage.flagged && <span className="flag-text"> · below 0.80</span>}
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

/**
 * The report's date range (inclusive ISO days, UTC), kept in the URL as `?from&to` so a shared link shows the same
 * period. Invalid input is reported inline and never sent (the server answers 400 for it).
 */
export function useFairnessRange(): {
  range: FairnessRange
  filter: ReactNode
} {
  const search = useSearchState()
  const appliedFrom = search.get('from')
  const appliedTo = search.get('to')
  const appliedErrors = validateDateRange(appliedFrom, appliedTo)
  // A hand-edited URL with a bad range falls back to "all time" rather than a 400.
  const range: FairnessRange = hasErrors(appliedErrors) ? {} : { from: appliedFrom || undefined, to: appliedTo || undefined }
  return { range, filter: <FairnessRangeForm key={`${appliedFrom}|${appliedTo}`} initialFrom={appliedFrom} initialTo={appliedTo} onApply={(from, to) => search.set({ from, to }, { replace: true })} /> }
}

function FairnessRangeForm({ initialFrom, initialTo, onApply }: { initialFrom: string; initialTo: string; onApply: (from: string | null, to: string | null) => void }) {
  const [from, setFrom] = useState(initialFrom)
  const [to, setTo] = useState(initialTo)
  const [errors, setErrors] = useState<DateRangeErrors>(() => validateDateRange(initialFrom, initialTo))

  function submit(event: FormEvent) {
    event.preventDefault()
    const next = validateDateRange(from, to)
    setErrors(next)
    if (!hasErrors(next)) onApply(from || null, to || null)
  }

  return (
    <form className="export-form fairness-range" onSubmit={submit} noValidate aria-label="Report period">
      <Field label="From" hint="First day included (UTC)" error={errors.from}>
        <input type="date" value={from} max={to || undefined} onChange={event => setFrom(event.target.value)} />
      </Field>
      <Field label="To" hint="Last day included (UTC)" error={errors.to}>
        <input type="date" value={to} min={from || undefined} onChange={event => setTo(event.target.value)} />
      </Field>
      <div className="invite-submit">
        <button type="submit" className="outline-button">Apply</button>
        {(initialFrom || initialTo) && (
          <button type="button" className="secondary-button" onClick={() => onApply(null, null)}>All time</button>
        )}
      </div>
    </form>
  )
}
