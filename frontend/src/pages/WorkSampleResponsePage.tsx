import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { workSamplesApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { CandidateWorkSample } from '../api/types'
import { isHttpUrl } from '../lib/url'
import { ConfirmDialog } from '../components/Dialog'
import { EmptyState, ErrorBlock, FormError, LoadingBlock } from '../components/Feedback'
import { Field } from '../components/Form'
import { useNotify } from '../components/Notices'
import { PageHeader } from '../components/PageHeader'
import { focusPageHeading } from '../lib/focus'
import { saveBlob, safeFilename } from '../lib/download'
import { WORK_SAMPLE_ACCEPT, WORK_SAMPLE_MAX_FILES, validateWorkSampleFiles } from '../lib/files'
import { describeDuration, formatDuration, formatFileSize, formatLongDateTime, formatTime } from '../lib/format'
import { useBeforeUnload, useNow } from '../lib/hooks'
import { workSampleStatusLabel } from '../lib/labels'

/** Plan 5.6: submissions are accepted until startedAt + time limit + 5 minutes. */
const GRACE_SECONDS = 5 * 60
const RESPONSE_MAX = 20000
const URL_MAX = 500
const ANNOUNCE_AT_SECONDS = [600, 300, 60]

function deadlineOf(sample: CandidateWorkSample): number | null {
  if (sample.deadlineAt) return new Date(sample.deadlineAt).getTime()
  if (sample.startedAt) return new Date(sample.startedAt).getTime() + sample.timeLimitMinutes * 60_000
  return null
}

/**
 * The server refuses a start after the start-by time and a submission after the time limit + grace with 400, and
 * marks the assignment EXPIRED (that change is committed). Refetch so the page shows the server's current state.
 */
function isWindowClosedError(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 400 || error.status === 409)
}

export function WorkSampleResponsePage() {
  const { assignmentId } = useParams()
  const id = Number(assignmentId)
  const samples = useQuery({ queryKey: queryKeys.workSamples, queryFn: workSamplesApi.list })
  const sample = samples.data?.find(item => item.assignmentId === id)
  // Text the candidate wrote but could not submit because the window closed; shown read-only so it can be copied.
  const [unsent, setUnsent] = useState<string | null>(null)

  const backLink = (
    <Link className="back-link" to="/work-samples">
      ← All work samples
    </Link>
  )

  if (samples.isPending) return <LoadingBlock label="Loading work sample…" />
  if (samples.isError) return <ErrorBlock error={samples.error} onRetry={() => void samples.refetch()} />
  if (!sample) {
    return (
      <>
        {backLink}
        <PageHeader title="Work sample not found" />
        <EmptyState icon="✎" title="This work sample is not available">It may have been cancelled, or the link is wrong.</EmptyState>
      </>
    )
  }

  return (
    <>
      {backLink}
      <PageHeader
        eyebrow={sample.jobTitle.toUpperCase()}
        title={sample.title}
        documentTitle={`Work sample: ${sample.title}`}
        description={`${sample.timeLimitMinutes} minutes · ${workSampleStatusLabel(sample.status)}`}
      />
      {sample.status === 'ASSIGNED' && <StartPanel sample={sample} />}
      {sample.status === 'STARTED' && <ResponsePanel sample={sample} onWindowClosed={setUnsent} />}
      {(sample.status === 'SUBMITTED' || sample.status === 'SCORED') && (
        <div className="panel">
          <p className="alert success">
            Submitted {sample.submittedAt ? formatLongDateTime(sample.submittedAt) : ''}. The employer reviews every response
            against the same scoring guide.
          </p>
          <SubmittedFiles sample={sample} />
        </div>
      )}
      {sample.status === 'EXPIRED' && <ExpiredPanel sample={sample} unsent={unsent} />}
      {sample.status === 'CANCELLED' && (
        <div className="panel">
          <p className="alert info">
            The employer cancelled this work sample{sample.cancelledAt ? ` on ${formatLongDateTime(sample.cancelledAt)}` : ''}. Nothing more is
            needed from you.
          </p>
          {sample.cancelReason && <p className="muted">Reason given: {sample.cancelReason}</p>}
        </div>
      )}
    </>
  )
}

