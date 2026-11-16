import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, errorMessage } from '../../api/client'
import { jobsApi, resumesApi } from '../../api/endpoints'
import { queryKeys } from '../../api/queryKeys'
import type { Application, JobPublic, Resume } from '../../api/types'
import { useCurrentUser } from '../../auth/AuthProvider'
import { ErrorState, Facts, FormError } from '../../components/Feedback'
import { useNotify } from '../../components/Notices'
import {
  Alert, Button, Dialog, EvidenceLadder, Field, FileDropzone, RadioCard, RadioGroup, Skeleton, Stepper, Textarea, Timeline, buttonProps,
} from '../../design/components'
import { ArrowLeft, ArrowRight, Check, FileText, Icon } from '../../design/icons'
import { formatDate, formatFileSize, plural } from '../../design/lib/format'
import { useDraft } from '../../lib/drafts'
import { isCovered, type RequirementMatch } from '../../lib/match'
import { ledgerQuery, newestFirst, recentApplicationsQuery, resumesQuery } from '../../lib/queries'

const COVER_LETTER_MAX = 10000
const RESUME_MAX = 10 * 1024 * 1024
const RESUME_ACCEPT = '.pdf,.doc,.docx'
/** Resumes offered in the dialog; older ones stay on the Resume page. */
const RESUME_CHOICES = 4

type Step = 0 | 1 | 2

/**
 * Apply in one dialog (FLOWS-CANDIDATE §4): Resume (newest preselected; upload right here if there is none) →
 * Review (with the evidence that goes along) → Sent (what happens next). The cover letter autosaves per job.
 */
