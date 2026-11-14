import { FormEvent, useId, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { workSamplesApi } from '../../api/assessments'
import { errorMessage } from '../../api/client'
import { qk } from '../../api/queryKeys'
import {
  ANCHOR_LEVELS, ASSIGNMENT_STATUSES, AssignmentStatus, JobStaff, Requirement, RubricCriterion, WorkSample, WorkSampleInput, WorkSampleStatus,
} from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { StatusBadge } from '../../components/Badges'
import { ConfirmDialog } from '../../components/Dialog'
import { Field } from '../../components/Field'
import { CloseIcon, PlusIcon, SparkIcon } from '../../components/Icons'
import { useNotices } from '../../components/Notices'
import { AnchorList } from '../../components/RatingScale'
import { EmptyPanel, ErrorPanel, FormError, LoadingPanel } from '../../components/States'
import { formatDateTime, humanize } from '../../lib/format'

/** Work samples for a job: generate (AI service, DRAFT), edit title/instructions/time limit/rubric, activate, archive. */
export function WorkSamplesTab({ job }: { job: JobStaff }) {
  const { can } = useAuth()
  const canManage = can('workSamples.manage')
  const queryClient = useQueryClient()
  const notices = useNotices()
  const samples = useQuery({ queryKey: qk.workSamples(job.id), queryFn: ({ signal }) => workSamplesApi.list(job.id, signal) })
  const [editing, setEditing] = useState<number | null>(null)
  const [expanded, setExpanded] = useState<number | null>(null)
  const [statusChange, setStatusChange] = useState<{ sample: WorkSample; status: WorkSampleStatus } | null>(null)

  const generate = useMutation({
    mutationFn: () => workSamplesApi.generate(job.id),
    onSuccess: sample => {
      queryClient.setQueryData<WorkSample[]>(qk.workSamples(job.id), current => [...(current ?? []), sample])
      setEditing(sample.id)
      notices.success('A draft work sample was generated. Review and edit it before activating.')
    },
    onError: cause => notices.error(errorMessage(cause, 'The work sample could not be generated.')),
  })

  const setStatus = useMutation({
    mutationFn: ({ sample, status }: { sample: WorkSample; status: WorkSampleStatus }) => workSamplesApi.setStatus(job.id, sample.id, status),
    onSuccess: updated => {
      queryClient.setQueryData<WorkSample[]>(qk.workSamples(job.id), current => current?.map(item => (item.id === updated.id ? updated : item)))
      setStatusChange(null)
      notices.success(`“${updated.title}” is now ${humanize(updated.status).toLowerCase()}.`)
    },
  })

  return (
    <div className="tab-stack">
      <section className="jobs-card" aria-labelledby="ws-heading">
        <div className="jobs-card-head">
          <div>
            <h2 id="ws-heading">Work samples</h2>
            <p>A short, timed task scored with a four-level rubric. A rubric criterion linked to a requirement and scored 3 or 4 marks that requirement as demonstrated.</p>
          </div>
          {canManage && (
            <button type="button" className="primary-button workspace-primary" onClick={() => generate.mutate()} disabled={generate.isPending || job.requirements.length === 0} title={job.requirements.length === 0 ? 'Add requirements first.' : undefined}>
              <SparkIcon /> {generate.isPending ? 'Generating…' : 'Generate work sample'}
            </button>
          )}
        </div>

        {samples.isPending ? <LoadingPanel label="Loading work samples…" compact /> : samples.isError ? (
          <ErrorPanel error={samples.error} onRetry={() => void samples.refetch()} compact />
        ) : samples.data.length === 0 ? (
          <EmptyPanel title="No work samples yet" compact>
            {canManage ? 'Generate one from the job’s requirements, then edit and activate it.' : 'An HR manager can generate one from the job’s requirements.'}
          </EmptyPanel>
        ) : (
          <ul className="card-list">
            {samples.data.map(sample => (
              <li key={sample.id} className="item-card">
                {editing === sample.id ? (
                  <WorkSampleEditor
                    jobId={job.id}
                    sample={sample}
                    requirements={job.requirements}
                    onDone={() => setEditing(null)}
                  />
                ) : (
                  <>
                    <div className="item-card-head">
                      <div>
                        <h3>{sample.title}</h3>
                        <p className="item-meta">
                          {sample.timeLimitMinutes} minutes · {sample.rubric.length} rubric criteria · updated {formatDateTime(sample.updatedAt)}
                        </p>
                      </div>
                      <StatusBadge status={sample.status} />
                    </div>
                    <div className="item-actions">
                      <button type="button" className="outline-button" aria-expanded={expanded === sample.id} onClick={() => setExpanded(expanded === sample.id ? null : sample.id)}>
                        {expanded === sample.id ? 'Hide details' : 'View instructions and rubric'}
                      </button>
                      {canManage && sample.status === 'DRAFT' && <button type="button" className="outline-button" onClick={() => setEditing(sample.id)}>Edit</button>}
                      {canManage && sample.status !== 'ACTIVE' && (
                        <button type="button" className="primary-button workspace-primary" onClick={() => { setStatus.reset(); setStatusChange({ sample, status: 'ACTIVE' }) }}>Activate</button>
                      )}
                      {canManage && sample.status !== 'ARCHIVED' && (
                        <button type="button" className="outline-button" onClick={() => { setStatus.reset(); setStatusChange({ sample, status: 'ARCHIVED' }) }}>Archive</button>
                      )}
                    </div>
                    {expanded === sample.id && <WorkSampleDetails sample={sample} requirements={job.requirements} />}
                  </>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <AssignmentsOverview jobId={job.id} />

      <ConfirmDialog
        open={statusChange != null}
        title={statusChange?.status === 'ACTIVE' ? 'Activate this work sample?' : 'Archive this work sample?'}
        message={statusChange?.status === 'ACTIVE'
          ? 'Active samples can be assigned to shortlisted applicants. They can no longer be edited, so every candidate gets the same task.'
          : 'Archived samples cannot be assigned. Existing assignments and scores are kept.'}
        confirmLabel={statusChange?.status === 'ACTIVE' ? 'Activate' : 'Archive'}
        busy={setStatus.isPending}
        error={setStatus.error}
        onCancel={() => setStatusChange(null)}
        onConfirm={() => { if (statusChange) setStatus.mutate(statusChange) }}
      />
    </div>
  )
}

function requirementLabel(requirements: Requirement[], id: number | null) {
  if (id == null) return 'General criterion'
  const requirement = requirements.find(item => item.id === id)
  return requirement ? `${humanize(requirement.type)}: ${requirement.description}` : `Requirement #${id}`
}

function WorkSampleDetails({ sample, requirements }: { sample: WorkSample; requirements: Requirement[] }) {
  return (
    <div className="item-details">
      <h4>Instructions (shown to the candidate after they press Start)</h4>
      <p className="description-text">{sample.instructions}</p>
      <h4>Rubric</h4>
      <ol className="rubric-view">
        {sample.rubric.map((criterion, index) => (
          <li key={`${criterion.criterion}-${index}`}>
            <strong>{criterion.criterion}</strong>
            <span className="item-meta">{requirementLabel(requirements, criterion.requirementId)}</span>
            <AnchorList anchors={criterion.anchors} />
          </li>
        ))}
      </ol>
    </div>
  )
}

function emptyCriterion(): RubricCriterion {
  return { requirementId: null, criterion: '', anchors: { '1': '', '2': '', '3': '', '4': '' } }
}

function WorkSampleEditor({ jobId, sample, requirements, onDone }: { jobId: number; sample: WorkSample; requirements: Requirement[]; onDone: () => void }) {
  const queryClient = useQueryClient()
  const notices = useNotices()
  const [title, setTitle] = useState(sample.title)
  const [instructions, setInstructions] = useState(sample.instructions)
  const [timeLimit, setTimeLimit] = useState(String(sample.timeLimitMinutes))
  const [rubric, setRubric] = useState<RubricCriterion[]>(() => sample.rubric.map(criterion => ({ ...criterion, anchors: { ...criterion.anchors } })))
  const [errors, setErrors] = useState<Record<string, string>>({})

  const save = useMutation({
    mutationFn: (body: WorkSampleInput) => workSamplesApi.update(jobId, sample.id, body),
    onSuccess: updated => {
      queryClient.setQueryData<WorkSample[]>(qk.workSamples(jobId), current => current?.map(item => (item.id === updated.id ? updated : item)))
      notices.success('Work sample saved.')
      onDone()
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (!title.trim()) next.title = 'Enter a title.'
    if (!instructions.trim()) next.instructions = 'Write the instructions.'
    const minutes = Number(timeLimit)
    if (!Number.isInteger(minutes) || minutes < 5 || minutes > 480) next.timeLimit = 'Use a whole number of minutes from 5 to 480.'
    if (rubric.length === 0) next.rubric = 'Add at least one rubric criterion.'
    rubric.forEach((criterion, index) => {
      if (!criterion.criterion.trim()) next[`c${index}`] = 'Name the criterion.'
      else if (ANCHOR_LEVELS.some(level => !criterion.anchors[level].trim())) next[`c${index}`] = 'Describe all four levels.'
    })
    setErrors(next)
    if (Object.keys(next).length) {
      window.setTimeout(() => document.querySelector<HTMLElement>('.item-card [aria-invalid="true"]')?.focus(), 0)
      return
    }
    save.mutate({
      title: title.trim(),
      instructions: instructions.trim(),
      timeLimitMinutes: minutes,
      rubric: rubric.map(criterion => ({
        requirementId: criterion.requirementId,
        criterion: criterion.criterion.trim(),
        anchors: { '1': criterion.anchors['1'].trim(), '2': criterion.anchors['2'].trim(), '3': criterion.anchors['3'].trim(), '4': criterion.anchors['4'].trim() },
      })),
    })
  }

  return (
    <form className="inline-editor" onSubmit={submit} noValidate aria-label={`Edit ${sample.title}`}>
      <div className="form-grid">
        <Field label="Title" required error={errors.title}>
          <input value={title} maxLength={255} onChange={event => setTitle(event.target.value)} />
        </Field>
        <Field label="Time limit (minutes)" required error={errors.timeLimit} hint="Submissions are accepted for 5 minutes after the limit.">
          <input type="number" min={5} max={480} step={5} inputMode="numeric" value={timeLimit} onChange={event => setTimeLimit(event.target.value)} />
        </Field>
        <Field label="Instructions" required className="full-width" error={errors.instructions} hint="Hidden from the candidate until they press Start.">
          <textarea rows={8} value={instructions} onChange={event => setInstructions(event.target.value)} />
        </Field>
      </div>
      <RubricEditor rubric={rubric} onChange={setRubric} requirements={requirements} errors={errors} />
      <FormError error={save.error} />
      <div className="editor-actions">
        <button type="button" className="outline-button" onClick={onDone} disabled={save.isPending}>Cancel</button>
        <button type="submit" className="primary-button workspace-primary" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Save work sample'}</button>
      </div>
    </form>
  )
}

/** Rubric criteria, each optionally linked to a requirement, with four behavioural anchors. */
export function RubricEditor({ rubric, onChange, requirements, errors }: {
  rubric: RubricCriterion[]
  onChange: (rubric: RubricCriterion[]) => void
  requirements: Requirement[]
  errors: Record<string, string>
}) {
  const baseId = useId()
  const update = (index: number, values: Partial<RubricCriterion>) => onChange(rubric.map((item, i) => (i === index ? { ...item, ...values } : item)))
  return (
    <fieldset className="rubric-editor">
      <legend>Rubric <span className="field-hint">(1 = weak … 4 = strong)</span></legend>
      {errors.rubric && <small className="field-error">{errors.rubric}</small>}
      {rubric.map((criterion, index) => {
        const id = `${baseId}-${index}`
        const error = errors[`c${index}`]
        return (
          <div key={index} className="rubric-criterion">
            <div className="rubric-criterion-head">
              <label className="req-field grow" htmlFor={`${id}-name`}>
                <span>Criterion {index + 1}</span>
                <input id={`${id}-name`} value={criterion.criterion} maxLength={255} onChange={event => update(index, { criterion: event.target.value })} aria-invalid={Boolean(error)} aria-describedby={error ? `${id}-error` : undefined} />
              </label>
              <label className="req-field" htmlFor={`${id}-req`}>
                <span>Evidence for requirement</span>
                <select id={`${id}-req`} value={criterion.requirementId ?? ''} onChange={event => update(index, { requirementId: event.target.value === '' ? null : Number(event.target.value) })}>
                  <option value="">None (general criterion)</option>
                  {requirements.map(requirement => <option key={requirement.id} value={requirement.id}>{humanize(requirement.type)}: {requirement.description}</option>)}
                </select>
              </label>
              <button type="button" className="icon-button danger" onClick={() => onChange(rubric.filter((_, i) => i !== index))} aria-label={`Remove criterion ${index + 1}`}><CloseIcon /></button>
            </div>
            {error && <small className="field-error" id={`${id}-error`}>{error}</small>}
            <div className="anchor-grid">
              {ANCHOR_LEVELS.map(level => (
                <label key={level} className="req-field" htmlFor={`${id}-a${level}`}>
                  <span>Level {level}</span>
                  <textarea id={`${id}-a${level}`} rows={3} value={criterion.anchors[level]} onChange={event => update(index, { anchors: { ...criterion.anchors, [level]: event.target.value } })} />
                </label>
              ))}
            </div>
          </div>
        )
      })}
      <button type="button" className="outline-button" onClick={() => onChange([...rubric, emptyCriterion()])}><PlusIcon /> Add criterion</button>
    </fieldset>
  )
}

/** Assignments across the job (e.g. "Submitted" = waiting for a score). Scoring happens on the applicant page. */
function AssignmentsOverview({ jobId }: { jobId: number }) {
  const [status, setStatus] = useState<AssignmentStatus | 'ALL'>('SUBMITTED')
  const assignments = useQuery({
    queryKey: qk.assignments(jobId, status),
    queryFn: ({ signal }) => workSamplesApi.assignments(jobId, status === 'ALL' ? undefined : status, signal),
  })
  return (
    <section className="jobs-card" aria-labelledby="assignments-heading">
      <div className="jobs-card-head">
        <div>
          <h2 id="assignments-heading">Assignments</h2>
          <p>Open an applicant to read the submission and score it against the rubric.</p>
        </div>
        <label className="status-filter">
          <span>Status</span>
          <select value={status} onChange={event => setStatus(event.target.value as AssignmentStatus | 'ALL')}>
            <option value="ALL">All</option>
            {ASSIGNMENT_STATUSES.map(value => <option key={value} value={value}>{value === 'SUBMITTED' ? 'Submitted (to score)' : humanize(value)}</option>)}
          </select>
        </label>
      </div>
      {assignments.isPending ? <LoadingPanel compact label="Loading assignments…" /> : assignments.isError ? (
        <ErrorPanel compact error={assignments.error} onRetry={() => void assignments.refetch()} />
      ) : assignments.data.length === 0 ? (
        <EmptyPanel compact title="No assignments with this status" />
      ) : (
        <div className="job-table-wrap">
          <table className="job-table">
            <thead>
              <tr>
                <th scope="col">APPLICATION</th>
                <th scope="col">WORK SAMPLE</th>
                <th scope="col">STATUS</th>
                <th scope="col">START BY</th>
                <th scope="col">SUBMITTED</th>
                <th scope="col">SCORE</th>
              </tr>
            </thead>
            <tbody>
              {assignments.data.map(assignment => (
                <tr key={assignment.id}>
                  <td><Link className="job-title-link" to={`/jobs/${jobId}/applicants/${assignment.applicationId}`}>Application #A-{assignment.applicationId}</Link></td>
                  <td>{assignment.workSampleTitle}</td>
                  <td><StatusBadge status={assignment.status} /></td>
                  <td>{formatDateTime(assignment.dueAt)}</td>
                  <td>{formatDateTime(assignment.submittedAt)}</td>
                  <td>{assignment.totalScore != null ? assignment.totalScore.toFixed(2) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
