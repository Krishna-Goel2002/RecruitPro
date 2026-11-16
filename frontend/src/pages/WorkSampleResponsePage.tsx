import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { workSamplesApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { CandidateWorkSample } from '../api/types'
import { ConfirmDialog } from '../components/Dialog'
import { ErrorState, Facts, FormError } from '../components/Feedback'
import { focusFirstInvalid } from '../components/Form'
import { useNotify } from '../components/Notices'
import {
  Alert, Button, CodeField, EmptyState, Field, FileDropzone, IconButton, Input, LoadingRegion, PageHeader, Skeleton, SkeletonText, Textarea, buttonProps,
} from '../design/components'
import { ArrowRight, Check, CircleCheck, Clock, Download, Icon, ListChecks } from '../design/icons'
import { formatDate, formatFileSize } from '../design/lib/format'
import { useDraft } from '../lib/drafts'
import { saveBlob, safeFilename } from '../lib/download'
import { WORK_SAMPLE_ACCEPT, WORK_SAMPLE_MAX_BYTES, WORK_SAMPLE_MAX_FILES, validateWorkSampleFiles } from '../lib/files'
import { describeDuration, formatDuration, formatLongDateTime, formatTime } from '../lib/format'
import { useBeforeUnload, useNow } from '../lib/hooks'
import { readStored } from '../lib/storage'
import { isHttpUrl } from '../lib/url'
import { workSamplesQuery } from '../lib/queries'
import { WorkSampleBadge } from './WorkSamplesPage'
import './common.css'
import './tasks/tasks.css'

/** Plan 5.6: submissions are accepted until startedAt + time limit + 5 minutes. */
const GRACE_SECONDS = 5 * 60
const RESPONSE_MAX = 20000
const URL_MAX = 500
const ANNOUNCE_AT_SECONDS = [600, 300, 60]

const draftKey = (id: number) => `draft-work-sample-${id}`

function deadlineOf(sample: CandidateWorkSample): number | null {
  if (sample.deadlineAt) return new Date(sample.deadlineAt).getTime()
  if (sample.startedAt) return new Date(sample.startedAt).getTime() + sample.timeLimitMinutes * 60_000
  return null
}

/** A refused start/submit (400/409) means the window closed and the server marked the task EXPIRED: refetch. */
function isWindowClosedError(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 400 || error.status === 409)
}

/**
 * One work sample (REDESIGN_PLAN A9): rules as a checklist and an honest Start button; while it runs, a sticky timer
 * (warning from 5 minutes, pulse in the last minute), the instructions and a response that autosaves as a draft.
 */
export function WorkSampleResponsePage() {
  const { assignmentId } = useParams()
  const id = Number(assignmentId)
  const samples = useQuery(workSamplesQuery)
  const sample = samples.data?.find(item => item.assignmentId === id)
  const crumbs = [{ label: 'Work samples', to: '/work-samples' }]

  if (samples.isPending) {
    return (
      <LoadingRegion label="Loading work sample">
        <Skeleton width={160} height={10} />
        <Skeleton width={360} height={24} style={{ marginTop: 12 }} />
        <div style={{ marginTop: 32 }}>
          <SkeletonText lines={5} />
        </div>
      </LoadingRegion>
    )
  }
  if (samples.isError) return <ErrorState error={samples.error} onRetry={() => void samples.refetch()} title="This work sample could not be loaded" />
  if (!sample) {
    return (
      <>
        <PageHeader title="Work sample not found" breadcrumb={[...crumbs, { label: 'Not found' }]} />
        <EmptyState
          icon={ListChecks}
          title="This work sample is not available"
          description="It may have been cancelled, or the link is wrong."
          action={
            <Link {...buttonProps('secondary')} to="/work-samples">
              All work samples
            </Link>
          }
        />
      </>
    )
  }

  return (
    <div className="t-page">
      <PageHeader
        breadcrumb={[...crumbs, { label: sample.title }]}
        title={sample.title}
        documentTitle={`Work sample: ${sample.title}`}
        status={<WorkSampleBadge status={sample.status} />}
        meta={[sample.jobTitle, `${sample.timeLimitMinutes} minutes`]}
      />
      {sample.status === 'ASSIGNED' && <StartPanel sample={sample} />}
      {sample.status === 'STARTED' && <ResponsePanel sample={sample} />}
      {(sample.status === 'SUBMITTED' || sample.status === 'SCORED') && (
        <div className="t-block">
          <div className="t-done">
            <span className="t-done-icon">
              <Icon icon={CircleCheck} size={20} />
            </span>
            <div>
              <p className="t-h3">Submitted{sample.submittedAt ? ` ${formatLongDateTime(sample.submittedAt)}` : ''}</p>
              <p className="c-muted">The employer reviews every response against the same scoring guide. Nothing more is needed from you.</p>
            </div>
          </div>
          <SubmittedFiles sample={sample} />
        </div>
      )}
      {sample.status === 'EXPIRED' && <ExpiredPanel sample={sample} />}
      {sample.status === 'CANCELLED' && (
        <Alert tone="neutral" title="The employer cancelled this work sample">
          {sample.cancelledAt ? `On ${formatLongDateTime(sample.cancelledAt)}. ` : ''}Nothing more is needed from you.
          {sample.cancelReason ? ` Reason given: ${sample.cancelReason}` : ''}
        </Alert>
      )}
    </div>
  )
}

