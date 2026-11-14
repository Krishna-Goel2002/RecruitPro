import { FormEvent, useId, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { workSamplesApi } from '../../api/assessments'
import { qk } from '../../api/queryKeys'
import type { ApplicantDetail, WorkSample, WorkSampleAssignment } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { StatusBadge } from '../../components/Badges'
import { Dialog } from '../../components/Dialog'
import { Field } from '../../components/Field'
import { useNotices } from '../../components/Notices'
import { RatingScale } from '../../components/RatingScale'
import { FormError } from '../../components/States'
import { formatDateTime, fromLocalInput, safeHttpUrl } from '../../lib/format'

const ASSIGNABLE = ['SHORTLISTED', 'INTERVIEWING']

/** Assign an active work sample, read submissions, score them against the rubric (1–4 per criterion + notes). */
export function WorkSampleSection({ jobId, detail }: { jobId: number; detail: ApplicantDetail }) {
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const notices = useNotices()
  const samples = useQuery({ queryKey: qk.workSamples(jobId), queryFn: ({ signal }) => workSamplesApi.list(jobId, signal) })
  const active = (samples.data ?? []).filter(sample => sample.status === 'ACTIVE')
  const assignedIds = new Set(detail.workSamples.map(assignment => assignment.workSampleId))
  const available = active.filter(sample => !assignedIds.has(sample.id))
  const [assignOpen, setAssignOpen] = useState(false)
  const [scoring, setScoring] = useState<WorkSampleAssignment | null>(null)
  const [viewing, setViewing] = useState<number | null>(null)

  const canAssign = can('workSamples.manage') && ASSIGNABLE.includes(detail.applicationStatus)

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: qk.applicant(jobId, detail.applicationId) })
    void queryClient.invalidateQueries({ queryKey: ['workSamples', jobId, 'assignments'] })
    void queryClient.invalidateQueries({ queryKey: qk.applicants(jobId) })
  }

  return (
    <section className="detail-card section-card" aria-labelledby="ws-section-heading">
      <div className="section-head">
        <div>
          <h2 id="ws-section-heading">Work samples</h2>
          <p>A criterion linked to a requirement and scored 3 or 4 marks that requirement as demonstrated.</p>
        </div>
        {canAssign && (
          <button type="button" className="outline-button" onClick={() => setAssignOpen(true)} disabled={samples.isPending || available.length === 0} title={available.length === 0 ? 'No active work sample left to assign. Activate one on the job’s Work samples tab.' : undefined}>
            Assign work sample
          </button>
        )}
      </div>
      {can('workSamples.manage') && !ASSIGNABLE.includes(detail.applicationStatus) && (
        <p className="muted-copy">Work samples can be assigned once the application is shortlisted.</p>
      )}

      {detail.workSamples.length === 0 ? <p className="muted-copy">No work sample assigned.</p> : (
        <ul className="card-list">
          {detail.workSamples.map(assignment => {
            const url = safeHttpUrl(assignment.responseUrl)
            const open = viewing === assignment.id
            return (
              <li key={assignment.id} className="item-card">
                <div className="item-card-head">
                  <div>
                    <h3>{assignment.workSampleTitle}</h3>
                    <p className="item-meta">
                      Assigned {formatDateTime(assignment.assignedAt)}
                      {assignment.dueAt && assignment.status === 'ASSIGNED' && ` · start by ${formatDateTime(assignment.dueAt)}`}
                      {assignment.startedAt && ` · started ${formatDateTime(assignment.startedAt)}`}
                      {assignment.submittedAt && ` · submitted ${formatDateTime(assignment.submittedAt)}`}
                    </p>
                  </div>
                  <StatusBadge status={assignment.status} />
                </div>
                {assignment.totalScore != null && (
                  <p className="score-line">Score {assignment.totalScore.toFixed(2)} / 4{assignment.scoredByName ? ` · scored by ${assignment.scoredByName}` : ''}{assignment.scoredAt ? ` on ${formatDateTime(assignment.scoredAt)}` : ''}</p>
                )}
                <div className="item-actions">
                  {(assignment.responseText || url) && (
                    <button type="button" className="outline-button" aria-expanded={open} onClick={() => setViewing(open ? null : assignment.id)}>{open ? 'Hide submission' : 'View submission'}</button>
                  )}
                  {assignment.status === 'SUBMITTED' && can('workSamples.score') && (
                    <button type="button" className="primary-button workspace-primary" onClick={() => setScoring(assignment)}>Score with rubric</button>
                  )}
                </div>
                {open && (
                  <div className="item-details">
                    {assignment.responseText && <p className="description-text submission-text">{assignment.responseText}</p>}
                    {url && <p><a href={url} target="_blank" rel="noopener noreferrer nofollow">Submitted link ↗</a></p>}
                  </div>
                )}
                {assignment.scores.length > 0 && (
                  <ul className="score-list">
                    {assignment.scores.map((score, index) => (
                      <li key={`${score.criterion}-${index}`}><strong>{score.score}</strong> {score.criterion}{score.note ? ` — ${score.note}` : ''}</li>
                    ))}
                  </ul>
                )}
              </li>
            )
          })}
        </ul>
      )}

      <AssignDialog
        open={assignOpen}
        jobId={jobId}
        applicationId={detail.applicationId}
        samples={available}
        onClose={() => setAssignOpen(false)}
        onAssigned={() => { setAssignOpen(false); notices.success('Work sample assigned. The candidate is notified.'); refresh() }}
      />
      {scoring && (
        <ScoreDialog
          assignment={scoring}
          sample={(samples.data ?? []).find(sample => sample.id === scoring.workSampleId) ?? null}
          onClose={() => setScoring(null)}
          onScored={() => { setScoring(null); notices.success('Scores saved. Requirement evidence is updated by re-matching.'); refresh() }}
        />
      )}
    </section>
  )
}

