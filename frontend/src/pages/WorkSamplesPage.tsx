import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import type { CandidateWorkSample } from '../api/types'
import { ErrorState } from '../components/Feedback'
import { Badge, EmptyState, LoadingRegion, PageHeader, Section, Skeleton, type Tone } from '../design/components'
import { ChevronRight, Icon, ListChecks } from '../design/icons'
import { formatDate, relativeTime } from '../design/lib/format'
import { workSampleStatusLabel } from '../lib/labels'
import { workSamplesQuery } from '../lib/queries'
import './common.css'
import './tasks/tasks.css'

const OPEN_STATUSES = ['ASSIGNED', 'STARTED']

export const WS_TONE: Record<string, { tone: Tone; variant?: 'solid' | 'outline' }> = {
  ASSIGNED: { tone: 'neutral', variant: 'outline' },
  STARTED: { tone: 'info' },
  SUBMITTED: { tone: 'info' },
  SCORED: { tone: 'success' },
  EXPIRED: { tone: 'neutral' },
  CANCELLED: { tone: 'neutral', variant: 'outline' },
}

export function WorkSampleBadge({ status }: { status: string }) {
  const t = WS_TONE[status] ?? { tone: 'neutral' as Tone }
  return (
    <Badge tone={t.tone} variant={t.variant ?? 'solid'}>
      {workSampleStatusLabel(status)}
    </Badge>
  )
}

/** Work samples: To do first (with start-by), then finished; each row opens the task. */
export function WorkSamplesPage() {
  const samples = useQuery(workSamplesQuery)
  const open = samples.data?.filter(s => OPEN_STATUSES.includes(s.status)) ?? []
  const done = samples.data?.filter(s => !OPEN_STATUSES.includes(s.status)) ?? []

  return (
    <>
      <PageHeader title="Work samples" description="Short, timed tasks from employers. They show what you can do, beyond what a resume says." />
      {samples.isPending ? (
        <LoadingRegion label="Loading work samples">
          {[0, 1].map(i => (
            <div key={i} className="c-skel-row">
              <Skeleton width={280} height={12} />
              <Skeleton width={200} height={10} />
            </div>
          ))}
        </LoadingRegion>
      ) : samples.isError ? (
        <ErrorState error={samples.error} onRetry={() => void samples.refetch()} title="Work samples could not be loaded" />
      ) : samples.data.length === 0 ? (
        <EmptyState
          icon={ListChecks}
          title="No work samples yet"
          description="When an employer asks you to complete one, it appears here and in your overview, and you get a notification."
        />
      ) : (
        <>
          <Section title="To do" id="todo" description={open.length ? 'Instructions appear when you press Start. The timer cannot be paused.' : undefined}>
            {open.length === 0 ? <p className="c-muted">Nothing to do right now.</p> : <SampleRows samples={open} />}
          </Section>
          {done.length > 0 && (
            <Section title="Finished" id="finished">
              <SampleRows samples={done} />
            </Section>
          )}
        </>
      )}
    </>
  )
}

function SampleRows({ samples }: { samples: CandidateWorkSample[] }) {
  return (
    <ul className="c-rows" data-closed="">
      {samples.map(s => (
        <li key={s.assignmentId} className="c-linkrow t-row">
          <div className="t-row-main">
            <p className="t-row-title">
              <Link to={`/work-samples/${s.assignmentId}`} className="c-linkrow-link">
                {s.title}
              </Link>
            </p>
            <p className="c-meta-line">
              <span>{s.jobTitle}</span>
              <span>{s.timeLimitMinutes} minutes</span>
              {s.status === 'ASSIGNED' && s.dueAt && (
                <span className="t-due" data-soon={new Date(s.dueAt).getTime() - Date.now() < 86_400_000 * 2 || undefined}>
                  Start by {formatDate(s.dueAt, { weekday: 'short', day: 'numeric', month: 'short' })} ({relativeTime(s.dueAt)})
                </span>
              )}
              {s.submittedAt && <span>Submitted {formatDate(s.submittedAt)}</span>}
            </p>
          </div>
          <WorkSampleBadge status={s.status} />
          <Icon icon={ChevronRight} size={16} className="t-chev" />
        </li>
      ))}
    </ul>
  )
}
