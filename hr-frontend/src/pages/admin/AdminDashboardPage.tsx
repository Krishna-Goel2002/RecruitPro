import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { adminApi } from '../../api/admin'
import { qk } from '../../api/queryKeys'
import type { AdminDashboard, HealthValue } from '../../api/types'
import { StatusBadge } from '../../components/Badges'
import { BriefcaseIcon, DocumentIcon, RefreshIcon, UsersIcon } from '../../components/Icons'
import { PageHeader } from '../../components/Layout'
import { ErrorPanel, LoadingPanel } from '../../components/States'
import { formatNumber, humanize } from '../../lib/format'
import { usePageTitle } from '../../lib/hooks'

/** /admin — organisation metrics plus real dependency health and the background-job queue (M11). */
export function AdminDashboardPage() {
  usePageTitle('Administration')
  const dashboard = useQuery({ queryKey: qk.adminDashboard, queryFn: ({ signal }) => adminApi.dashboard(signal) })

  return (
    <>
      <PageHeader
        eyebrow="ADMINISTRATION"
        title="Organisation overview"
        subtitle="Hiring activity in your organisation and the health of the services RecruitPro depends on."
        actions={<button type="button" className="outline-button" onClick={() => void dashboard.refetch()} disabled={dashboard.isFetching}><RefreshIcon /> {dashboard.isFetching ? 'Refreshing…' : 'Refresh'}</button>}
      />
      {dashboard.isPending ? <LoadingPanel label="Loading overview…" /> : dashboard.isError ? (
        <ErrorPanel error={dashboard.error} title="We couldn’t load the overview" onRetry={() => void dashboard.refetch()} />
      ) : <Dashboard data={dashboard.data} />}
    </>
  )
}

function Dashboard({ data }: { data: AdminDashboard }) {
  const health = data.health ?? {
    backend: data.backendHealth ?? 'UNKNOWN',
    database: data.databaseHealth ?? 'UNKNOWN',
    aiService: data.aiServiceHealth ?? 'UNKNOWN',
    queue: null,
  }
  const queue = data.health?.queue
  return (
    <>
      <section className="stat-grid" aria-label="Organisation counts">
        <Stat label="Staff accounts" value={data.totalUsers} icon={<UsersIcon />} />
        <Stat label="Applicants" value={data.totalCandidates} icon={<UsersIcon />} tone="published" />
        <Stat label="Jobs" value={data.totalJobs} icon={<BriefcaseIcon />} tone="draft" />
        <Stat label="Applications" value={data.totalApplications} icon={<DocumentIcon />} tone="closed" />
      </section>

      <section className="jobs-card" aria-labelledby="health-heading">
        <div className="jobs-card-head">
          <div>
            <h2 id="health-heading">Service health</h2>
            <p>Checked live when this page loads: database query, AI service /health (2-second timeout) and the background-job queue.</p>
          </div>
        </div>
        <div className="health-grid">
          <HealthCard title="Backend API" value={health.backend} />
          <HealthCard title="Database" value={health.database} />
          <HealthCard title="AI service" value={health.aiService} hint="Resume parsing and matching wait in the queue while it is down." />
          {data.health?.agentService && (
            <HealthCard title="Verification agents" value={data.health.agentService} hint="Candidate verification runs wait and retry while it is down; nothing else is affected." />
          )}
          {queue ? (
            <div className={`health-card${queue.dead > 0 ? ' warn' : ''}`}>
              <strong>Background queue</strong>
              <dl className="queue-counts">
                <div><dt>Pending</dt><dd>{formatNumber(queue.pending)}</dd></div>
                <div><dt>Failed</dt><dd>{formatNumber(queue.failed)}</dd></div>
                <div><dt>Dead</dt><dd>{formatNumber(queue.dead)}</dd></div>
              </dl>
              <small>{queue.dead > 0 ? 'Dead jobs stopped retrying; affected resumes show “analysis failed” and can be retried by HR.' : 'Parsing and matching jobs retry with backoff.'}</small>
            </div>
          ) : (
            <div className="health-card"><strong>Background queue</strong><small>Not reported by this server version.</small></div>
          )}
        </div>
      </section>

      <div className="two-column">
        <Breakdown title="Applications by status" total={data.totalApplications} values={data.applicationsByStatus} footnote={`${formatNumber(data.recentApplicationsCount)} received recently`} />
        <Breakdown title="Jobs by status" total={data.totalJobs} values={data.jobsByStatus} />
      </div>
    </>
  )
}

function Stat({ label, value, icon, tone = '' }: { label: string; value: number; icon: ReactNode; tone?: string }) {
  return (
    <article className="stat-card">
      <span className={`stat-icon ${tone}`} aria-hidden="true">{icon}</span>
      <div><span className="stat-label">{label}</span><strong className="stat-value">{formatNumber(value)}</strong></div>
    </article>
  )
}

function HealthCard({ title, value, hint }: { title: string; value: HealthValue; hint?: string }) {
  const up = value === 'UP'
  const tone = up ? 'published' : value === 'UNCONFIGURED' ? 'on_hold' : 'cancelled'
  return (
    <div className={`health-card${up ? '' : ' warn'}`}>
      <strong>{title}</strong>
      <StatusBadge status={tone} label={humanize(value)} />
      {!up && hint && <small>{hint}</small>}
    </div>
  )
}

function Breakdown({ title, total, values, footnote }: { title: string; total: number; values: Record<string, number>; footnote?: string }) {
  const entries = Object.entries(values ?? {}).sort((a, b) => b[1] - a[1])
  const headingId = `breakdown-${title.toLowerCase().replace(/[^a-z]+/g, '-')}`
  return (
    <section className="jobs-card" aria-labelledby={headingId}>
      <div className="jobs-card-head"><div><h2 id={headingId}>{title}</h2>{footnote && <p>{footnote}</p>}</div><span className="record-count">{formatNumber(total)} total</span></div>
      {entries.length === 0 ? <p className="muted-copy card-pad">Nothing yet.</p> : (
        <ul className="breakdown-list">
          {entries.map(([status, count]) => (
            <li key={status}>
              <StatusBadge status={status} />
              <span className="breakdown-bar" aria-hidden="true"><span style={{ width: `${total ? (count / total) * 100 : 0}%` }} /></span>
              <strong>{formatNumber(count)}</strong>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
