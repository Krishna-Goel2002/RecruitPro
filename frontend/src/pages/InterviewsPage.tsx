import { useQuery } from '@tanstack/react-query'
import { interviewsApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { CandidateInterview } from '../api/types'
import { isHttpUrl } from '../lib/url'
import { EmptyState, ErrorBlock, LoadingBlock } from '../components/Feedback'
import { PageHeader, Section } from '../components/PageHeader'
import { formatLongDateTime, formatTime, localTimeZone } from '../lib/format'
import { humanize } from '../lib/labels'

function endOf(interview: CandidateInterview): number {
  return new Date(interview.scheduledAt).getTime() + interview.durationMinutes * 60_000
}

export function InterviewsPage() {
  const interviews = useQuery({ queryKey: queryKeys.interviews, queryFn: interviewsApi.list })
  const now = Date.now()
  const all = interviews.data ?? []
  const upcoming = all
    .filter(interview => interview.status === 'SCHEDULED' && endOf(interview) >= now)
    .sort((a, b) => a.scheduledAt.localeCompare(b.scheduledAt))
  const past = all
    .filter(interview => !upcoming.includes(interview))
    .sort((a, b) => b.scheduledAt.localeCompare(a.scheduledAt))

  return (
    <>
      <PageHeader
        eyebrow="CANDIDATE PORTAL"
        title="Interviews"
        description={`All times are shown in your time zone (${localTimeZone()}).`}
      />
      {interviews.isPending ? (
        <LoadingBlock label="Loading interviews…" />
      ) : interviews.isError ? (
        <ErrorBlock error={interviews.error} onRetry={() => void interviews.refetch()} />
      ) : all.length === 0 ? (
        <EmptyState icon="◷" title="No interviews scheduled">
          When an employer schedules an interview, it appears here and you receive a notification.
        </EmptyState>
      ) : (
        <>
          <Section title="Upcoming" id="upcoming">
            {upcoming.length === 0 ? <p className="muted">No upcoming interviews.</p> : <InterviewList interviews={upcoming} />}
          </Section>
          {past.length > 0 && (
            <Section title="Past and cancelled" id="past">
              <InterviewList interviews={past} />
            </Section>
          )}
        </>
      )}
    </>
  )
}

function InterviewList({ interviews }: { interviews: CandidateInterview[] }) {
  return (
    <ul className="plain-list card-list">
      {interviews.map(interview => (
        <li key={interview.id} className="list-card interview-card">
          <div>
            <span className={`ws-status ws-${interview.status.toLowerCase()}`}>{humanize(interview.status)}</span>
            <h3 className="card-title">{interview.jobTitle}</h3>
            <p>
              <time dateTime={interview.scheduledAt}>{formatLongDateTime(interview.scheduledAt)}</time>
              <span className="muted">
                {' '}
                – {formatTime(new Date(endOf(interview)))} ({interview.durationMinutes} minutes)
              </span>
            </p>
            {interview.location && (
              <p className="muted break-anywhere">
                Where:{' '}
                {isHttpUrl(interview.location) ? (
                  <a href={interview.location} target="_blank" rel="noopener noreferrer">
                    {interview.location}
                    <span className="visually-hidden"> (opens in a new tab)</span>
                  </a>
                ) : (
                  interview.location
                )}
              </p>
            )}
          </div>
        </li>
      ))}
    </ul>
  )
}
