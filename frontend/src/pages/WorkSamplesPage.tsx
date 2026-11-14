import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { workSamplesApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { CandidateWorkSample } from '../api/types'
import { EmptyState, ErrorBlock, LoadingBlock } from '../components/Feedback'
import { PageHeader, Section } from '../components/PageHeader'
import { formatLongDateTime, localTimeZone } from '../lib/format'
import { workSampleStatusLabel } from '../lib/labels'

const OPEN_STATUSES = ['ASSIGNED', 'STARTED']

export function WorkSamplesPage() {
  const samples = useQuery({ queryKey: queryKeys.workSamples, queryFn: workSamplesApi.list })
  const open = samples.data?.filter(sample => OPEN_STATUSES.includes(sample.status)) ?? []
  const done = samples.data?.filter(sample => !OPEN_STATUSES.includes(sample.status)) ?? []

  return (
    <>
      <PageHeader
        eyebrow="CANDIDATE PORTAL"
        title="Work samples"
        description="Short, timed tasks from employers. They show what you can do, beyond what a resume says."
      />
      {samples.isPending ? (
        <LoadingBlock label="Loading work samples…" />
      ) : samples.isError ? (
        <ErrorBlock error={samples.error} onRetry={() => void samples.refetch()} />
      ) : samples.data.length === 0 ? (
        <EmptyState icon="✎" title="No work samples yet">
          When an employer asks you to complete a work sample, it appears here and you receive a notification.
        </EmptyState>
      ) : (
        <>
          <Section title="To do" id="todo" description={`Times are shown in ${localTimeZone()}.`}>
            {open.length === 0 ? <p className="muted">Nothing to do right now.</p> : <SampleList samples={open} />}
          </Section>
          {done.length > 0 && (
            <Section title="Finished" id="finished">
              <SampleList samples={done} />
            </Section>
          )}
        </>
      )}
    </>
  )
}

function SampleList({ samples }: { samples: CandidateWorkSample[] }) {
  return (
    <ul className="plain-list card-list">
      {samples.map(sample => (
        <li key={sample.assignmentId} className="list-card">
          <div>
            <span className={`ws-status ws-${sample.status.toLowerCase()}`}>{workSampleStatusLabel(sample.status)}</span>
            <h3 className="card-title">
              <Link to={`/work-samples/${sample.assignmentId}`} className="card-link">
                {sample.title}
              </Link>
            </h3>
            <p className="muted">
              {sample.jobTitle} · {sample.timeLimitMinutes} minutes
              {sample.dueAt ? ` · start by ${formatLongDateTime(sample.dueAt)}` : ''}
            </p>
          </div>
          <span className="muted view-hint" aria-hidden="true">
            {sample.status === 'ASSIGNED' ? 'Open →' : sample.status === 'STARTED' ? 'Continue →' : 'View →'}
          </span>
        </li>
      ))}
    </ul>
  )
}
