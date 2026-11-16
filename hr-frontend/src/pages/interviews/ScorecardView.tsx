import type { InterviewQuestion, Scorecard } from '../../api/types'
import { relativeTime } from '../../design/lib/format'
import './interviews.css'

const REC: Record<string, string> = { STRONG_NO: 'Strong no', NO: 'No', YES: 'Yes', STRONG_YES: 'Strong yes' }

/** Read-only scorecard: recommendation, summary, then each question with its rating and the anchor it matched. */
export function ScorecardView({ scorecard, questions }: { scorecard: Scorecard; questions: InterviewQuestion[] }) {
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
    </div>
  )
}
