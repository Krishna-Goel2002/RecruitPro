import { FormEvent, useId, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { interviewsApi } from '../../api/assessments'
import { qk } from '../../api/queryKeys'
import { InterviewDetail, Rating, Recommendation, RECOMMENDATIONS, ScorecardInput } from '../../api/types'
import { useAuth, useCurrentUser } from '../../auth/AuthContext'
import { RequirementTags, StatusBadge, TierBadge } from '../../components/Badges'
import { ConfirmDialog } from '../../components/Dialog'
import { useNotices } from '../../components/Notices'
import { AnchorList, RatingScale } from '../../components/RatingScale'
import { ErrorPanel, FormError, LoadingPanel } from '../../components/States'
import { formatDateTime, safeHttpUrl } from '../../lib/format'
import { usePageTitle } from '../../lib/hooks'
import { ScorecardView } from './ScorecardView'

const RECOMMENDATION_TEXT: Record<Recommendation, string> = {
  STRONG_NO: 'Strong no',
  NO: 'No',
  YES: 'Yes',
  STRONG_YES: 'Strong yes',
}

/** /interviews/:interviewId — kit questions, focus requirements and the scorecard form (assigned interviewer only). */
export function InterviewDetailPage() {
  const { interviewId } = useParams()
  const id = Number(interviewId)
  const interview = useQuery({ queryKey: qk.interview(id), queryFn: ({ signal }) => interviewsApi.detail(id, signal) })
  usePageTitle(interview.data ? `Interview · ${interview.data.candidateName}` : 'Interview')
  if (interview.isPending) return <LoadingPanel label="Loading interview…" />
  if (interview.isError) return <ErrorPanel error={interview.error} title="We couldn’t load this interview" onRetry={() => void interview.refetch()} />
  return <InterviewView interview={interview.data} />
}

function InterviewView({ interview }: { interview: InterviewDetail }) {
  const user = useCurrentUser()
  const { can } = useAuth()
  const isMine = interview.interviewerUserId === user.id
  const canScore = isMine && interview.status === 'SCHEDULED' && !interview.scorecard
  const locationUrl = safeHttpUrl(interview.location)
  const linkToApplicant = can('jobs.view')

  return (
    <>
      <Link className="back-link" to="/interviews">← Back to my interviews</Link>
      <section className="detail-card">
        <div className="detail-heading">
          <div>
            <p className="eyebrow">{interview.jobTitle.toUpperCase()} · INTERVIEW #{interview.id}</p>
            <h1>{interview.candidateName}</h1>
            <div className="detail-tags">
              <span>{formatDateTime(interview.scheduledAt)}</span>
              <span>{interview.durationMinutes} minutes</span>
              <span>Interviewer: {isMine ? 'you' : interview.interviewerName}</span>
              {interview.location && (locationUrl ? <a href={locationUrl} target="_blank" rel="noopener noreferrer">Join link ↗</a> : <span>{interview.location}</span>)}
            </div>
          </div>
          <StatusBadge status={interview.status} />
        </div>
        {linkToApplicant && (
          <div className="detail-actions">
            <Link className="outline-button" to={`/jobs/${interview.jobId}/applicants/${interview.applicationId}`}>Open the application</Link>
          </div>
        )}
      </section>

      <div className="applicant-layout">
        <div className="applicant-main">
          {interview.focusRequirements.length > 0 && (
            <section className="detail-card section-card" aria-labelledby="focus-heading">
              <h2 id="focus-heading">Focus on these requirements</h2>
              <p className="section-intro">Evidence so far is weak or only claimed. Use the questions below to probe them.</p>
              <ul className="focus-list">
                {interview.focusRequirements.map(item => (
                  <li key={item.requirementId}>
                    <div className="focus-title">
                      <strong>{item.description}</strong>
                      <TierBadge tier={item.tier} />
                      <RequirementTags mandatory={item.mandatory} knockout={item.knockout} minimumYears={item.minimumYears} />
                    </div>
                    {item.verificationHint && <p className="verification-hint">{item.verificationHint}</p>}
                  </li>
                ))}
              </ul>
            </section>
          )}

          {canScore ? (
            <ScorecardForm interview={interview} />
          ) : (
            <section className="detail-card section-card" aria-labelledby="kit-heading">
              <h2 id="kit-heading">Interview questions</h2>
              {interview.kit.length === 0 ? <p className="muted-copy">This job has no interview kit yet.</p> : (
                <ol className="question-list">
                  {interview.kit.map(question => (
                    <li key={question.id} className="question-card">
                      <p className="question-text">{question.question}</p>
                      {question.followUps.length > 0 && <ul className="follow-ups">{question.followUps.map((followUp, index) => <li key={index}>{followUp}</li>)}</ul>}
                      <AnchorList anchors={question.anchors} />
                    </li>
                  ))}
                </ol>
              )}
            </section>
          )}
        </div>
        <aside className="applicant-aside">
          <section className="detail-card section-card" aria-labelledby="scorecard-heading">
            <h2 id="scorecard-heading">Scorecard</h2>
            {interview.scorecard ? <ScorecardView scorecard={interview.scorecard} questions={interview.kit} /> : (
              <p className="muted-copy">
                {interview.status === 'CANCELLED' ? 'This interview was cancelled.'
                  : isMine ? 'Fill in the scorecard on the left after the interview.'
                    : `${interview.interviewerName} has not submitted a scorecard yet.`}
              </p>
            )}
          </section>
        </aside>
      </div>
    </>
  )
}

function ScorecardForm({ interview }: { interview: InterviewDetail }) {
  const queryClient = useQueryClient()
  const notices = useNotices()
  const baseId = useId()
  const [ratings, setRatings] = useState<Record<string, Rating | null>>(() => Object.fromEntries(interview.kit.map(question => [question.id, null])))
  const [notes, setNotes] = useState<Record<string, string>>({})
  const [recommendation, setRecommendation] = useState<Recommendation | null>(null)
  const [summary, setSummary] = useState('')
  const [touched, setTouched] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)

  const submit = useMutation({
    mutationFn: (body: ScorecardInput) => interviewsApi.submitScorecard(interview.id, body),
    onSuccess: () => {
      setConfirmOpen(false)
      notices.success('Scorecard submitted. Thank you.')
      void queryClient.invalidateQueries({ queryKey: qk.interviews })
      void queryClient.invalidateQueries({ queryKey: ['applicants', interview.jobId] })
    },
  })

  const missing = interview.kit.some(question => ratings[question.id] == null) || recommendation == null

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setTouched(true)
    if (missing) {
      window.setTimeout(() => document.querySelector<HTMLElement>('.scorecard-form .invalid input')?.focus(), 0)
      return
    }
    setConfirmOpen(true)
  }

  function send() {
    if (recommendation == null) return
    submit.mutate({
      ratings: interview.kit.map(question => ({
        questionId: question.id,
        requirementId: question.requirementId,
        rating: ratings[question.id] as Rating,
        notes: notes[question.id]?.trim() || null,
      })),
      recommendation,
      summary: summary.trim() || null,
    })
  }

  return (
    <section className="detail-card section-card" aria-labelledby="scorecard-form-heading">
      <h2 id="scorecard-form-heading">Questions and scorecard</h2>
      <p className="section-intro">Ask each question, use the follow-ups as needed, and rate the answer against the anchors (1 weak … 4 strong). Notes are optional but help the team.</p>
      <form className="scorecard-form" onSubmit={onSubmit} noValidate>
        <ol className="question-list">
          {interview.kit.map((question, index) => (
            <li key={question.id} className="question-card">
              <p className="question-text"><span className="question-number">Q{index + 1}.</span> {question.question}</p>
              {question.followUps.length > 0 && (
                <details className="follow-up-details">
                  <summary>Follow-ups ({question.followUps.length})</summary>
                  <ul className="follow-ups">{question.followUps.map((followUp, i) => <li key={i}>{followUp}</li>)}</ul>
                </details>
              )}
              <RatingScale
                legend={`Rating for question ${index + 1}`}
                value={ratings[question.id] ?? null}
                onChange={value => setRatings(current => ({ ...current, [question.id]: value }))}
                anchors={question.anchors}
                required
                invalid={touched && ratings[question.id] == null}
              />
              <label className="req-field" htmlFor={`${baseId}-${question.id}-notes`}>
                <span>Notes</span>
                <textarea id={`${baseId}-${question.id}-notes`} rows={2} maxLength={2000} value={notes[question.id] ?? ''} onChange={event => setNotes(current => ({ ...current, [question.id]: event.target.value }))} />
              </label>
            </li>
          ))}
        </ol>

        <fieldset className={`recommendation-picker${touched && recommendation == null ? ' invalid' : ''}`}>
          <legend>Overall recommendation <span className="required-mark" aria-hidden="true">*</span></legend>
          <div className="recommendation-options">
            {RECOMMENDATIONS.map(value => (
              <label key={value} className={`recommendation-option rec-${value.toLowerCase()}${recommendation === value ? ' selected' : ''}`}>
                <input type="radio" name={`${baseId}-recommendation`} value={value} checked={recommendation === value} onChange={() => setRecommendation(value)} />
                {RECOMMENDATION_TEXT[value]}
              </label>
            ))}
          </div>
          {touched && recommendation == null && <small className="field-error">Choose a recommendation.</small>}
        </fieldset>

        <label className="req-field" htmlFor={`${baseId}-summary`}>
          <span>Summary</span>
          <textarea id={`${baseId}-summary`} rows={4} maxLength={4000} value={summary} onChange={event => setSummary(event.target.value)} placeholder="Strengths, concerns and anything the next interviewer should probe." />
        </label>

        <FormError error={submit.error} />
        <div className="editor-actions">
          <span className="item-meta">{touched && missing ? 'Rate every question and choose a recommendation.' : 'Scorecards cannot be edited after submission.'}</span>
          <button type="submit" className="primary-button workspace-primary" disabled={submit.isPending}>{submit.isPending ? 'Submitting…' : 'Submit scorecard'}</button>
        </div>
      </form>

      <ConfirmDialog
        open={confirmOpen}
        title="Submit your scorecard?"
        message={`Recommendation: ${recommendation ? RECOMMENDATION_TEXT[recommendation] : '—'}. You cannot change it afterwards. Ratings of 3 or 4 on requirement-linked questions count as interview-verified evidence.`}
        confirmLabel="Submit scorecard"
        busy={submit.isPending}
        error={submit.error}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={send}
      />
    </section>
  )
}