/** Files attached to a submitted response, downloadable by the candidate (raw bytes, server file name). */
export function SubmittedFiles({ sample }: { sample: CandidateWorkSample }) {
  const download = useMutation({
    mutationFn: (fileId: number) => workSamplesApi.downloadFile(sample.assignmentId, fileId),
    onSuccess: (file, fileId) => {
      const original = sample.files.find(item => item.id === fileId)?.originalName ?? 'attachment'
      saveBlob(file.blob, safeFilename(file.filename ?? original, original))
    },
  })
  if (sample.files.length === 0) return null
  return (
    <div>
      <h3 className="subheading">Attached files</h3>
      <ul className="plain-list compact-list">
        {sample.files.map(file => (
          <li key={file.id} className="file-row">
            <span className="break-anywhere">
              {file.originalName} <span className="muted">· {formatFileSize(file.sizeBytes)}</span>
            </span>
            <button
              type="button"
              className="quiet-button"
              onClick={() => download.mutate(file.id)}
              disabled={download.isPending && download.variables === file.id}
              aria-label={`Download ${file.originalName}`}
            >
              {download.isPending && download.variables === file.id ? 'Downloading…' : 'Download'}
            </button>
          </li>
        ))}
      </ul>
      <FormError error={download.error} />
    </div>
  )
}

function ExpiredPanel({ sample, unsent }: { sample: CandidateWorkSample; unsent: string | null }) {
  const unsentId = useId()
  return (
    <div className="panel">
      <p className="alert warning" role="alert">
        {sample.startedAt
          ? 'The time for this work sample ran out (time limit plus 5 minutes), so no more responses are accepted.'
          : 'The start-by time for this work sample has passed, so it can no longer be started.'}{' '}
        Contact the employer if something went wrong.
      </p>
      {unsent && (
        <div className="field">
          <label htmlFor={unsentId}>Your unsent response (copy it if you want to keep it)</label>
          <textarea id={unsentId} rows={10} readOnly value={unsent} />
        </div>
      )}
    </div>
  )
}

function StartPanel({ sample }: { sample: CandidateWorkSample }) {
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const pastDue = sample.dueAt ? new Date(sample.dueAt).getTime() < Date.now() : false

  const start = useMutation({
    mutationFn: () => workSamplesApi.start(sample.assignmentId),
    onError: error => {
      // Past the start-by time the server refuses and the sweep marks the assignment EXPIRED: show the current state.
      if (isWindowClosedError(error)) void queryClient.invalidateQueries({ queryKey: queryKeys.workSamples })
    },
    onSuccess: started => {
      setConfirming(false)
      queryClient.setQueryData<CandidateWorkSample[]>(queryKeys.workSamples, current =>
        current?.map(item => (item.assignmentId === started.assignmentId ? started : item)),
      )
      void queryClient.invalidateQueries({ queryKey: queryKeys.workSamples })
      // The start panel is replaced by the timer and instructions; move focus to the instructions.
      window.setTimeout(() => document.getElementById('instructions-title')?.focus(), 0)
    },
  })

  return (
    <section className="panel" aria-labelledby="before-start">
      <h2 id="before-start" className="panel-title">
        Before you start
      </h2>
      <ul className="rules">
        <li>The instructions appear only after you press Start.</li>
        <li>
          You then have <strong>{sample.timeLimitMinutes} minutes</strong>. The timer cannot be paused.
        </li>
        <li>Responses sent more than 5 minutes after the time limit are not accepted.</li>
        <li>Keep this page open while you work. Write your answer here, and add a link if the task asks for a file.</li>
        {sample.dueAt && <li>Start by {formatLongDateTime(sample.dueAt)}.</li>}
      </ul>
      {pastDue && <p className="alert warning">The start-by date has passed. The employer may no longer accept a response.</p>}
      <button type="button" className="primary" onClick={() => setConfirming(true)}>
        Start the work sample
      </button>
      <ConfirmDialog
        open={confirming}
        title="Start now?"
        confirmLabel={`Start ${sample.timeLimitMinutes}-minute timer`}
        tone="primary"
        busy={start.isPending}
        onCancel={() => {
          setConfirming(false)
          start.reset()
        }}
        onConfirm={() => start.mutate()}
        error={<FormError error={start.error} />}
      >
        <p>The timer starts as soon as you confirm and runs even if you close this page.</p>
      </ConfirmDialog>
    </section>
  )
}

