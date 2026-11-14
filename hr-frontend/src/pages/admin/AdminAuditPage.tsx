import { FormEvent, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { adminApi } from '../../api/admin'
import { errorMessage } from '../../api/client'
import { qk } from '../../api/queryKeys'
import { AUDIT_ACTIONS, AuditParams } from '../../api/types'
import { Field } from '../../components/Field'
import { DownloadIcon } from '../../components/Icons'
import { PageHeader } from '../../components/Layout'
import { useNotices } from '../../components/Notices'
import { Pagination } from '../../components/Pagination'
import { EmptyPanel, ErrorPanel, FormError, LoadingPanel } from '../../components/States'
import { formatDateTime, humanize, localDayBoundary } from '../../lib/format'
import { usePageTitle } from '../../lib/hooks'
import { useSearchState } from '../../lib/useSearchState'

function isoDay(date: Date) {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** /admin/audit — the organisation's persistent audit trail, filter by action, CSV export for a date range. */
export function AdminAuditPage() {
  usePageTitle('Audit log')
  const notices = useNotices()
  const state = useSearchState()
  const action = state.get('action')
  const page = state.getPage()
  const params: AuditParams = { page, size: 25, action: action || undefined }
  const events = useQuery({ queryKey: qk.adminAudit(params), queryFn: ({ signal }) => adminApi.audit(params, signal) })

  const today = new Date()
  const [from, setFrom] = useState(isoDay(new Date(today.getTime() - 30 * 86_400_000)))
  const [to, setTo] = useState(isoDay(today))
  const [rangeError, setRangeError] = useState<string | null>(null)

  const exportCsv = useMutation({
    mutationFn: () => adminApi.exportAudit(localDayBoundary(from, false), localDayBoundary(to, true)),
    onSuccess: filename => notices.success(`Downloaded ${filename}. The export itself is recorded in the audit log.`),
    onError: cause => notices.error(errorMessage(cause, 'The export failed.')),
  })

  function submitExport(event: FormEvent) {
    event.preventDefault()
    if (from && to && from > to) {
      setRangeError('“From” must be on or before “To”.')
      return
    }
    setRangeError(null)
    exportCsv.mutate()
  }

  return (
    <>
      <PageHeader eyebrow="ADMINISTRATION" title="Audit log" subtitle="Who did what, when and from where. Entries cannot be edited or deleted." />

      <section className="detail-card form-card" aria-labelledby="export-heading">
        <h2 id="export-heading">Export as CSV</h2>
        <form className="export-form" onSubmit={submitExport} noValidate>
          <Field label="From" hint="Start of this day, your local time" error={rangeError}>
            <input type="date" value={from} max={to || undefined} onChange={event => setFrom(event.target.value)} />
          </Field>
          <Field label="To" hint="End of this day, your local time">
            <input type="date" value={to} min={from || undefined} onChange={event => setTo(event.target.value)} />
          </Field>
          <div className="invite-submit">
            <button type="submit" className="outline-button" disabled={exportCsv.isPending}><DownloadIcon /> {exportCsv.isPending ? 'Exporting…' : 'Download CSV'}</button>
          </div>
        </form>
        <p className="field-hint">Leave both empty for the last 90 days.</p>
        <FormError error={exportCsv.error} />
      </section>

      <section className="jobs-card" aria-labelledby="audit-heading">
        <div className="jobs-card-head">
          <div><h2 id="audit-heading">Events</h2><p>Newest first.</p></div>
          <span className="record-count" aria-live="polite">{events.data ? `${events.data.totalElements} events` : '—'}</span>
        </div>
        <div className="jobs-toolbar">
          <label className="status-filter">
            <span>Action</span>
            <select value={action} onChange={event => state.set({ action: event.target.value })}>
              <option value="">All actions</option>
              {AUDIT_ACTIONS.map(value => <option key={value} value={value}>{humanize(value)}</option>)}
            </select>
          </label>
        </div>
        {events.isPending ? <LoadingPanel label="Loading audit events…" /> : events.isError ? (
          <ErrorPanel error={events.error} onRetry={() => void events.refetch()} />
        ) : events.data.content.length === 0 ? <EmptyPanel title="No events" /> : (
          <>
            <div className="job-table-wrap">
              <table className="job-table audit-table">
                <thead>
                  <tr>
                    <th scope="col">WHEN</th>
                    <th scope="col">ACTION</th>
                    <th scope="col">ACTOR</th>
                    <th scope="col">TARGET</th>
                    <th scope="col">DETAILS</th>
                    <th scope="col">IP</th>
                  </tr>
                </thead>
                <tbody>
                  {events.data.content.map(event => (
                    <tr key={event.id}>
                      <td>{formatDateTime(event.createdAt)}</td>
                      <td><span className="audit-action">{humanize(event.action)}</span></td>
                      <td>{event.actorEmail ?? 'System'}</td>
                      <td>{event.targetType ? `${humanize(event.targetType)}${event.targetId ? ` #${event.targetId}` : ''}` : '—'}</td>
                      <td className="wrap-cell">{event.details ?? '—'}</td>
                      <td>{event.ipAddress ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination page={events.data} onPage={next => state.set({ page: next })} label="events" />
          </>
        )}
      </section>
    </>
  )
}
