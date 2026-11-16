import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { interviewsApi } from '../../api/assessments'
import { qk } from '../../api/queryKeys'
import type { Interview } from '../../api/types'
import { QueryError } from '../../components/QueryError'
import { Badge, EmptyState, LoadingRegion, PageHeader, Skeleton, SkeletonText, buttonProps } from '../../design/components'
import { CalendarClock, ChevronRight, Icon, Link2, Building2 } from '../../design/icons'
import { plural, relativeTime } from '../../design/lib/format'
import { safeHttpUrl } from '../../lib/format'
import { dayLabel, timeOf, timeZoneLabel } from '../../lib/when'
import './interviews.css'

/** /interviews: interviews assigned to me (FLOWS-HR-B §3 step 4). Scorecards waiting first, then upcoming by day. */
export function InterviewsPage() {
  const query = useQuery({ queryKey: qk.myInterviews, queryFn: ({ signal }) => interviewsApi.mine(signal) })
  const now = Date.now()
  const all = query.data ?? []
  const waiting = all.filter(i => i.status === 'SCHEDULED' && !i.scorecard && new Date(i.scheduledAt).getTime() <= now).sort(byTime)
  const upcoming = all.filter(i => i.status === 'SCHEDULED' && new Date(i.scheduledAt).getTime() > now).sort(byTime)
  const past = all.filter(i => i.status !== 'SCHEDULED' || (i.scorecard && new Date(i.scheduledAt).getTime() <= now)).sort((a, b) => byTime(b, a))
  const days = groupByDay(upcoming)

  return (
    <div className="iv" data-width="wide">
      <PageHeader
        title="My interviews"
        meta={query.isSuccess ? [
          waiting.length ? <span key="w" className="iv-meta-warn">{plural(waiting.length, 'scorecard')} waiting</span> : <span key="w">No scorecards waiting</span>,
          <span key="u">{plural(upcoming.length, 'upcoming interview')}</span>,
          <span key="tz">Times in {timeZoneLabel()}</span>,
        ] : undefined}
      />
      {query.isPending ? (
        <LoadingRegion label="Loading your interviews">
          <div className="iv-skel">{Array.from({ length: 4 }, (_, i) => <div key={i} className="iv-row"><Skeleton width={56} height={20} /><SkeletonText lines={2} /><Skeleton width={90} height={20} /></div>)}</div>
        </LoadingRegion>
      ) : query.isError ? (
        <QueryError error={query.error} title="We couldn’t load your interviews" onRetry={() => void query.refetch()} retrying={query.isFetching} />
      ) : all.length === 0 ? (
        <EmptyState icon={CalendarClock} title="No interviews assigned to you" description="When an HR manager schedules you as an interviewer, it appears here and you get a notification." />
      ) : (
        <div className="iv-groups">
          {waiting.length > 0 && (
            <section className="iv-group" aria-labelledby="iv-wait" data-tone="warning">
              <h2 id="iv-wait" className="iv-group-title">Waiting for your scorecard <span className="iv-count numeric">{waiting.length}</span></h2>
              <ul className="iv-list">{waiting.map(i => <Row key={i.id} interview={i} waiting />)}</ul>
            </section>
          )}
          <section className="iv-group" aria-labelledby="iv-up">
            <h2 id="iv-up" className="iv-group-title">Upcoming <span className="iv-count numeric">{upcoming.length}</span></h2>
            {upcoming.length === 0 ? <p className="iv-none">Nothing scheduled ahead.</p> : days.map(([day, items]) => (
              <div key={day} className="iv-day">
                <h3 className="iv-day-label">{day}</h3>
                <ul className="iv-list">{items.map(i => <Row key={i.id} interview={i} />)}</ul>
              </div>
            ))}
          </section>
          {past.length > 0 && (
            <section className="iv-group" aria-labelledby="iv-past">
              <h2 id="iv-past" className="iv-group-title">Completed and cancelled <span className="iv-count numeric">{past.length}</span></h2>
              <ul className="iv-list">{past.map(i => <Row key={i.id} interview={i} past />)}</ul>
            </section>
          )}
        </div>
      )}
    </div>
  )
}

function byTime(a: Interview, b: Interview) {
  return new Date(a.scheduledAt).getTime() - new Date(b.scheduledAt).getTime()
}

function groupByDay(items: Interview[]): [string, Interview[]][] {
  const map = new Map<string, Interview[]>()
  for (const i of items) {
    const key = dayLabel(i.scheduledAt)
    map.set(key, [...(map.get(key) ?? []), i])
  }
  return [...map.entries()]
}

function Row({ interview: i, waiting, past }: { interview: Interview; waiting?: boolean; past?: boolean }) {
  const link = safeHttpUrl(i.location)
  return (
    <li className="iv-row" data-waiting={waiting || undefined} data-cancelled={i.status === 'CANCELLED' || undefined}>
      <div className="iv-when">
        <span className="iv-time numeric">{timeOf(i.scheduledAt)}</span>
        <span className="iv-dur">{waiting || past ? dayLabel(i.scheduledAt) : `${i.durationMinutes} min`}</span>
      </div>
      <div className="iv-who">
        <Link to={`/interviews/${i.id}`} className="iv-link">{i.candidateName}</Link>
        <span className="iv-sub">{i.jobTitle}{i.location && <> · {link ? <span className="iv-loc"><Icon icon={Link2} size={12} />Video call</span> : <span className="iv-loc"><Icon icon={Building2} size={12} />{i.location}</span>}</>}</span>
      </div>
      <div className="iv-state">
        {waiting ? <span {...buttonProps('primary', 'sm')} aria-hidden="true">Write scorecard</span>
          : i.scorecard ? <Badge tone="success">Scorecard in</Badge>
            : i.status === 'CANCELLED' ? <Badge tone="neutral" variant="outline">Cancelled</Badge>
              : <span className="iv-rel">{relativeTime(i.scheduledAt)}</span>}
        <Icon icon={ChevronRight} size={16} className="iv-chev" />
      </div>
    </li>
  )
}