function ResponsePanel({ sample, onWindowClosed }: { sample: CandidateWorkSample; onWindowClosed: (unsentText: string | null) => void }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const now = useNow(1000)
  const deadline = deadlineOf(sample)
  const remaining = deadline === null ? null : Math.round((deadline - now) / 1000)
  const [responseText, setResponseText] = useState('')
  const [responseUrl, setResponseUrl] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [confirming, setConfirming] = useState(false)
  const [announcement, setAnnouncement] = useState('')
  const lastThreshold = useRef<number | null>(null)

  useBeforeUnload(responseText.trim().length > 0)

  // Announce only at a few milestones; a per-second live region would be unusable with a screen reader.
  // "Time is up" and "window closed" are announced by their role="alert" messages below.
  useEffect(() => {
    if (remaining === null) return
    const passed = ANNOUNCE_AT_SECONDS.filter(threshold => remaining <= threshold)
    if (passed.length === 0) return
    const crossed = Math.min(...passed)
    if (lastThreshold.current !== null && crossed >= lastThreshold.current) return
    const isFirst = lastThreshold.current === null
    lastThreshold.current = crossed
    if (!isFirst) setAnnouncement(`${describeDuration(crossed)} left.`)
  }, [remaining])

  const submit = useMutation({
    mutationFn: () =>
      workSamplesApi.submit(sample.assignmentId, { responseText: responseText.trim(), responseUrl: responseUrl.trim() || null, files }),
    onSuccess: submitted => {
      setConfirming(false)
      setResponseText('')
      setFiles([])
      notify('success', 'Your response was submitted.')
      queryClient.setQueryData<CandidateWorkSample[]>(queryKeys.workSamples, current =>
        current?.map(item => (item.assignmentId === submitted.assignmentId ? submitted : item)),
      )
      void queryClient.invalidateQueries({ queryKey: queryKeys.workSamples })
      void queryClient.invalidateQueries({ queryKey: ['application'] })
      focusPageHeading()
    },
    onError: error => {
      if (!isWindowClosedError(error)) return
      // WP-BE-C: a late submission is refused (400) and the assignment is now EXPIRED (also 400 when the application
      // is no longer active). Keep the text for copying in case the page switches state, and refetch the server state.
      onWindowClosed(responseText.trim() || null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.workSamples })
      void queryClient.invalidateQueries({ queryKey: ['application'] })
      if (remaining !== null && remaining <= 0) {
        setConfirming(false)
        notify('error', 'Your response was not accepted: the submission window has closed and this work sample has expired.')
      }
      // Otherwise the dialog shows the server's reason.
    },
  })

  // Once the grace period is over, check the server state once (the expiry sweep runs every minute).
  const closedNow = deadline !== null && remaining !== null && remaining <= -GRACE_SECONDS
  const checkedClosed = useRef(false)
  useEffect(() => {
    if (!closedNow || checkedClosed.current) return
    checkedClosed.current = true
    onWindowClosed(responseText.trim() || null)
    void queryClient.invalidateQueries({ queryKey: queryKeys.workSamples })
    // responseText is read at the moment the window closes on purpose.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [closedNow, onWindowClosed, queryClient])

  function review(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (!responseText.trim() && files.length === 0) next.responseText = 'Write your response or attach a file before submitting.'
    const fileProblems = validateWorkSampleFiles(files)
    if (fileProblems.length > 0) next.files = fileProblems.join(' ')
    if (responseUrl.trim() && !isHttpUrl(responseUrl.trim())) next.responseUrl = 'Enter a full link starting with https:// or http://.'
    setErrors(next)
    if (Object.keys(next).length === 0) setConfirming(true)
  }

  const timeUp = remaining !== null && remaining <= 0
  const closed = remaining !== null && remaining <= -GRACE_SECONDS
  const low = remaining !== null && remaining > 0 && remaining <= 300

  return (
    <>
      <div className={`timer-bar${low ? ' low' : ''}${timeUp ? ' up' : ''}`}>
        <div>
          <span className="eyebrow">TIME LEFT</span>
          <span className="timer" role="timer" aria-label={remaining === null ? 'Time left unknown' : `${describeDuration(Math.max(0, remaining))} left`}>
            {remaining === null ? '—' : formatDuration(Math.max(0, remaining))}
          </span>
        </div>
        {deadline !== null && <span className="muted">Ends at {formatTime(new Date(deadline))}</span>}
        <span className="visually-hidden" aria-live="assertive">
          {announcement}
        </span>
      </div>
      {timeUp && !closed && (
        <p className="alert warning" role="alert">
          Time is up. Submit now: responses are accepted for up to 5 minutes after the time limit.
        </p>
      )}
      {closed && (
        <p className="alert error" role="alert">
          The submission window has closed. A response sent now will be refused and the work sample marked as expired.
        </p>
      )}

      <section className="panel" aria-labelledby="instructions-title">
        <h2 id="instructions-title" className="panel-title" tabIndex={-1}>
          Instructions
        </h2>
        {sample.instructions ? (
          <div className="prose">{sample.instructions}</div>
        ) : (
          <p className="muted">The instructions could not be loaded. Refresh the page; the timer keeps running.</p>
        )}
      </section>

      <section className="panel" aria-labelledby="response-title">
        <h2 id="response-title" className="panel-title">
          Your response
        </h2>
        <form className="stack-form" onSubmit={review} noValidate>
          <Field
            label="Response"
            required={files.length === 0}
            error={errors.responseText}
            hint={`${responseText.length.toLocaleString()} / ${RESPONSE_MAX.toLocaleString()} characters. Leaving or reloading the page loses unsaved text.`}
          >
            {control => (
              <textarea {...control} rows={14} maxLength={RESPONSE_MAX} value={responseText} onChange={event => setResponseText(event.target.value)} />
            )}
          </Field>
          <Field label="Link to your work" optional error={errors.responseUrl} hint="For example a shared document, spreadsheet or repository.">
            {control => (
              <input
                {...control}
                type="url"
                inputMode="url"
                maxLength={URL_MAX}
                placeholder="https://"
                value={responseUrl}
                onChange={event => setResponseUrl(event.target.value)}
              />
            )}
          </Field>
          <Field
            label="Attach files"
            optional
            error={errors.files}
            hint={`Up to ${WORK_SAMPLE_MAX_FILES} files: PDF, ZIP, DOCX, PPTX, PNG or JPG, at most 20 MB each.`}
          >
            {control => (
              <input
                {...control}
                ref={fileInputRef}
                type="file"
                multiple
                accept={WORK_SAMPLE_ACCEPT}
                disabled={files.length >= WORK_SAMPLE_MAX_FILES}
                onChange={event => {
                  const chosen = Array.from(event.target.files ?? [])
                  const next = [...files, ...chosen].slice(0, WORK_SAMPLE_MAX_FILES + 2)
                  setFiles(next)
                  const problems = validateWorkSampleFiles(next)
                  setErrors(current => ({ ...current, files: problems.join(' ') }))
                  if (fileInputRef.current) fileInputRef.current.value = ''
                }}
              />
            )}
          </Field>
          {files.length > 0 && (
            <ul className="plain-list compact-list" aria-label="Files to attach">
              {files.map((file, index) => (
                <li key={`${file.name}-${index}`} className="file-row">
                  <span className="break-anywhere">
                    {file.name} <span className="muted">· {formatFileSize(file.size)}</span>
                  </span>
                  <button
                    type="button"
                    className="text-button"
                    aria-label={`Remove ${file.name}`}
                    onClick={() => {
                      const next = files.filter((_, position) => position !== index)
                      setFiles(next)
                      setErrors(current => ({ ...current, files: validateWorkSampleFiles(next).join(' ') }))
                    }}
                  >
                    Remove
                  </button>
                </li>
              ))}
            </ul>
          )}
          <div className="form-actions">
            <span className="muted">You cannot change your response after submitting.</span>
            <button type="submit" className="primary" disabled={submit.isPending}>
              Submit response
            </button>
          </div>
        </form>
      </section>

      <ConfirmDialog
        open={confirming}
        title="Submit your response?"
        confirmLabel="Submit response"
        tone="primary"
        busy={submit.isPending}
        onCancel={() => {
          setConfirming(false)
          submit.reset()
        }}
        onConfirm={() => submit.mutate()}
        error={<FormError error={submit.error} />}
      >
        <p>
          You cannot edit it afterwards.
          {files.length > 0 ? ` ${files.length} file${files.length === 1 ? '' : 's'} will be attached.` : ''}
        </p>
      </ConfirmDialog>
    </>
  )
}
