import { useState, type FormEvent } from 'react'
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query'
import { adminApi } from '../../api/admin'
import { qk } from '../../api/queryKeys'
import { AUDIT_ACTIONS } from '../../api/types'
import type { AuditEvent, AuditParams } from '../../api/types'
import { InlineError, QueryError } from '../../components/QueryError'
import { Button, DataTable, EmptyState, Field, Input, PageHeader, Pagination, Select, Tooltip, useToast, type Column } from '../../design/components'
import { Download, ScrollText } from '../../design/icons'
import { formatNumber, relativeTime } from '../../design/lib/format'
import { humanize, localDayBoundary } from '../../lib/format'
import { fullDateTime } from '../../lib/when'
import { useSearchState } from '../../lib/useSearchState'
import './admin.css'

const isoDay = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

/** /admin/audit: the event log (compact, newest first, filter by action) and a CSV export for a date range. */
export function AdminAuditPage() {
  const { toast } = useToast()
  const state = useSearchState()
  const action = state.get('action')
  const page = state.getPage()
  const params: AuditParams = { page, size: 25, action: action || undefined }
  const events = useQuery({ queryKey: qk.adminAudit(params), queryFn: ({ signal }) => adminApi.audit(params, signal), placeholderData: keepPreviousData })
  const today = new Date()
  const [from, setFrom] = useState(isoDay(new Date(today.getTime() - 30 * 86_400_000)))
  const [to, setTo] = useState(isoDay(today))
  const [rangeError, setRangeError] = useState<string | null>(null)
  const exporter = useMutation({
    mutationFn: () => adminApi.exportAudit(localDayBoundary(from, false), localDayBoundary(to, true)),
    onSuccess: ({ filename, truncated }) => truncated
      ? toast({ tone: 'warning', title: `Exported ${filename} (incomplete)`, body: 'Only the first 50,000 rows were exported. Narrow the date range.' })
      : toast({ tone: 'success', title: `Exported ${filename}`, body: 'The export itself is recorded in the audit log.' }),
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (from && to && from > to) return setRangeError('“From” must be on or before “To”.')
    setRangeError(null)
    exporter.mutate()
  }

  const columns: Column<AuditEvent>[] = [
    { key: 'when', header: 'When', width: '130px', cell: e => <time dateTime={e.createdAt} title={fullDateTime(e.createdAt)} className="numeric">{relativeTime(e.createdAt)}</time> },
    { key: 'action', header: 'Action', primary: true, cell: e => <Tooltip content={e.action} labelOnly><span className="adm-strong" tabIndex={0}>{humanize(e.action)}</span></Tooltip> },
    { key: 'actor', header: 'Actor', cell: e => (e.actorEmail ? <span className="adm-faint">{e.actorEmail}</span> : <span className="adm-faint">System</span>) },
    { key: 'target', header: 'Target', hideWhenStacked: true, cell: e => (e.targetType ? <span className="mono adm-faint">{humanize(e.targetType).toLowerCase()} #{e.targetId}</span> : '—') },
    { key: 'details', header: 'Details', cell: e => <span className="adm-details">{e.details ?? '—'}</span> },
    { key: 'ip', header: 'IP', width: '120px', hideWhenStacked: true, cell: e => <span className="mono adm-faint">{e.ipAddress ?? '—'}</span> },
  ]

  return (
    <div className="adm" data-width="wide">
      <PageHeader title="Audit log" meta={[events.data ? `${formatNumber(events.data.totalElements)} events` : '…', 'Every reveal, decision, verification, download and setting change']} />
      <form className="adm-export" onSubmit={submit} noValidate aria-label="Export audit log">
        <Field label="From"><Input type="date" value={from} max={to || undefined} onChange={e => setFrom(e.target.value)} /></Field>
        <Field label="To" error={rangeError}><Input type="date" value={to} min={from || undefined} onChange={e => setTo(e.target.value)} /></Field>
        <Button type="submit" icon={Download} loading={exporter.isPending}>Export CSV</Button>
        <p className="adm-faint adm-export-hint">Your local days. Defaults to the last 30 days. The export is itself audited.</p>
        <InlineError error={exporter.error} />
      </form>
      {events.isError && !events.data ? <QueryError error={events.error} title="We couldn’t load the audit log" onRetry={() => void events.refetch()} retrying={events.isFetching} /> : (
        <DataTable
          caption="Audit events, newest first"
          columns={columns}
          rows={events.data?.content ?? []}
          rowKey={e => e.id}
          density="compact"
          loading={events.isPending}
          toolbar={
            <div className="adm-toolbar">
              <Select aria-label="Action" value={action} onChange={e => state.set({ action: e.target.value })}>
                <option value="">All actions</option>
                {AUDIT_ACTIONS.map(a => <option key={a} value={a}>{humanize(a)}</option>)}
              </Select>
            </div>
          }
          empty={<EmptyState compact icon={ScrollText} title={action ? 'No events of this kind' : 'No events yet'} action={action ? <Button size="sm" variant="secondary" onClick={() => state.set({ action: null })}>Show all actions</Button> : undefined} />}
        />
      )}
      {events.data && events.data.totalElements > 25 && <Pagination page={page + 1} pageSize={25} total={events.data.totalElements} onPageChange={p => state.set({ page: p - 1 })} />}
    </div>
  )
}
