import { useMemo, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { workSamplesApi } from '../../api/assessments'
import { qk } from '../../api/queryKeys'
import type { ApplicantDetail, WorkSample, WorkSampleAssignment } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { canCancelAssignment, invalidateAssignment, useCancelAssignment, useSubmissionDownload } from '../workSamples/assignmentActions'
import { AnchoredRating } from '../../components/AnchoredRating'
import { InlineError } from '../../components/QueryError'
import { WorkSampleStatusBadge } from '../../components/StatusBadges'
import {
  Button, Dialog, Drawer, EmptyState, Field, Input, Menu, IconButton, Quote, RadioCard, RadioGroup, Textarea, useToast,
} from '../../design/components'
import { ArrowUpRight, ChevronDown, Download, Ellipsis, FileText, Icon, ListChecks, Plus } from '../../design/icons'
import { formatFileSize, plural, relativeTime } from '../../design/lib/format'
import { fromLocalInput, safeHttpUrl } from '../../lib/format'
import { fullDateTime, localInputInDays, timeZoneLabel } from '../../lib/when'

const ASSIGNABLE = ['SHORTLISTED', 'INTERVIEWING']

/** Work samples on the applicant (FLOWS-HR-B §2): assign, read the submission, score with an anchored rubric, cancel. */
export function WorkSamplesTab({ jobId, detail }: { jobId: number; detail: ApplicantDetail }) {
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const samples = useQuery({ queryKey: qk.workSamples(jobId), queryFn: ({ signal }) => workSamplesApi.list(jobId, signal) })
  const assignedIds = new Set(detail.workSamples.map(a => a.workSampleId))
  const available = (samples.data ?? []).filter(s => s.status === 'ACTIVE' && !assignedIds.has(s.id))
  const [assignOpen, setAssignOpen] = useState(false)
  const [scoring, setScoring] = useState<WorkSampleAssignment | null>(null)
  const canAssign = can('workSamples.manage') && ASSIGNABLE.includes(detail.applicationStatus)

  const refresh = () => invalidateAssignment(queryClient, jobId, detail.applicationId)
  const cancel = useCancelAssignment(jobId)

  const assignHint = !ASSIGNABLE.includes(detail.applicationStatus)
    ? 'Work samples can be assigned once the application is shortlisted.'
    : samples.isSuccess && available.length === 0
      ? detail.workSamples.some(a => a.status === 'CANCELLED' || a.status === 'EXPIRED')
        ? 'Each work sample can be given to an application once. To set a cancelled or expired task again, duplicate it on the job’s Work samples tab and activate the copy.'
        : 'No active work sample left to assign. Activate one on the job’s Work samples tab.'
      : null

  return (
    <div className="ap-ws">
      <div className="ap-tab-head">
        <p className="ap-tab-intro">A rubric criterion linked to a requirement and scored 3 or 4 marks that requirement as <strong>Demonstrated</strong>.</p>
        {can('workSamples.manage') && (
          <div className="ap-tab-actions">
            <Button icon={Plus} onClick={() => setAssignOpen(true)} disabled={!canAssign || samples.isPending || available.length === 0} aria-describedby={assignHint ? 'ws-assign-hint' : undefined}>
              Assign work sample
            </Button>
          </div>
        )}
      </div>
      {can('workSamples.manage') && assignHint && <p id="ws-assign-hint" className="ap-muted ap-hint-line">{assignHint}</p>}

      {detail.workSamples.length === 0 ? (
        <EmptyState icon={ListChecks} title="No work sample assigned" description="A short, timed task scored against a rubric is the strongest evidence short of an interview." />
      ) : (
        <ul className="ap-rows">
          {detail.workSamples.map(a => (
            <AssignmentRow
              key={a.id}
              assignment={a}
              onScore={() => setScoring(a)}
              onCancel={() => cancel.request(a)}
            />
          ))}
        </ul>
      )}

      <AssignDialog
        open={assignOpen}
        jobId={jobId}
        applicationId={detail.applicationId}
        samples={available}
        onClose={() => setAssignOpen(false)}
        onAssigned={() => {
          setAssignOpen(false)
          toast({ tone: 'success', title: 'Work sample assigned', body: 'The candidate is notified and starts when ready.' })
          refresh()
        }}
      />
      {cancel.dialog}
      <ScoreDrawer
        assignment={scoring}
        sample={(samples.data ?? []).find(s => s.id === scoring?.workSampleId) ?? null}
        samplesLoading={samples.isPending}
        onClose={() => setScoring(null)}
        onScored={() => {
          setScoring(null)
          toast({ tone: 'success', title: 'Scores saved', body: 'Linked requirements are re-matched; the ladder updates when it finishes.' })
          refresh()
        }}
      />
    </div>
  )
}

function AssignmentRow({ assignment: a, onScore, onCancel }: { assignment: WorkSampleAssignment; onScore: () => void; onCancel: () => void }) {
  const { can } = useAuth()
  const [open, setOpen] = useState(false)
  const files = a.files ?? []
  const url = safeHttpUrl(a.responseUrl)
  const hasSubmission = Boolean(a.responseText || url || files.length)
  const steps = [
    { label: 'Assigned', at: a.assignedAt },
    a.status === 'ASSIGNED' && a.dueAt ? { label: 'Start by', at: a.dueAt, future: true } : null,
    a.startedAt ? { label: 'Started', at: a.startedAt } : null,
    a.submittedAt ? { label: 'Submitted', at: a.submittedAt } : null,
    a.scoredAt ? { label: 'Scored', at: a.scoredAt } : null,
    a.cancelledAt ? { label: 'Cancelled', at: a.cancelledAt } : null,
  ].filter(Boolean) as { label: string; at: string; future?: boolean }[]

  return (
    <li className="ap-row" data-open={open || undefined}>
      <div className="ap-row-head">
        <div className="ap-row-title">
          <span className="ap-row-name">{a.workSampleTitle}</span>
          <span className="ap-row-meta">
            {steps.map((s, i) => (
              <span key={s.label}>
                {i > 0 && <span className="ui-sep" aria-hidden="true">·</span>}
                {s.label} <time dateTime={s.at} title={fullDateTime(s.at)}>{s.future ? fullDateTime(s.at) : relativeTime(s.at)}</time>
              </span>
            ))}
          </span>
        </div>
        <div className="ap-row-status"><WorkSampleStatusBadge status={a.status} /></div>
        <div className="ap-row-score">
          {a.totalScore != null ? (
            <span className="ap-score">
              <span className="ap-score-num numeric">{a.totalScore.toFixed(1)}</span><span className="ap-score-of"> / 4</span>
              <span className="ap-score-bar" aria-hidden="true"><i style={{ ['--v' as string]: a.totalScore / 4 }} /></span>
            </span>
          ) : <span className="ap-muted">Not scored</span>}
        </div>
        <div className="ap-row-actions">
          {hasSubmission && (
            <Button variant="ghost" size="sm" trailingIcon={ChevronDown} aria-expanded={open} onClick={() => setOpen(o => !o)} className="ap-expand-btn">
              {open ? 'Hide submission' : 'Submission'}
            </Button>
          )}
          {a.status === 'SUBMITTED' && can('workSamples.score') && <Button size="sm" variant="primary" onClick={onScore}>Score with rubric</Button>}
          {can('workSamples.cancel') && canCancelAssignment(a) && (
            <Menu
              label={`More actions for ${a.workSampleTitle}`}
              trigger={props => <IconButton {...props} label="More actions" icon={Ellipsis} size="sm" />}
              items={[{ label: 'Cancel assignment', danger: true, onSelect: onCancel }]}
            />
          )}
        </div>
      </div>
      {a.status === 'CANCELLED' && a.cancelReason && (
        <p className="ap-row-note">Cancelled{a.cancelledByName ? ` by ${a.cancelledByName}` : ''}: “{a.cancelReason}”</p>
      )}
      {a.scores.length > 0 && (
        <ul className="ap-scores" aria-label="Scores by criterion">
          {a.scores.map((s, i) => (
            <li key={`${s.criterion}-${i}`}>
              <span className="ap-score-chip numeric" data-score={s.score}>{s.score}</span>
              <span>{s.criterion}{s.note ? <span className="ap-muted"> — {s.note}</span> : null}</span>
            </li>
          ))}
          {a.scoredByName && <li className="ap-muted ap-scores-by">Scored by {a.scoredByName}{a.scoredAt ? `, ${relativeTime(a.scoredAt)}` : ''}</li>}
        </ul>
      )}
      <div className="ap-row-more" aria-hidden={!open || undefined} {...(open ? {} : ({ inert: '' } as Record<string, string>))}>
        <div><div className="ap-row-more-inner">
          <Submission assignment={a} />
        </div></div>
      </div>
    </li>
  )
}

function Submission({ assignment: a }: { assignment: WorkSampleAssignment }) {
  const { can } = useAuth()
  const download = useSubmissionDownload()
  const url = safeHttpUrl(a.responseUrl)
  const files = a.files ?? []
  return (
    <div className="ap-submission">
      {a.responseText ? <Quote className="ap-submission-text">{a.responseText}</Quote> : <p className="ap-muted">No written answer.</p>}
      {url && (
        <p><a href={url} target="_blank" rel="noopener noreferrer nofollow">Submitted link <Icon icon={ArrowUpRight} size={12} /><span className="sr-only"> (opens in a new tab)</span></a></p>
      )}
      {files.length > 0 && (
        <ul className="ap-files" aria-label="Submitted files">
          {files.map(f => (
            <li key={f.id}>
              <Icon icon={FileText} size={16} />
              <span className="ap-file-name" title={f.originalName}>{f.originalName}</span>
              <span className="ap-muted numeric">{formatFileSize(f.sizeBytes)}</span>
              {can('workSamples.downloadFiles') && (
                <Button size="sm" variant="ghost" icon={Download} loading={download.busyFileId === f.id} disabled={download.busyFileId != null && download.busyFileId !== f.id} aria-label={`Download ${f.originalName}`} onClick={() => download.start(a.id, f)}>Download</Button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
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
  const [errors, setErrors] = useState<{ sample?: string; due?: string }>({})
  const assign = useMutation({
    mutationFn: () => workSamplesApi.assign(jobId, applicationId, { workSampleId: Number(sampleId), dueAt: fromLocalInput(due) ?? undefined }),
    onSuccess: () => { setSampleId(''); setDue(''); onAssigned() },
  })
  const chosen = sampleId || (samples.length === 1 ? String(samples[0].id) : '')

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: typeof errors = {}
    if (!chosen) next.sample = 'Choose a work sample.'
    if (due) {
      const iso = fromLocalInput(due)
      if (!iso || new Date(iso).getTime() <= Date.now()) next.due = 'The start-by time must be in the future.'
    }
    setErrors(next)
    if (Object.keys(next).length === 0) {
      if (!sampleId) setSampleId(chosen)
      assign.mutate()
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Assign a work sample"
      description="The candidate sees the instructions when they press Start; the time limit runs from then."
      busy={assign.isPending}
      footer={<>
        <Button variant="secondary" onClick={onClose} disabled={assign.isPending}>Cancel</Button>
        <Button type="submit" form="ws-assign" variant="primary" loading={assign.isPending}>Assign</Button>
      </>}
    >
      <form id="ws-assign" className="sx-action-form" onSubmit={submit} noValidate>
        <RadioGroup legend="Work sample" value={chosen} onChange={v => { setSampleId(v); setErrors(e => ({ ...e, sample: undefined })) }} error={errors.sample}>
          {samples.map(s => (
            <RadioCard key={s.id} value={String(s.id)} label={s.title} description={`${s.timeLimitMinutes} minutes · ${plural(s.rubric.length, 'criterion', 'criteria')}`} />
          ))}
        </RadioGroup>
        <Field label="Start by" optional hint={`The latest time to start, in ${timeZoneLabel()}. Once started, the time limit applies.`} error={errors.due}>
          <Input type="datetime-local" value={due} onChange={event => setDue(event.target.value)} />
        </Field>
        <div className="ap-quick" role="group" aria-label="Quick start-by times">
          {[2, 3, 5, 7].map(days => (
            <button key={days} type="button" className="sx-suggestion" onClick={() => setDue(localInputInDays(days))}>In {days} days</button>
          ))}
          {due && <button type="button" className="sx-suggestion" onClick={() => setDue('')}>No deadline</button>}
        </div>
        <InlineError error={assign.error} />
      </form>
    </Dialog>
  )
}

/**
 * Score with the anchored rubric (FLOWS-HR-B §2): the submission on top, then each criterion as four anchor cards
 * plus an optional note. Sticky footer with progress and the running average. Every criterion is required.
 */
function ScoreDrawer({ assignment, sample, samplesLoading, onClose, onScored }: {
  assignment: WorkSampleAssignment | null
  sample: WorkSample | null
  samplesLoading: boolean
  onClose: () => void
  onScored: () => void
}) {
  return (
    <Drawer
      open={assignment != null}
      onClose={onClose}
      size="lg"
      title={assignment ? `Score: ${assignment.workSampleTitle}` : 'Score'}
      description="Read the submission, then rate each criterion against its anchors."
    >
      {assignment && (sample ? <ScoreForm key={assignment.id} assignment={assignment} sample={sample} onClose={onClose} onScored={onScored} />
        : <p className="ap-muted">{samplesLoading ? 'Loading the rubric…' : 'The rubric for this work sample could not be loaded. Close and try again.'}</p>)}
    </Drawer>
  )
}

function ScoreForm({ assignment, sample, onClose, onScored }: { assignment: WorkSampleAssignment; sample: WorkSample; onClose: () => void; onScored: () => void }) {
  const rubric = sample.rubric
  const [scores, setScores] = useState<(number | null)[]>(() => rubric.map(c => assignment.scores.find(s => s.criterion === c.criterion)?.score ?? null))
  const [notes, setNotes] = useState<string[]>(() => rubric.map(c => assignment.scores.find(s => s.criterion === c.criterion)?.note ?? ''))
  const [touched, setTouched] = useState(false)
  const rated = scores.filter(s => s != null).length
  const average = useMemo(() => (rated ? (scores.reduce<number>((t, s) => t + (s ?? 0), 0) / rated) : null), [scores, rated])
  const save = useMutation({
    mutationFn: () => workSamplesApi.score(assignment.id, {
      scores: rubric.map((c, i) => ({ requirementId: c.requirementId, criterion: c.criterion, score: scores[i] as number, note: notes[i].trim() || undefined })),
    }),
    onSuccess: onScored,
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    setTouched(true)
    const first = scores.findIndex(s => s == null)
    if (first >= 0) {
      document.querySelector<HTMLInputElement>(`#crit-${first} input`)?.focus()
      return
    }
    save.mutate()
  }

  return (
    <form className="ap-score-form" onSubmit={submit} noValidate>
      <section className="ap-score-sub" aria-label="Submission">
        <h3 className="t-label">Submission</h3>
        <Submission assignment={assignment} />
      </section>
      <ol className="ap-criteria">
        {rubric.map((c, i) => (
          <li key={`${c.criterion}-${i}`} id={`crit-${i}`} className="ap-criterion">
            <AnchoredRating
              legend={<><span className="ap-crit-n numeric">{i + 1}.</span> {c.criterion}</>}
              description={c.requirementId ? 'Linked to a requirement: 3 or 4 marks it Demonstrated.' : undefined}
              anchors={c.anchors}
              value={scores[i]}
              required
              invalid={touched && scores[i] == null}
              onChange={v => setScores(cur => cur.map((x, j) => (j === i ? v : x)))}
            />
            <Field label="Note" optional>
              <Textarea rows={1} maxRows={5} maxLength={1000} value={notes[i]} onChange={e => setNotes(cur => cur.map((x, j) => (j === i ? e.target.value : x)))} />
            </Field>
          </li>
        ))}
      </ol>
      <InlineError error={save.error} />
      <div className="ap-sticky-foot">
        <p className="ap-progress">
          <span className="ap-progress-bar" aria-hidden="true"><i style={{ ['--v' as string]: rubric.length ? rated / rubric.length : 0 }} /></span>
          <span><strong className="numeric">{rated}</strong> of <span className="numeric">{rubric.length}</span> rated{average != null && <> · average <strong className="numeric">{average.toFixed(1)}</strong></>}</span>
        </p>
        <div className="ap-foot-actions">
          <Button variant="secondary" onClick={onClose} disabled={save.isPending}>Cancel</Button>
          <Button type="submit" variant="primary" loading={save.isPending}>Save scores</Button>
        </div>
      </div>
    </form>
  )
}
