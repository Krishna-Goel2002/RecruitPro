import type { InterviewQuestion, Scorecard } from '../../api/types'
import { formatDateTime, humanize } from '../../lib/format'

/** Read-only scorecard: recommendation, summary and each rating with its question text when the kit is known. */
export function ScorecardView({ scorecard, questions }: { scorecard: Scorecard; questions: InterviewQuestion[] }) {
  return (
    <div className="scorecard-view">
      <p className="scorecard-head">
        <span className={`recommendation rec-${scorecard.recommendation.toLowerCase()}`}>{humanize(scorecard.recommendation)}</span>
        <span className="item-meta">Submitted {formatDateTime(scorecard.submittedAt)}</span>
      </p>
      {scorecard.summary && <p className="description-text">{scorecard.summary}</p>}
      <ol className="rating-list">
        {scorecard.ratings.map(rating => {
          const question = questions.find(item => item.id === rating.questionId)
          return (
            <li key={rating.questionId}>
              <span className="rating-number" aria-label={`Rating ${rating.rating} of 4`}>{rating.rating}</span>
              <span>
                <strong>{question?.question ?? `Question ${rating.questionId}`}</strong>
                {question && <span className="item-meta">Anchor: {question.anchors[String(rating.rating) as '1' | '2' | '3' | '4']}</span>}
                {rating.notes && <span className="rating-notes">{rating.notes}</span>}
              </span>
            </li>
          )
        })}
      </ol>
    </div>
  )
}