export function ApplyDialog({ job, matches, open, onClose }: { job: JobPublic; matches: RequirementMatch[]; open: boolean; onClose: () => void }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const user = useCurrentUser()
  const [step, setStep] = useState<Step>(0)
  const [resumeId, setResumeId] = useState<number | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [submitted, setSubmitted] = useState<Application | null>(null)
  const [duplicate, setDuplicate] = useState<number | null>(null)
  const stepRef = useRef<HTMLDivElement>(null)
  const cover = useDraft(open ? `draft-cover-letter-${job.id}` : null)

  const resumes = useQuery({ ...resumesQuery, enabled: open })
  const ledger = useQuery({ ...ledgerQuery, enabled: open })
  const sorted = resumes.data ? newestFirst(resumes.data) : []
  const selected = sorted.find(r => r.id === resumeId) ?? null
  const shared = (ledger.data ?? []).reduce((n, c) => n + c.verdicts.filter(v => v.candidateStatus === 'SHARED').length, 0)

  const upload = useMutation({
    mutationFn: (file: File) => resumesApi.upload(file),
    onSuccess: resume => {
      queryClient.setQueryData<Resume[]>(queryKeys.resumes, current => [resume, ...(current ?? [])])
      void queryClient.invalidateQueries({ queryKey: queryKeys.resumes })
      setResumeId(resume.id)
    },
  })

  const apply = useMutation({
    mutationFn: () => jobsApi.apply(job.id, { resumeId: resumeId ?? 0, coverLetter: cover.value.trim() || null }),
    onSuccess: application => {
      setSubmitted(application)
      setStep(2)
      cover.clear()
      void queryClient.invalidateQueries({ queryKey: queryKeys.applicationsAll })
    },
    onError: async error => {
      // Already applied: point to that application instead of showing an error.
      if (error instanceof ApiError && error.status === 409) {
        const list = await queryClient.fetchQuery(recentApplicationsQuery).catch(() => null)
        const found = list?.content.find(a => a.jobId === job.id)
        if (found) setDuplicate(found.id)
      }
    },
  })

  useEffect(() => {
    if (open && resumeId === null && sorted.length > 0) setResumeId(sorted[0].id)
  }, [open, resumeId, sorted])

  useEffect(() => {
    if (!open) {
      setStep(0)
      setResumeId(null)
      setFormError(null)
      setSubmitted(null)
      setDuplicate(null)
      apply.reset()
      upload.reset()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // Focus follows the step so keyboard and screen-reader users hear the new content. Going Back removes the button
  // that had focus, so step 1 (Resume) takes focus too, but not when the dialog first opens (initial focus is the
  // dialog's own).
  const previousStep = useRef<Step>(step)
  useEffect(() => {
    if (open && (step > 0 || previousStep.current > 0)) stepRef.current?.focus()
    previousStep.current = step
  }, [open, step])

  function toReview() {
    if (!selected) return setFormError('Choose the resume to send with this application.')
    if (cover.value.length > COVER_LETTER_MAX) return setFormError(`The cover letter can be up to ${COVER_LETTER_MAX.toLocaleString()} characters.`)
    setFormError(null)
    setStep(1)
  }

  const covered = matches.filter(isCovered).length
  const gaps = matches.filter(m => m.requirement.mandatory && !isCovered(m))

  const footer =
    step === 0 ? (
      <>
        <Button variant="ghost" onClick={onClose}>
          Cancel
        </Button>
        <Button variant="primary" trailingIcon={ArrowRight} onClick={toReview} disabled={sorted.length === 0}>
          Review application
        </Button>
      </>
    ) : step === 1 ? (
      <>
        <Button variant="ghost" icon={ArrowLeft} onClick={() => setStep(0)} disabled={apply.isPending}>
          Back
        </Button>
        <Button variant="primary" loading={apply.isPending} onClick={() => apply.mutate()} disabled={duplicate !== null}>
          Submit application
        </Button>
      </>
    ) : (
      <>
        <Button variant="ghost" onClick={onClose}>
          Close
        </Button>
        {submitted && (
          <Link {...buttonProps('primary')} to={`/applications/${submitted.id}`}>
            View application
          </Link>
        )}
      </>
    )

  return (
    <Dialog
      open={open}
      onClose={onClose}
      busy={apply.isPending || upload.isPending}
      size="md"
      title={step === 2 ? 'Application sent' : `Apply: ${job.title}`}
      description={step === 2 ? undefined : [job.organizationName, job.location].filter(Boolean).join(' · ')}
      footer={footer}
    >
      <Stepper steps={['Resume', 'Review', 'Sent']} current={step} label="Application steps" className="ap-stepper" />

      {step === 0 && (
        <div className="ap-step" ref={stepRef} tabIndex={-1} role="group" aria-label="Choose a resume">
          {resumes.isPending ? (
            <div aria-hidden="true">
              <Skeleton width="100%" height={56} />
            </div>
          ) : resumes.isError ? (
            <ErrorState error={resumes.error} onRetry={() => void resumes.refetch()} title="Your resumes could not be loaded" />
          ) : sorted.length === 0 ? (
            <div>
              <p className="ap-subhead">Add your resume</p>
              <p className="ap-org" style={{ margin: '4px 0 12px' }}>
                Employers need one to review you. It uploads straight away and stays in your account for later applications.
              </p>
              <FileDropzone
                prompt="Drop a PDF, DOC or DOCX here, or"
                hint="Up to 10 MB."
                accept={RESUME_ACCEPT}
                maxSize={RESUME_MAX}
                onFiles={files => upload.mutate(files[0])}
                files={upload.isPending && upload.variables ? [{ id: 'up', name: upload.variables.name, size: upload.variables.size, progress: 0.5 }] : []}
                error={upload.error ? errorMessage(upload.error) : undefined}
              />
            </div>
          ) : (
            <RadioGroup legend="Resume to send" value={resumeId === null ? undefined : String(resumeId)} onChange={v => setResumeId(Number(v))}>
              {sorted.slice(0, RESUME_CHOICES).map((resume, i) => (
                <RadioCard
                  key={resume.id}
                  value={String(resume.id)}
                  icon={FileText}
                  label={resume.originalFilename}
                  description={`${i === 0 ? 'Newest · ' : ''}Uploaded ${formatDate(resume.uploadedAt)} · ${formatFileSize(resume.fileSize)}`}
                />
              ))}
            </RadioGroup>
          )}
          {sorted.length > RESUME_CHOICES && (
            <p className="ap-org">
              Showing your {RESUME_CHOICES} newest of {sorted.length} resumes. Older files are on the <Link to="/resumes">Resume page</Link>.
            </p>
          )}
          {upload.isSuccess && <Alert tone="success">{upload.data.originalFilename} uploaded and selected.</Alert>}

          <Field
            label="Cover letter"
            optional
            hint={
              <>
                Recruiters see it after they shortlist you, together with your name.{' '}
                {cover.savedAt && <span className="ap-draft">Draft saved.</span>}
              </>
            }
          >
            <Textarea
              rows={5}
              maxRows={14}
              maxLength={COVER_LETTER_MAX}
              showCount
              value={cover.value}
              placeholder="Why this role, and what you would bring to it."
              onChange={e => cover.setValue(e.target.value)}
            />
          </Field>
          {cover.value.trim() && (
            <div>
              {/* The draft autosaves and comes back on every visit: this is the way to start over (Undo in the toast). */}
              <Button
                variant="link"
                size="sm"
                onClick={() => {
                  const previous = cover.value
                  cover.clear()
                  notify('info', 'Cover letter cleared', { body: 'The saved draft was removed too.', action: { label: 'Undo', onClick: () => cover.setValue(previous) } })
                }}
              >
                Clear cover letter
              </Button>
            </div>
          )}
          <FormError error={formError} />
        </div>
      )}

      {step === 1 && selected && (
        <div className="ap-step" ref={stepRef} tabIndex={-1} aria-label="Check before you submit">
          <Facts
            className="ap-summary"
            items={[
              { label: 'Role', value: `${job.title}${job.organizationName ? ` at ${job.organizationName}` : ''}` },
              { label: 'Resume', value: selected.originalFilename },
              { label: 'Cover letter', value: cover.value.trim() ? `Included (${plural(cover.value.trim().length, 'character')})` : 'Not included' },
              { label: 'Contact email', value: user.email },
            ]}
          />
          {matches.length > 0 && (
            <div>
              <p className="ap-subhead">Evidence that goes with it</p>
              <p className="ap-org" style={{ margin: '2px 0 10px' }}>
                {covered} of {matches.length} requirements found in your resume and claims{shared > 0 ? `, plus ${plural(shared, 'shared verification result')}` : ''}.
              </p>
              <ul className="ap-evidence">
                {matches.map(m => (
                  <li key={m.requirement.id}>
                    <span title={m.requirement.description}>{m.requirement.description}</span>
                    <EvidenceLadder tier={m.tier} audience="candidate" animate={false} />
                  </li>
                ))}
              </ul>
            </div>
          )}
          <Alert tone="neutral">
            Your name and cover letter stay hidden until the employer shortlists you. After submitting you can follow and withdraw the application, but not
            change the resume or cover letter.
          </Alert>
          {duplicate !== null ? (
            <Alert tone="info" title="You already applied to this role" action={<Link {...buttonProps('secondary', 'sm')} to={`/applications/${duplicate}`}>Open it</Link>}>
              Each role takes one application.
            </Alert>
          ) : (
            <FormError error={apply.error} />
          )}
        </div>
      )}

      {step === 2 && submitted && (
        <div className="ap-sent ap-step" ref={stepRef} tabIndex={-1}>
          <span className="ap-sent-mark" aria-hidden="true">
            <Icon icon={Check} size={20} strokeWidth={2.25} />
          </span>
          <div>
            <p className="ap-subhead">Your application for {submitted.jobTitle} was sent.</p>
            <p className="ap-org">We notify you whenever its status changes.</p>
          </div>
          <Timeline
            label="What happens next"
            items={[
              { id: 1, title: 'Blind review', meta: 'The employer reads your evidence without your name.', state: 'current' },
              { id: 2, title: 'Shortlist', meta: 'If they move forward, your name and cover letter become visible.', state: 'future' },
              { id: 3, title: 'Work sample or interview', meta: 'Tasks appear in your overview with clear deadlines.', state: 'future' },
            ]}
          />
          {gaps.length > 0 && (
            <Alert
              tone="info"
              title="Strengthen this application"
              action={
                <Link {...buttonProps('secondary', 'sm')} to="/verification?add=source&step=choose">
                  Add a source
                </Link>
              }
            >
              {plural(gaps.length, 'must-have')} {gaps.length === 1 ? 'is' : 'are'} not evidenced yet ({gaps.map(g => g.requirement.description).join(', ')}).
            </Alert>
          )}
        </div>
      )}
    </Dialog>
  )
}
