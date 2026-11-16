import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import type { CandidateInterview } from '../api/types'
import { ErrorState } from '../components/Feedback'
import { Badge, Button, EmptyState, LoadingRegion, PageHeader, Section, Skeleton, buttonProps } from '../design/components'
import { ArrowUpRight, Calendar, CalendarClock, Icon } from '../design/icons'
import { relativeTime } from '../design/lib/format'
import { downloadInterviewIcs, timeRange, timeZoneLabel } from '../lib/calendar'
import { humanize } from '../lib/labels'
import { interviewEnd } from '../lib/nextStep'
import { interviewsQuery } from '../lib/queries'
import { isHttpUrl } from '../lib/url'
import './common.css'
import './tasks/tasks.css'

/** Interviews: upcoming first with Join and Add to calendar; times in the viewer's zone, named in plain words. */
export function InterviewsPage() {
  const interviews = useQuery(interviewsQuery)
  const now = Date.now()
  const all = interviews.data ?? []
  const upcoming = all.filter(i => i.status === 'SCHEDULED' && interviewEnd(i) >= now).sort((a, b) => a.scheduledAt.localeCompare(b.scheduledAt))
  const past = all.filter(i => !upcoming.includes(i)).sort((a, b) => b.scheduledAt.localeCompare(a.scheduledAt))

  return (
    <>
      <PageHeader title="Interviews" description={`Times are shown in your time zone, ${timeZoneLabel()}.`} />
      {interviews.isPending ? (
        <LoadingRegion label="Loading interviews">
          {[0, 1].map(i => (
            <div key={i} className="c-skel-row">
              <Skeleton width={240} height={12} />
              <Skeleton width={180} height={10} />
            </div>
          ))}
        </LoadingRegion>
      ) : interviews.isError ? (
        <ErrorState error={interviews.error} onRetry={() => void interviews.refetch()} title="Interviews could not be loaded" />
      ) : all.length === 0 ? (
        <EmptyState
          icon={CalendarClock}
          title="No interviews yet"
          description="When an employer schedules one, it appears here and in your overview, with a calendar file and the meeting link."
        />
      ) : (
        <>
          <Section title="Upcoming" id="upcoming">
            {upcoming.length === 0 ? <p className="c-muted">No upcoming interviews.</p> : <InterviewRows interviews={upcoming} />}
          </Section>
          {past.length > 0 && (
            <Section title="Past and cancelled" id="past">
              <InterviewRows interviews={past} past />
            </Section>
          )}
        </>
      )}
    </>
  )
}

function InterviewRows({ interviews, past }: { interviews: CandidateInterview[]; past?: boolean }) {
  return (
    <ul className="c-rows" data-closed="">
      {interviews.map(i => {
        const start = new Date(i.scheduledAt)
        const r = timeRange(i.scheduledAt, i.durationMinutes)
        const link = i.location && isHttpUrl(i.location) ? i.location : null
        const live = Date.now() >= start.getTime() - 15 * 60_000 && Date.now() <= interviewEnd(i)
        return (
          <li key={i.id} className="iv-row" data-past={past || undefined}>
            <span className="iv-date" aria-hidden="true">
              <span className="iv-date-m">{new Intl.DateTimeFormat('en-IN', { month: 'short' }).format(start)}</span>
              <span className="iv-date-d numeric">{start.getDate()}</span>
            </span>
            <div className="iv-main">
              <p className="t-strong">{i.applicationId ? <Link to={`/applications/${i.applicationId}`}>{i.jobTitle}</Link> : i.jobTitle}</p>
              <p className="c-meta-line">
                <span>
                  <time dateTime={i.scheduledAt}>
                    {r.day}, {r.time}
                  </time>
                </span>
                <span>{i.durationMinutes} minutes</span>
                {!past && <span>{relativeTime(i.scheduledAt)}</span>}
                {i.location && !link && <span>{i.location}</span>}
              </p>
              {(past || i.status !== 'SCHEDULED') && (
                <span>
                  <Badge tone="neutral" variant={i.status === 'CANCELLED' ? 'struck' : 'solid'}>
                    {/* A past interview still SCHEDULED on the server has taken place; "Scheduled" read as upcoming. */}
                    {i.status === 'SCHEDULED' ? 'Ended' : humanize(i.status)}
                  </Badge>
                </span>
              )}
            </div>
            {!past && (
              <div className="iv-actions">
                <Button size="sm" icon={Calendar} onClick={() => downloadInterviewIcs(i, i.jobTitle)}>
                  Add to calendar<span className="sr-only">: {i.jobTitle} interview</span>
                </Button>
                {link && (
                  <a {...buttonProps(live ? 'primary' : 'secondary', 'sm')} href={link} target="_blank" rel="noopener noreferrer">
                    Join
                    <Icon icon={ArrowUpRight} size={14} />
                    <span className="sr-only"> the {i.jobTitle} interview (opens in a new tab)</span>
                  </a>
                )}
              </div>
            )}
          </li>
        )
      })}
    </ul>
  )
}