/** Files attached to a submitted response, downloadable by the candidate. */
function SubmittedFiles({ sample }: { sample: CandidateWorkSample }) {
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
      <p className="t-subhead">Attached files</p>
      <ul className="c-rows" data-closed="">
        {sample.files.map(file => (
          <li key={file.id} className="t-file">
            <span className="c-break">
              {file.originalName} <span className="c-meta">· {formatFileSize(file.sizeBytes)}</span>
            </span>
            <IconButton
              label={`Download ${file.originalName}`}
              icon={Download}
              size="sm"
              loading={download.isPending && download.variables === file.id}
              onClick={() => download.mutate(file.id)}
            />
          </li>
        ))}
      </ul>
      <FormError error={download.error} />
    </div>
  )
}

function ExpiredPanel({ sample }: { sample: CandidateWorkSample }) {
  const unsent = readStored(draftKey(sample.assignmentId))
  return (
    <div className="t-block">
      <Alert tone="warning" title={sample.startedAt ? 'The time ran out' : 'The start-by time has passed'}>
        {sample.startedAt
          ? 'The time limit plus 5 minutes has passed, so no more responses are accepted.'
          : 'This work sample can no longer be started.'}{' '}
        Contact the employer if something went wrong.
      </Alert>
      {unsent && (
        <div>
          <p className="t-subhead">Your unsent response</p>
          <p className="c-meta" style={{ marginBottom: 8 }}>
            Saved in this browser. Copy it if you want to keep it.
          </p>
          <CodeField value={unsent} label="Response text" />
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
      if (isWindowClosedError(error)) void queryClient.invalidateQueries({ queryKey: queryKeys.workSamples })
    },
    onSuccess: started => {
      setConfirming(false)
      queryClient.setQueryData<CandidateWorkSample[]>(queryKeys.workSamples, current =>
        current?.map(item => (item.assignmentId === started.assignmentId ? started : item)),
      )
      void queryClient.invalidateQueries({ queryKey: queryKeys.workSamples })
      window.setTimeout(() => document.getElementById('instructions-title')?.focus(), 0)
    },
  })

  const rules = [
    'The instructions appear only after you press Start.',
    `You then have ${sample.timeLimitMinutes} minutes. The timer cannot be paused and keeps running if you close the page.`,
    'You can attach up to 3 files (PDF, ZIP, DOCX, PPTX, PNG or JPG, 20 MB each), add a link, or both.',
    'Responses sent more than 5 minutes after the time limit are not accepted.',
    'Your answer is saved as a draft in this browser while you type.',
  ]

  return (
    <section className="t-block" aria-labelledby="before-start">
      <h2 id="before-start" className="t-h2">
        Before you start
      </h2>
      <ul className="t-rules">
        {rules.map(rule => (
          <li key={rule}>
            <Icon icon={Check} size={14} />
            <span>{rule}</span>
          </li>
        ))}
      </ul>
      {sample.dueAt && (
        <Facts
          items={[
            { label: 'Start by', value: formatLongDateTime(sample.dueAt) },
            { label: 'Time limit', value: `${sample.timeLimitMinutes} minutes, plus 5 minutes to submit` },
          ]}
        />
      )}
      {pastDue && <Alert tone="warning">The start-by time has passed. The employer may no longer accept a response.</Alert>}
      <div>
        <Button variant="primary" size="lg" trailingIcon={ArrowRight} onClick={() => setConfirming(true)}>
          Start, {sample.timeLimitMinutes}-minute timer begins
        </Button>
      </div>
      <ConfirmDialog
        open={confirming}
        title="Start the timer now?"
        confirmLabel={`Start ${sample.timeLimitMinutes}-minute timer`}
        tone="primary"
        busy={start.isPending}
        error={start.error}
        onCancel={() => {
          setConfirming(false)
          start.reset()
        }}
        onConfirm={() => start.mutate()}
      >
        <p>The timer starts as soon as you confirm and keeps running even if you close this page. Make sure you have {sample.timeLimitMinutes} minutes free.</p>
      </ConfirmDialog>
    </section>
  )
}

