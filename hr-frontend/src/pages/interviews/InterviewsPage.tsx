import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { interviewsApi } from '../../api/assessments'
import { qk } from '../../api/queryKeys'
import type { Interview } from '../../api/types'
import { StatusBadge } from '../../components/Badges'
import { ArrowIcon } from '../../components/Icons'
import { PageHeader } from '../../components/Layout'
import { EmptyPanel, ErrorPanel, LoadingPanel } from '../../components/States'
import { formatDateTime } from '../../lib/format'
import { usePageTitle } from '../../lib/hooks'

/** /interviews — interviews assigned to the signed-in staff member (every role). */
export function InterviewsPage() {
  usePageTitle('My interviews')
  const interviews = useQuery({ queryKey: qk.myInterviews, queryFn: ({ signal }) => interviewsApi.mine(signal) })

  const now = Date.now()
  const all = interviews.data ?? []
  const toScore = all.filter(item => item.status === 'SCHEDULED' && !item.scorecard && new Date(item.scheduledAt).getTime() <= now)
  const upcoming = all.filter(item => item.status === 'SCHEDULED' && new Date(item.scheduledAt).getTime() > now).sort(byTime)
  const past = all.filter(item => item.status !== 'SCHEDULED').sort((a, b) => byTime(b, a))

  return (
    <>
      <PageHeader eyebrow="STRUCTURED INTERVIEWS" title="My interviews" subtitle="Ask the kit’s questions, rate each answer against its anchors, and submit your scorecard soon after." />
      {interviews.isPending ? <LoadingPanel label="Loading your interviews…" /> : interviews.isError ? (
        <ErrorPanel error={interviews.error} title="We couldn’t load your interviews" onRetry={() => void interviews.refetch()} />
      ) : all.length === 0 ? (
        <section className="jobs-card"><EmptyPanel title="No interviews assigned to you">When an HR manager schedules you as an interviewer it appears here and you get a notification.</EmptyPanel></section>
      ) : (
        <div className="tab-stack">
          {toScore.length > 0 && <InterviewTable title="Waiting for your scorecard" items={toScore} highlight />}
          <InterviewTable title="Upcoming" items={upcoming} empty="Nothing scheduled ahead." />
          <InterviewTable title="Completed and cancelled" items={past} empty="No past interviews." />
        </div>
      )}
    </>
  )
}

function byTime(a: Interview, b: Interview) {
  return new Date(a.scheduledAt).getTime() - new Date(b.scheduledAt).getTime()
}

function InterviewTable({ title, items, empty, highlight = false }: { title: string; items: Interview[]; empty?: string; highlight?: boolean }) {
  const headingId = `interviews-${title.toLowerCase().replace(/[^a-z]+/g, '-')}`
  return (
    <section className={`jobs-card${highlight ? ' highlight-card' : ''}`} aria-labelledby={headingId}>
      <div className="jobs-card-head"><div><h2 id={headingId}>{title}</h2></div><span className="record-count">{items.length}</span></div>
      {items.length === 0 ? <p className="muted-copy card-pad">{empty}</p> : (
        <div className="job-table-wrap">
          <table className="job-table">
            <thead>
              <tr>
                <th scope="col">CANDIDATE</th>
                <th scope="col">JOB</th>
                <th scope="col">WHEN</th>
                <th scope="col">WHERE</th>
                <th scope="col">STATUS</th>
                <th scope="col"><span className="sr-only">Open</span></th>
              </tr>
            </thead>
            <tbody>
              {items.map(item => (
                <tr key={item.id}>
                  <td><Link className="job-title-link" to={`/interviews/${item.id}`}>{item.candidateName}</Link></td>
                  <td>{item.jobTitle}</td>
                  <td>{formatDateTime(item.scheduledAt)} · {item.durationMinutes} min</td>
                  <td className="wrap-cell">{item.location || '—'}</td>
                  <td><StatusBadge status={item.scorecard && item.status === 'SCHEDULED' ? 'COMPLETED' : item.status} /></td>
                  <td><Link className="row-action" to={`/interviews/${item.id}`} aria-label={`Open interview with ${item.candidateName}`}>{item.scorecard ? 'View' : 'Open'} <ArrowIcon /></Link></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