function AssignDialog({ open, jobId, applicationId, samples, onClose, onAssigned }: {
  open: boolean
  jobId: number
  applicationId: number
  samples: WorkSample[]
  onClose: () => void
  onAssigned: () => void
}) {
  const [sampleId, setSampleId] = useState('')
  const [due, setDue] = useState('')
  const [error, setError] = useState<string | null>(null)
  const assign = useMutation({
    mutationFn: () => workSamplesApi.assign(jobId, applicationId, { workSampleId: Number(sampleId), dueAt: fromLocalInput(due) ?? undefined }),
    onSuccess: () => { setSampleId(''); setDue(''); onAssigned() },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (!sampleId) return setError('Choose a work sample.')
    if (due) {
      const iso = fromLocalInput(due)
      if (!iso || new Date(iso).getTime() <= Date.now()) return setError('The start-by time must be in the future.')
    }
    assign.mutate()
  }

  return (
    <Dialog open={open} title="Assign a work sample" description="The candidate sees the instructions only when they press Start; the time limit runs from then." onClose={onClose} busy={assign.isPending}>
      <form className="dialog-form" onSubmit={submit} noValidate>
        <Field label="Work sample" required error={error && !sampleId ? error : null}>
          <select value={sampleId} onChange={event => setSampleId(event.target.value)} data-autofocus>
            <option value="">Choose an active work sample</option>
            {samples.map(sample => <option key={sample.id} value={sample.id}>{sample.title} ({sample.timeLimitMinutes} min)</option>)}
          </select>
        </Field>
        <Field label="Start by" hint="Optional; your local time. The latest time the candidate may start; once started, the time limit applies." error={error && sampleId ? error : null}>
          <input type="datetime-local" value={due} onChange={event => setDue(event.target.value)} />
        </Field>
        <FormError error={assign.error} />
        <div className="dialog-actions">
          <button type="button" className="outline-button" onClick={onClose} disabled={assign.isPending}>Cancel</button>
          <button type="submit" className="primary-button workspace-primary" disabled={assign.isPending}>{assign.isPending ? 'Assigning…' : 'Assign'}</button>
        </div>
      </form>
    </Dialog>
  )
}

function ScoreDialog({ assignment, sample, onClose, onScored }: {
  assignment: WorkSampleAssignment
  sample: WorkSample | null
  onClose: () => void
  onScored: () => void
}) {
  const baseId = useId()
  const rubric = sample?.rubric ?? []
  const [scores, setScores] = useState<(number | null)[]>(() => rubric.map(() => null))
  const [notes, setNotes] = useState<string[]>(() => rubric.map(() => ''))
  const [touched, setTouched] = useState(false)
  const score = useMutation({
    mutationFn: () => workSamplesApi.score(assignment.id, {
      scores: rubric.map((criterion, index) => ({
        requirementId: criterion.requirementId,
        criterion: criterion.criterion,
        score: scores[index] as number,
        note: notes[index].trim() || undefined,
      })),
    }),
    onSuccess: onScored,
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    setTouched(true)
    if (scores.some(value => value == null)) {
      window.setTimeout(() => document.querySelector<HTMLElement>('.dialog-panel .rating-scale.invalid input')?.focus(), 0)
      return
    }
    score.mutate()
  }

  return (
    <Dialog open title={`Score: ${assignment.workSampleTitle}`} description="Rate each criterion against its anchors. Read the submission first." onClose={onClose} busy={score.isPending} wide>
      {!sample ? (
        <p className="muted-copy">The rubric for this work sample could not be loaded. Close and try again.</p>
      ) : (
        <form className="dialog-form" onSubmit={submit} noValidate>
          {assignment.responseText && (
            <details className="submission-preview" open>
              <summary>Submission</summary>
              <p className="description-text submission-text">{assignment.responseText}</p>
            </details>
          )}
          {rubric.map((criterion, index) => (
            <div key={`${criterion.criterion}-${index}`} className="score-criterion">
              <RatingScale
                legend={criterion.criterion}
                value={scores[index]}
                onChange={value => setScores(current => current.map((item, i) => (i === index ? value : item)))}
                anchors={criterion.anchors}
                required
                invalid={touched && scores[index] == null}
              />
              <label className="req-field" htmlFor={`${baseId}-note-${index}`}>
                <span>Note (optional)</span>
                <textarea id={`${baseId}-note-${index}`} rows={2} maxLength={1000} value={notes[index]} onChange={event => setNotes(current => current.map((item, i) => (i === index ? event.target.value : item)))} />
              </label>
            </div>
          ))}
          <FormError error={score.error} />
          <div className="dialog-actions">
            <button type="button" className="outline-button" onClick={onClose} disabled={score.isPending}>Cancel</button>
            <button type="submit" className="primary-button workspace-primary" disabled={score.isPending}>{score.isPending ? 'Saving…' : 'Save scores'}</button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
