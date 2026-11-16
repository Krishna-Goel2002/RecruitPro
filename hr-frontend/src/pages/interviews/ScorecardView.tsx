import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { interviewsApi } from '../../api/assessments'
import type { InterviewQuestion, Scorecard } from '../../api/types'
import { QueryError } from '../../components/QueryError'
import { Button, Skeleton } from '../../design/components'
import { History } from '../../design/icons'
import { relativeTime } from '../../design/lib/format'
import { formatDateTime } from '../../lib/format'
import './interviews.css'

const REC: Record<string, string> = { STRONG_NO: 'Strong no', NO: 'No', YES: 'Yes', STRONG_YES: 'Strong yes' }

/**
 * Read-only scorecard: recommendation, summary, then each question with its rating and the anchor it matched. An
 * edited scorecard can show its earlier versions (`interviewId` + `canSeeRevisions`: the author, ADMIN, HR_MANAGER).
 */
export function ScorecardView({ scorecard, questions, interviewId, canSeeRevisions }: { scorecard: Scorecard; questions: InterviewQuestion[]; interviewId?: number; canSeeRevisions?: boolean }) {
  const edited = (scorecard.revisionCount ?? 0) > 0
  return (
    <div className="sc-view">
      <div className="sc-view-head">
        <span className="sc-rec" data-rec={scorecard.recommendation}>{REC[scorecard.recommendation] ?? scorecard.recommendation}</span>
        <span className="sc-muted">
          Submitted <time dateTime={scorecard.submittedAt}>{relativeTime(scorecard.submittedAt)}</time>
          {(scorecard.revisionCount ?? 0) > 0 && <> · edited {scorecard.revisionCount} {scorecard.revisionCount === 1 ? 'time' : 'times'}</>}
        </span>
      </div>
      {scorecard.summary && <p className="sc-summary">{scorecard.summary}</p>}
      {scorecard.ratings.length > 0 && (
        <ol className="sc-ratings">
          {scorecard.ratings.map((r, i) => {
            const q = questions.find(x => x.id === r.questionId)
            return (
              <li key={r.questionId}>
                <span className="sc-pips" role="img" aria-label={`Rating ${r.rating} of 4`}>
                  {[1, 2, 3, 4].map(n => <i key={n} data-on={n <= r.rating || undefined} />)}
                  <b className="numeric">{r.rating}</b>
                </span>
                <div className="sc-rating-text">
                  <p className="sc-q">{q?.question ?? `Question ${i + 1}`}</p>
                  {q && <p className="sc-muted">Anchor: {q.anchors[String(r.rating) as '1' | '2' | '3' | '4']}</p>}
                  {r.notes && <p className="sc-notes">{r.notes}</p>}
                </div>
              </li>
            )
          })}
        </ol>
      )}
      {edited && interviewId != null && canSeeRevisions && <EarlierVersions interviewId={interviewId} questions={questions} count={scorecard.revisionCount ?? 0} />}
    </div>
  )
}

function EarlierVersions({ interviewId, questions, count }: { interviewId: number; questions: InterviewQuestion[]; count: number }) {
  const [open, setOpen] = useState(false)
  const revisions = useQuery({
    queryKey: ['interviews', interviewId, 'scorecard-revisions', count],
    queryFn: ({ signal }) => interviewsApi.scorecardRevisions(interviewId, signal),
    enabled: open,
  })
  return (
    <div className="sc-revisions">
      <Button size="sm" variant="ghost" icon={History} aria-expanded={open} onClick={() => setOpen(v => !v)}>
        {open ? 'Hide earlier versions' : count === 1 ? 'Show the earlier version' : `Show earlier versions (${count})`}
      </Button>
      {open && (revisions.isPending ? <Skeleton width="100%" height={48} />
        : revisions.isError ? <QueryError error={revisions.error} title="Earlier versions could not be loaded" onRetry={() => void revisions.refetch()} compact />
          : (
            <ol className="sc-revision-list" aria-label="Earlier versions, newest first">
              {revisions.data.map(revision => (
                <li key={revision.id}>
                  <p className="sc-muted">Replaced {formatDateTime(revision.revisedAt)}{revision.revisedByName ? ` by ${revision.revisedByName}` : ''}. Before that edit:</p>
                  <ScorecardView scorecard={{ ...revision.previous, revisionCount: 0 }} questions={questions} />
                </li>
              ))}
            </ol>
          ))}
    </div>
  )
}
