import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { adminApi } from '../../api/admin'
import { qk } from '../../api/queryKeys'
import type { AdminDashboard, HealthValue } from '../../api/types'
import { useCurrentUser } from '../../auth/AuthContext'
import { QueryError } from '../../components/QueryError'
import { applicationStatusLabel, jobStatusLabel } from '../../components/StatusBadges'
import { LoadingRegion, PageHeader, Skeleton, SkeletonText, Stat, StatRow } from '../../design/components'
import { ChevronRight, Icon } from '../../design/icons'
import { formatNumber, plural } from '../../design/lib/format'
import './admin.css'

type Service = { key: string; name: string; value: HealthValue | null | undefined; down: string; unconfigured?: string }

/** /admin: service status first (what needs attention), then counts and the status mix. */
export function AdminDashboardPage() {
  const user = useCurrentUser()
  const q = useQuery({ queryKey: qk.adminDashboard, queryFn: ({ signal }) => adminApi.dashboard(signal), refetchInterval: 60_000 })

  return (
    <div className="adm" data-width="wide">
      <PageHeader title="Overview" meta={[user.organizationName ?? 'Your organisation', 'Refreshes every minute']} />
      {q.isPending ? (
        <LoadingRegion label="Loading overview"><div className="adm-skel"><SkeletonText lines={5} /><Skeleton height={60} /></div></LoadingRegion>
      ) : q.isError ? (
        <QueryError error={q.error} title="We couldn’t load the overview" onRetry={() => void q.refetch()} retrying={q.isFetching} />
      ) : <Overview data={q.data} />}
    </div>
  )
}

function Overview({ data }: { data: AdminDashboard }) {
  const h = data.health
  const services: Service[] = [
    { key: 'backend', name: 'Application server', value: h?.backend ?? data.backendHealth, down: 'Staff and candidates cannot sign in or save.' },
    { key: 'db', name: 'Database', value: h?.database ?? data.databaseHealth, down: 'Nothing can be read or saved until it is back.' },
    { key: 'ai', name: 'AI service', value: h?.aiService ?? data.aiServiceHealth, down: 'Resume analysis and matching wait in the queue; nothing is lost.', unconfigured: 'Resumes are not analysed automatically.' },
    { key: 'agent', name: 'Verification agents', value: h?.agentService, down: 'Verification runs wait until it is back; candidates see “Checking”.', unconfigured: 'Claim verification is not set up on this server.' },
  ]
  const queue = h?.queue
  const attention = services.filter(s => s.value === 'DOWN').length + (queue && (queue.failed > 0 || queue.dead > 0) ? 1 : 0)

  return (
    <>
      <section className="adm-section" aria-labelledby="svc-h">
        <div className="adm-section-head">
          <h2 id="svc-h" className="t-h2">Service status</h2>
          <p className="adm-sub">{attention === 0 ? 'Everything is running.' : `${plural(attention, 'thing needs', 'things need')} attention.`}</p>
        </div>
        <ul className="adm-services">
          {services.map(s => {
            const v = s.value ?? 'UNKNOWN'
            return (
              <li key={s.key} data-state={v}>
                <span className="adm-dot" aria-hidden="true" />
                <span className="adm-svc-name">{s.name}</span>
                <span className="adm-svc-state">{v === 'UP' ? 'Up' : v === 'DOWN' ? 'Down' : v === 'UNCONFIGURED' ? 'Not configured' : 'Unknown'}</span>
                <span className="adm-svc-hint">{v === 'DOWN' ? s.down : v === 'UNCONFIGURED' ? s.unconfigured ?? '' : ''}</span>
              </li>
            )
          })}
          {queue && (
            <li data-state={queue.dead > 0 ? 'DOWN' : queue.failed > 0 ? 'WARN' : 'UP'}>
              <span className="adm-dot" aria-hidden="true" />
              <span className="adm-svc-name">Background queue</span>
              <span className="adm-svc-state numeric">{formatNumber(queue.pending)} pending</span>
              <span className="adm-svc-hint">
                {queue.failed > 0 && <>{plural(queue.failed, 'job')} failed and will be retried. </>}
                {queue.dead > 0 && <>{plural(queue.dead, 'job')} stopped after every retry; an engineer should look.</>}
              </span>
            </li>
          )}
        </ul>
      </section>

      <section className="adm-section" aria-labelledby="cnt-h">
        <h2 id="cnt-h" className="t-h2">In your organisation</h2>
        <StatRow className="adm-stats">
          <Stat label="Staff accounts" value={data.totalUsers} format={formatNumber} />
          <Stat label="Jobs" value={data.totalJobs} format={formatNumber} />
          <Stat label="Applications" value={data.totalApplications} format={formatNumber} />
          <Stat label="Applications, last 7 days" value={data.recentApplicationsCount} format={formatNumber} />
          <Stat label="Candidates who applied" value={data.totalCandidates} format={formatNumber} />
        </StatRow>
      </section>

      <div className="adm-mix">
        <Mix title="Applications by status" data={data.applicationsByStatus} label={applicationStatusLabel} order={['SUBMITTED', 'SHORTLISTED', 'INTERVIEWING', 'SELECTED', 'REJECTED', 'WITHDRAWN']} />
        <Mix title="Jobs by status" data={data.jobsByStatus} label={jobStatusLabel} order={['PUBLISHED', 'DRAFT', 'ON_HOLD', 'CLOSED', 'CANCELLED']} link={{ to: '/admin/jobs', label: 'Job oversight' }} />
      </div>
    </>
  )
}

function Mix({ title, data, label, order, link }: { title: string; data: Record<string, number>; label: (s: string) => string; order: string[]; link?: { to: string; label: string } }) {
  const keys = [...order.filter(k => k in data), ...Object.keys(data).filter(k => !order.includes(k))]
  const total = keys.reduce((n, k) => n + (data[k] ?? 0), 0)
  return (
    <section className="adm-section adm-mix-col" aria-label={title}>
      <div className="adm-section-head">
        <h2 className="t-h3">{title}</h2>
        {link && <Link className="adm-more" to={link.to}>{link.label}<Icon icon={ChevronRight} size={14} /></Link>}
      </div>
      <div className="adm-bar" aria-hidden="true">
        {keys.map((k, i) => <span key={k} data-i={i} style={{ ['--w' as string]: total ? data[k] / total : 0, ['--d' as string]: `${i * 40}ms` }} />)}
      </div>
      <ul className="adm-legend">
        {keys.map((k, i) => (
          <li key={k}><span className="adm-swatch" data-i={i} aria-hidden="true" /><span>{label(k)}</span><span className="numeric">{formatNumber(data[k] ?? 0)}</span><span className="adm-pct numeric">{total ? Math.round((data[k] / total) * 100) : 0}%</span></li>
        ))}
      </ul>
    </section>
  )
}