function ResponsePanel({ sample }: { sample: CandidateWorkSample }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const formRef = useRef<HTMLFormElement>(null)
  const now = useNow(1000)
  const deadline = deadlineOf(sample)
  const remaining = deadline === null ? null : Math.round((deadline - now) / 1000)
  const total = sample.timeLimitMinutes * 60
  const draft = useDraft(draftKey(sample.assignmentId), 600)
  const [responseUrl, setResponseUrl] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [confirming, setConfirming] = useState(false)
  const [announcement, setAnnouncement] = useState('')
  const lastThreshold = useRef<number | null>(null)

  useBeforeUnload(files.length > 0)

  // Announce only at 10, 5 and 1 minutes; a per-second live region would be unusable with a screen reader.
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
    mutationFn: () => workSamplesApi.submit(sample.assignmentId, { responseText: draft.value.trim(), responseUrl: responseUrl.trim() || null, files }),
    onSuccess: submitted => {
      setConfirming(false)
      draft.clear()
      setFiles([])
      notify('success', 'Response submitted', { body: 'The employer reviews it against the same scoring guide as everyone else.' })
      queryClient.setQueryData<CandidateWorkSample[]>(queryKeys.workSamples, current =>
        current?.map(item => (item.assignmentId === submitted.assignmentId ? submitted : item)),
      )
      void queryClient.invalidateQueries({ queryKey: queryKeys.workSamples })
      void queryClient.invalidateQueries({ queryKey: ['application'] })
      window.setTimeout(() => document.querySelector<HTMLElement>('[data-page-heading]')?.focus(), 0)
    },
    onError: error => {
      if (!isWindowClosedError(error)) return
      void queryClient.invalidateQueries({ queryKey: queryKeys.workSamples })
      void queryClient.invalidateQueries({ queryKey: ['application'] })
      if (remaining !== null && remaining <= 0) {
        setConfirming(false)
        notify('error', 'Your response was not accepted', { body: 'The submission window closed and this work sample has expired. Your text is still saved here.' })
      }
    },
  })

  // Once the grace period is over, check the server state once (the expiry sweep runs every minute).
  const closedNow = deadline !== null && remaining !== null && remaining <= -GRACE_SECONDS
  const checkedClosed = useRef(false)
  useEffect(() => {
    if (!closedNow || checkedClosed.current) return
    checkedClosed.current = true
    void queryClient.invalidateQueries({ queryKey: queryKeys.workSamples })
  }, [closedNow, queryClient])

  function review(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (!draft.value.trim() && files.length === 0) next.responseText = 'Write your response or attach a file before submitting.'
    const fileProblems = validateWorkSampleFiles(files)
    if (fileProblems.length > 0) next.files = fileProblems.join(' ')
    if (responseUrl.trim() && !isHttpUrl(responseUrl.trim())) next.responseUrl = 'Enter a full link starting with https:// or http://.'
    setErrors(next)
    if (Object.keys(next).length === 0) setConfirming(true)
    else focusFirstInvalid(formRef.current)
  }

  const timeUp = remaining !== null && remaining <= 0
  const closed = remaining !== null && remaining <= -GRACE_SECONDS
  const low = remaining !== null && remaining > 0 && remaining <= 300
  const urgent = remaining !== null && remaining > 0 && remaining <= 60
  const elapsed = remaining === null ? 0 : Math.min(total, Math.max(0, total - remaining))

  return (
    <>
      <div className="t-timer" data-tone={timeUp ? 'danger' : low ? 'warning' : undefined}>
        <Icon icon={Clock} size={16} className="t-timer-icon" />
        <div className="t-timer-text">
          <span className={`t-timer-num numeric${urgent ? ' is-urgent' : ''}`} role="timer" aria-label={remaining === null ? 'Time left unknown' : `${describeDuration(Math.max(0, remaining))} left`}>
            {remaining === null ? '—' : formatDuration(Math.max(0, remaining))}
          </span>
          <span className="c-meta">{timeUp ? (closed ? 'Window closed' : 'Time is up, submit now') : 'left'}</span>
        </div>
        <div className="t-timer-bar" aria-hidden="true">
          <span style={{ ['--v' as string]: total ? elapsed / total : 0 }} />
        </div>
        {deadline !== null && <span className="c-meta t-timer-end">Ends {formatTime(new Date(deadline))}</span>}
        <span className="sr-only" aria-live="assertive">
          {announcement}
        </span>
      </div>
      {timeUp && !closed && (
        <Alert tone="warning" live>
          Time is up. Submit now: responses are accepted for up to 5 minutes after the time limit.
        </Alert>
      )}
      {closed && (
        <Alert tone="danger" live>
          The submission window has closed. A response sent now will be refused and the work sample marked as expired.
        </Alert>
      )}

      <section className="t-block" aria-labelledby="instructions-title">
        <h2 id="instructions-title" className="t-h2" tabIndex={-1}>
          Instructions
        </h2>
        {sample.instructions ? (
          <div className="c-prose">{sample.instructions}</div>
        ) : (
          <p className="c-muted">The instructions could not be loaded. Refresh the page; the timer keeps running and your draft is saved.</p>
        )}
      </section>

      <section className="t-block" aria-labelledby="response-title">
        <h2 id="response-title" className="t-h2">
          Your response
        </h2>
        <form ref={formRef} className="t-form" onSubmit={review} noValidate>
          <Field
            label="Response"
            optional={files.length > 0}
            error={errors.responseText}
            hint={draft.savedAt ? `Draft saved in this browser at ${formatDate(draft.savedAt, { hour: 'numeric', minute: '2-digit' })}.` : 'Saved as a draft in this browser while you type.'}
          >
            <Textarea rows={10} maxRows={30} maxLength={RESPONSE_MAX} showCount value={draft.value} onChange={e => draft.setValue(e.target.value)} />
          </Field>
          <Field label="Link to your work" optional error={errors.responseUrl} hint="For example a shared document, spreadsheet or repository.">
            <Input type="url" inputMode="url" maxLength={URL_MAX} placeholder="https://" value={responseUrl} onChange={e => setResponseUrl(e.target.value)} />
          </Field>
          <div>
            <p className="ui-field-label">
              Attach files <span className="ui-field-optional">(optional)</span>
            </p>
            <FileDropzone
              compact
              multiple
              maxFiles={WORK_SAMPLE_MAX_FILES - files.length}
              disabled={files.length >= WORK_SAMPLE_MAX_FILES}
              accept={WORK_SAMPLE_ACCEPT}
              maxSize={WORK_SAMPLE_MAX_BYTES}
              prompt={files.length >= WORK_SAMPLE_MAX_FILES ? 'Three files attached.' : 'Drop files here, or'}
              hint="Up to 3 files: PDF, ZIP, DOCX, PPTX, PNG or JPG, 20 MB each."
              onFiles={chosen => {
                const next = [...files, ...chosen].slice(0, WORK_SAMPLE_MAX_FILES)
                setFiles(next)
                setErrors(current => ({ ...current, files: validateWorkSampleFiles(next).join(' ') }))
              }}
              files={files.map((file, i) => ({ id: i, name: file.name, size: file.size }))}
              onRemove={i => setFiles(current => current.filter((_, position) => position !== i))}
              error={errors.files || undefined}
            />
          </div>
          <div className="t-submit">
            <span className="c-meta">You cannot change your response after submitting.</span>
            <Button type="submit" variant="primary">
              Submit response
            </Button>
          </div>
        </form>
      </section>

      <ConfirmDialog
        open={confirming}
        title="Submit your response?"
        confirmLabel="Submit response"
        tone="primary"
        busy={submit.isPending}
        error={submit.error}
        onCancel={() => {
          setConfirming(false)
          submit.reset()
        }}
        onConfirm={() => submit.mutate()}
      >
        <p>
          You cannot edit it afterwards.
          {files.length > 0 ? ` ${files.length} file${files.length === 1 ? '' : 's'} will be attached.` : ''}
        </p>
      </ConfirmDialog>
    </>
  )
}
