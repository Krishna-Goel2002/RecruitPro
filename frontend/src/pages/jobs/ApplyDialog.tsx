import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { jobsApi, resumesApi } from '../../api/endpoints'
import { queryKeys } from '../../api/queryKeys'
import type { Application, JobPublic, Resume } from '../../api/types'
import { useCurrentUser } from '../../auth/AuthProvider'
import { Dialog } from '../../components/Dialog'
import { ErrorBlock, FormError, LoadingBlock } from '../../components/Feedback'
import { formatDate, formatFileSize } from '../../lib/format'

const COVER_LETTER_MAX = 10000

type Step = 'form' | 'confirm' | 'done'

function newestFirst(resumes: Resume[]): Resume[] {
  return [...resumes].sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt))
}

/**
 * Apply flow (audit M5): choose a resume, optionally write a cover letter, review, then submit.
 */
export function ApplyDialog({ job, open, onClose }: { job: JobPublic; open: boolean; onClose: () => void }) {
  const queryClient = useQueryClient()
  const user = useCurrentUser()
  const [step, setStep] = useState<Step>('form')
  const [resumeId, setResumeId] = useState<number | null>(null)
  const [coverLetter, setCoverLetter] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [submitted, setSubmitted] = useState<Application | null>(null)
  const stepHeadingRef = useRef<HTMLHeadingElement>(null)
  const coverHintId = useId()

  const resumes = useQuery({ queryKey: queryKeys.resumes, queryFn: resumesApi.list, enabled: open })
  const sorted = resumes.data ? newestFirst(resumes.data) : []
  const selected = sorted.find(resume => resume.id === resumeId) ?? null

  const apply = useMutation({
    mutationFn: () => jobsApi.apply(job.id, { resumeId: resumeId ?? 0, coverLetter: coverLetter.trim() || null }),
    onSuccess: application => {
      setSubmitted(application)
      setStep('done')
      void queryClient.invalidateQueries({ queryKey: queryKeys.applicationsAll })
    },
  })

  // Default to the most recent resume once the list arrives.
  useEffect(() => {
    if (open && resumeId === null && sorted.length > 0) setResumeId(sorted[0].id)
  }, [open, resumeId, sorted])

  // Reset when the dialog closes.
  useEffect(() => {
    if (!open) {
      setStep('form')
      setCoverLetter('')
      setResumeId(null)
      setFormError(null)
      setSubmitted(null)
      apply.reset()
    }
    // Runs only when `open` changes; `apply.reset` is stable for the mutation's lifetime.
  }, [open])

  // Move focus to the heading of each new step so keyboard and screen-reader users follow along.
  useEffect(() => {
    if (open && step !== 'form') stepHeadingRef.current?.focus()
  }, [open, step])

  function goToConfirm(event: FormEvent) {
    event.preventDefault()
    if (!selected) {
      setFormError('Choose the resume to send with this application.')
      return
    }
    if (coverLetter.length > COVER_LETTER_MAX) {
      setFormError(`The cover letter can be up to ${COVER_LETTER_MAX.toLocaleString()} characters.`)
      return
    }
    setFormError(null)
    setStep('confirm')
  }

  const title = step === 'done' ? 'Application submitted' : `Apply: ${job.title}`

  return (
    <Dialog open={open} title={title} onClose={onClose} busy={apply.isPending} size="large">
      {step === 'form' && (
        <form className="stack-form" onSubmit={goToConfirm}>
          <p className="muted">
            {job.organizationName ? `${job.organizationName} · ` : ''}
            {job.location}
          </p>
          {resumes.isPending ? (
            <LoadingBlock label="Loading your resumes…" />
          ) : resumes.isError ? (
            <ErrorBlock error={resumes.error} onRetry={() => void resumes.refetch()} />
          ) : sorted.length === 0 ? (
            <div className="alert info">
              <span>
                You need a resume before you can apply. <Link to="/resumes">Upload a resume</Link>, then come back to this job.
              </span>
            </div>
          ) : (
            <fieldset className="choice-list">
              <legend>Resume to send</legend>
              {sorted.map(resume => (
                <label key={resume.id} className={`choice${resume.id === resumeId ? ' selected' : ''}`}>
                  <input
                    type="radio"
                    name="resume"
                    value={resume.id}
                    checked={resume.id === resumeId}
                    onChange={() => setResumeId(resume.id)}
                  />
                  <span>
                    <strong>{resume.originalFilename}</strong>
                    <small>
                      Uploaded {formatDate(resume.uploadedAt)} · {formatFileSize(resume.fileSize)}
                    </small>
                  </span>
                </label>
              ))}
            </fieldset>
          )}

          <div className="field">
            <label htmlFor={`${coverHintId}-input`}>
              Cover letter <span className="optional">(optional)</span>
            </label>
            <textarea
              id={`${coverHintId}-input`}
              aria-describedby={coverHintId}
              rows={7}
              maxLength={COVER_LETTER_MAX}
              value={coverLetter}
              onChange={event => setCoverLetter(event.target.value)}
              placeholder="Why this role, and what you would bring to it."
            />
            <p id={coverHintId} className="field-hint">
              Recruiters see your cover letter after your application is shortlisted, together with your name.{' '}
              {coverLetter.length.toLocaleString()} / {COVER_LETTER_MAX.toLocaleString()} characters.
            </p>
          </div>

          <FormError error={formError} />
          <div className="dialog-actions">
            <button type="button" className="quiet-button" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="primary" disabled={!selected}>
              Review application
            </button>
          </div>
        </form>
      )}

      {step === 'confirm' && selected && (
        <div className="stack-form">
          <h3 ref={stepHeadingRef} tabIndex={-1} className="subheading">
            Check before you submit
          </h3>
          <dl className="summary-list">
            <div>
              <dt>Role</dt>
              <dd>
                {job.title}
                {job.organizationName ? ` at ${job.organizationName}` : ''}
              </dd>
            </div>
            <div>
              <dt>Resume</dt>
              <dd>{selected.originalFilename}</dd>
            </div>
            <div>
              <dt>Cover letter</dt>
              <dd>{coverLetter.trim() ? `Included (${coverLetter.trim().length.toLocaleString()} characters)` : 'Not included'}</dd>
            </div>
            <div>
              <dt>Contact email</dt>
              <dd>{user.email}</dd>
            </div>
          </dl>
          <p className="muted">
            After submitting you can follow the application and withdraw it, but you cannot change the resume or cover
            letter.
          </p>
          <FormError error={apply.error} />
          <div className="dialog-actions">
            <button type="button" className="quiet-button" onClick={() => setStep('form')} disabled={apply.isPending}>
              Back
            </button>
            <button type="button" className="primary" onClick={() => apply.mutate()} disabled={apply.isPending}>
              {apply.isPending ? 'Submitting…' : 'Submit application'}
            </button>
          </div>
        </div>
      )}

      {step === 'done' && submitted && (
        <div className="stack-form">
          <h3 ref={stepHeadingRef} tabIndex={-1} className="subheading">
            Your application for {submitted.jobTitle} was sent.
          </h3>
          <p className="muted">We will notify you when its status changes. You can follow it under Applications.</p>
          <div className="dialog-actions">
            <button type="button" className="quiet-button" onClick={onClose}>
              Close
            </button>
            <Link className="primary button-link" to={`/applications/${submitted.id}`}>
              View application
            </Link>
          </div>
        </div>
      )}
    </Dialog>
  )
}
