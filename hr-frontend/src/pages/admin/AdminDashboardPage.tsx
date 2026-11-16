import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { adminApi } from '../../api/admin'
import { qk } from '../../api/queryKeys'
import type { AdminDashboard, HealthValue } from '../../api/types'
import { useOptionalAssistant } from '../../assistant/AssistantProvider'
import { useCurrentUser } from '../../auth/AuthContext'
import { QueryError } from '../../components/QueryError'
import { applicationStatusLabel, jobStatusLabel } from '../../components/StatusBadges'
import {
  Button, CountUp, LoadingRegion, PageHeader, Reveal, Skeleton, SkeletonText, StackedBar, StackedBarSwatch, Stat, StatRow, type StackedBarTone,
} from '../../design/components'
import {
  ArrowRight, BriefcaseBusiness, ChevronRight, CircleCheck, Icon, Inbox, MessagesSquare, Pencil, TriangleAlert, type IconComponent,
} from '../../design/icons'
import { formatNumber, plural } from '../../design/lib/format'
import './admin.css'

type Service = { key: string; name: string; value: HealthValue | null | undefined; down: string; unconfigured?: string }

/** One "needs attention" item: a count, what it means, and where to act on it. */
export interface AttentionItem {
  key: string
  tone: 'danger' | 'warning' | 'info'
  icon: IconComponent
  count: number
  title: string
  detail: string
  to: string
  cta: string
}

function services(data: AdminDashboard): Service[] {
  const h = data.health
  return [
    { key: 'backend', name: 'Application server', value: h?.backend ?? data.backendHealth, down: 'Staff and candidates cannot sign in or save.' },
    { key: 'db', name: 'Database', value: h?.database ?? data.databaseHealth, down: 'Nothing can be read or saved until it is back.' },
    { key: 'ai', name: 'AI service', value: h?.aiService ?? data.aiServiceHealth, down: 'Resume analysis and matching wait in the queue; nothing is lost.', unconfigured: 'Resumes are not analysed automatically.' },
    { key: 'agent', name: 'Verification agents', value: h?.agentService, down: 'Verification runs wait until it is back; candidates see “Checking”.', unconfigured: 'Claim verification is not set up on this server.' },
  ]
}

/** What needs an admin's attention, most urgent first; each item links to the screen where it is handled. */
export function attentionItems(data: AdminDashboard): AttentionItem[] {
  const items: AttentionItem[] = []
  const down = services(data).filter(s => s.value === 'DOWN')
  if (down.length > 0) {
    items.push({
      key: 'down', tone: 'danger', icon: TriangleAlert, count: down.length,
      title: down.length === 1 ? `${down[0].name} is down` : `${down.length} services are down`,
      detail: down[0].down, to: '#service-status', cta: 'See service status',
    })
  }
  const queue = data.health?.queue
  if (queue && (queue.dead > 0 || queue.failed > 0)) {
    items.push({
      key: 'queue', tone: queue.dead > 0 ? 'danger' : 'warning', icon: TriangleAlert, count: queue.dead + queue.failed,
      title: queue.dead > 0 ? `${plural(queue.dead, 'background job')} stopped` : `${plural(queue.failed, 'background job')} failed`,
      detail: queue.dead > 0 ? 'They stopped after every retry; an engineer should look.' : 'They will be retried automatically.',
      to: '#service-status', cta: 'See the queue',
    })
  }
  const submitted = data.applicationsByStatus?.SUBMITTED ?? 0
  if (submitted > 0) {
    items.push({
      key: 'review', tone: 'info', icon: Inbox, count: submitted,
      title: `${plural(submitted, 'application')} to review`,
      detail: 'Submitted and not yet shortlisted or declined.', to: '/jobs?status=PUBLISHED', cta: 'Open published jobs',
    })
  }
  const drafts = data.jobsByStatus?.DRAFT ?? 0
  if (drafts > 0) {
    items.push({
      key: 'drafts', tone: 'info', icon: Pencil, count: drafts,
      title: `${plural(drafts, 'draft job')} not published`, detail: 'Candidates cannot see drafts yet.', to: '/jobs?status=DRAFT', cta: 'Open drafts',
    })
  }
  const onHold = data.jobsByStatus?.ON_HOLD ?? 0
  if (onHold > 0) {
    items.push({
      key: 'hold', tone: 'warning', icon: BriefcaseBusiness, count: onHold,
      title: `${plural(onHold, 'job')} on hold`, detail: 'Not accepting applications until resumed.', to: '/jobs?status=ON_HOLD', cta: 'Open jobs on hold',
    })
  }
  return items
}

/**
 * /admin: what needs attention first (with links), then the organisation's numbers, the status mix as stacked bars,
 * and service status in detail. An "Ask" entry point sends "What needs my attention?" to the assistant.
 */
export function AdminDashboardPage() {
  const user = useCurrentUser()
  const assistant = useOptionalAssistant()
  const q = useQuery({ queryKey: qk.adminDashboard, queryFn: ({ signal }) => adminApi.dashboard(signal), refetchInterval: 60_000 })

  return (
    <div className="adm" data-width="wide">
      <PageHeader
        title="Overview"
        meta={[user.organizationName ?? 'Your organisation', 'Refreshes every minute']}
        actions={assistant && (
          <Button icon={MessagesSquare} onClick={() => assistant.ask('What needs my attention?')}>
            Ask what needs attention
          </Button>
        )}
      />
      {q.isPending ? (
        <LoadingRegion label="Loading overview">
          <div className="adm-skel">
            <div className="adm-attn-skel">{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} height={104} />)}</div>
            <SkeletonText lines={2} />
            <Skeleton height={60} />
          </div>
        </LoadingRegion>
      ) : q.isError ? (
        <QueryError error={q.error} title="We couldn’t load the overview" onRetry={() => void q.refetch()} retrying={q.isFetching} />
      ) : <Overview data={q.data} />}
    </div>
  )
}

function Attention({ items }: { items: AttentionItem[] }) {
  if (items.length === 0) {
    return (
      <section className="adm-attn-ok" aria-labelledby="attn-h">
        <Icon icon={CircleCheck} size={20} />
        <div>
          <h2 id="attn-h" className="t-h3">Nothing needs your attention</h2>
          <p className="adm-sub">Every service is running and no application is waiting for a first review.</p>
        </div>
      </section>
    )
  }
  return (
    <section className="adm-attn" aria-labelledby="attn-h">
      <h2 id="attn-h" className="adm-attn-title">Needs attention <span className="adm-attn-count numeric">{items.length}</span></h2>
      <ul className="adm-attn-list ui-stagger" role="list">
        {items.map(item => {
          const body = (
            <>
              <span className="adm-attn-top">
                <span className="adm-attn-icon" aria-hidden="true"><Icon icon={item.icon} size={16} /></span>
                <span className="adm-attn-num numeric"><CountUp value={item.count} format={n => formatNumber(Math.round(n))} /></span>
              </span>
              <span className="adm-attn-name">{item.title}</span>
              <span className="adm-attn-detail">{item.detail}</span>
              <span className="adm-attn-cta">{item.cta}<Icon icon={ArrowRight} size={14} /></span>
            </>
          )
          return (
            <li key={item.key}>
              {item.to.startsWith('#') ? (
                <a className="adm-attn-card ui-lift" data-tone={item.tone} href={item.to}>{body}</a>
              ) : (
                <Link className="adm-attn-card ui-lift" data-tone={item.tone} to={item.to}>{body}</Link>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}

function Overview({ data }: { data: AdminDashboard }) {
  const list = services(data)
  const queue = data.health?.queue
  const problems = list.filter(s => s.value === 'DOWN').length + (queue && (queue.failed > 0 || queue.dead > 0) ? 1 : 0)

  return (
    <>
      <Attention items={attentionItems(data)} />

      <Reveal className="adm-section" aria-labelledby="cnt-h" role="region">
        <h2 id="cnt-h" className="t-h2">In your organisation</h2>
        <StatRow className="adm-stats">
          <Stat label="Staff accounts" value={data.totalUsers} format={formatNumber} />
          <Stat label="Jobs" value={data.totalJobs} format={formatNumber} />
          <Stat label="Applications" value={data.totalApplications} format={formatNumber} />
          <Stat label="Applications, last 7 days" value={data.recentApplicationsCount} format={formatNumber} />
          <Stat label="Candidates who applied" value={data.totalCandidates} format={formatNumber} />
        </StatRow>
      </Reveal>

      <div className="adm-mix">
        <Mix title="Applications by status" data={data.applicationsByStatus} label={applicationStatusLabel} order={['SUBMITTED', 'SHORTLISTED', 'INTERVIEWING', 'SELECTED', 'REJECTED', 'WITHDRAWN']} link={{ to: '/jobs', label: 'Jobs' }} />
        <Mix title="Jobs by status" data={data.jobsByStatus} label={jobStatusLabel} order={['PUBLISHED', 'DRAFT', 'ON_HOLD', 'CLOSED', 'CANCELLED']} link={{ to: '/admin/jobs', label: 'Job oversight' }} />
      </div>

      <Reveal className="adm-section" id="service-status" aria-labelledby="svc-h" role="region">
        <div className="adm-section-head">
          <h2 id="svc-h" className="t-h2">Service status</h2>
          <p className="adm-sub">{problems === 0 ? 'Everything is running.' : `${plural(problems, 'thing needs', 'things need')} attention.`}</p>
        </div>
        <ul className="adm-services">
          {list.map(s => {
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
      </Reveal>
    </>
  )
}

function Mix({ title, data, label, order, link }: { title: string; data: Record<string, number>; label: (s: string) => string; order: string[]; link?: { to: string; label: string } }) {
  const keys = [...order.filter(k => k in data), ...Object.keys(data).filter(k => !order.includes(k))]
  const total = keys.reduce((n, k) => n + (data[k] ?? 0), 0)
  const tone = (i: number) => ((i % 6) + 1) as StackedBarTone
  return (
    <Reveal className="adm-section adm-mix-col" aria-label={title} role="region">
      <div className="adm-section-head">
        <h2 className="t-h3">{title} <span className="adm-mix-total numeric">{formatNumber(total)}</span></h2>
        {link && <Link className="adm-more" to={link.to}>{link.label}<Icon icon={ChevronRight} size={14} /></Link>}
      </div>
      <StackedBar label={title} segments={keys.map((k, i) => ({ key: k, label: label(k), value: data[k] ?? 0, tone: tone(i) }))} />
      <ul className="adm-legend">
        {keys.map((k, i) => (
          <li key={k}><StackedBarSwatch tone={tone(i)} /><span>{label(k)}</span><span className="numeric">{formatNumber(data[k] ?? 0)}</span><span className="adm-pct numeric">{total ? Math.round((data[k] / total) * 100) : 0}%</span></li>
        ))}
      </ul>
    </Reveal>
  )
}
