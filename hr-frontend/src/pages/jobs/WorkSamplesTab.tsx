import { FormEvent, useId, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { workSamplesApi } from '../../api/assessments'
import { errorMessage } from '../../api/client'
import { qk } from '../../api/queryKeys'
import {
  ANCHOR_LEVELS, AssignmentStatus, JobStaff, Requirement, RubricCriterion, WorkSample, WorkSampleAssignment, WorkSampleInput, WorkSampleStatus,
} from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { ActionDialog } from '../../components/ActionDialog'
import { QueryError } from '../../components/QueryError'
import { WorkSampleStatusBadge } from '../../components/StatusBadges'
import {
  Button, DataTable, Drawer, EmptyState, Field, IconButton, Input, LoadingRegion, ReqMarker, Section, SegmentedControl, Select, Skeleton,
  Textarea, Tooltip, useToast, type Column,
} from '../../design/components'
import { plural, relativeTime } from '../../design/lib/format'
import { ChevronDown, FileText, Icon, ListChecks, Pencil, Plus, Trash2 } from '../../design/icons'
import { formatDateTime, humanize } from '../../lib/format'

/** Work samples for a job: generate (DRAFT), edit in a drawer, activate (freezes it), archive; plus the assignments. */
export function WorkSamplesTab({ job }: { job: JobStaff }) {
  const { can } = useAuth()
  const canManage = can('workSamples.manage')
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const samples = useQuery({ queryKey: qk.workSamples(job.id), queryFn: ({ signal }) => workSamplesApi.list(job.id, signal) })
  const [editing, setEditing] = useState<WorkSample | null>(null)
  const [expanded, setExpanded] = useState<number | null>(null)
  const [statusChange, setStatusChange] = useState<{ sample: WorkSample; status: WorkSampleStatus } | null>(null)

  const setStatus = useMutation({
    mutationFn: ({ sample, status }: { sample: WorkSample; status: WorkSampleStatus }) => workSamplesApi.setStatus(job.id, sample.id, status),
    onSuccess: updated => {
      queryClient.setQueryData<WorkSample[]>(qk.workSamples(job.id), current => current?.map(item => (item.id === updated.id ? updated : item)))
      setStatusChange(null)
      toast({ tone: 'success', title: `“${updated.title}” is ${humanize(updated.status).toLowerCase()}`, body: updated.status === 'ACTIVE' ? 'Assign it from a shortlisted applicant’s page.' : undefined })
    },
  })

  const generate = useMutation({
    mutationFn: () => workSamplesApi.generate(job.id),
    onSuccess: sample => {
      queryClient.setQueryData<WorkSample[]>(qk.workSamples(job.id), current => [...(current ?? []), sample])
      setExpanded(sample.id)
      setEditing(sample)
    },
    onError: cause => toast({ tone: 'danger', title: 'The work sample could not be generated', body: errorMessage(cause) }),
  })

  const noRequirements = job.requirements.length === 0
  const generateButton = canManage && (
    <Tooltip content={noRequirements ? 'Add requirements to the job first.' : 'Drafts a timed task and a four-level rubric from the must-haves.'} labelOnly>
      <Button icon={Plus} variant={samples.data?.length ? 'secondary' : 'primary'} loading={generate.isPending} disabled={noRequirements} onClick={() => generate.mutate()}>
        {generate.isPending ? 'Generating…' : 'Generate from requirements'}
      </Button>
    </Tooltip>
  )

  return (
    <div className="hra-tab-stack">
      <Section
        title="Work samples"
        description="A short timed task scored with a four-level rubric. A criterion linked to a requirement and scored 3 or 4 marks that requirement as demonstrated."
        actions={samples.data && samples.data.length > 0 ? generateButton : undefined}
      >
        {samples.isPending ? (
          <LoadingRegion label="Loading work samples"><div className="hra-list-skel">{[0, 1].map(i => <Skeleton key={i} width="100%" height={44} />)}</div></LoadingRegion>
        ) : samples.isError ? (
          <QueryError error={samples.error} title="Work samples could not be loaded" onRetry={() => void samples.refetch()} compact />
        ) : samples.data.length === 0 ? (
          <EmptyState
            icon={FileText}
            title="No work samples yet"
            description={canManage ? 'Generate one from the job’s requirements, review the rubric, then activate it so shortlisted applicants can be assigned the same task.' : 'An HR manager can generate one from the job’s requirements.'}
            action={generateButton || undefined}
          />
        ) : (
          <ul className="hra-ws-list">
            {samples.data.map(sample => {
              const isOpen = expanded === sample.id
              return (
                <li key={sample.id} className="hra-ws-item" data-open={isOpen || undefined}>
                  <div className="hra-ws-row">
                    <button type="button" className="hra-ws-toggle" aria-expanded={isOpen} aria-controls={`ws-${sample.id}`} onClick={() => setExpanded(isOpen ? null : sample.id)}>
                      <Icon icon={ChevronDown} size={16} className="hra-chevron" />
                      <span className="hra-ws-title">{sample.title}</span>
                      <span className="hra-ws-meta numeric">{sample.timeLimitMinutes} min · {plural(sample.rubric.length, 'criterion', 'criteria')} · updated {relativeTime(sample.updatedAt)}</span>
                    </button>
                    <WorkSampleStatusBadge status={sample.status} />
                    {canManage && (
                      <span className="hra-ws-actions">
                        {sample.status === 'DRAFT' && <IconButton label={`Edit ${sample.title}`} icon={Pencil} size="sm" onClick={() => setEditing(sample)} />}
                        {sample.status !== 'ACTIVE' && <Button size="sm" variant={sample.status === 'DRAFT' ? 'primary' : 'secondary'} onClick={() => { setStatus.reset(); setStatusChange({ sample, status: 'ACTIVE' }) }}>Activate</Button>}
                        {sample.status === 'ACTIVE' && <Button size="sm" variant="ghost" onClick={() => { setStatus.reset(); setStatusChange({ sample, status: 'ARCHIVED' }) }}>Archive</Button>}
                      </span>
                    )}
                  </div>
                  <div className="hra-expand" id={`ws-${sample.id}`} data-open={isOpen || undefined}>
                    <div className="hra-expand-inner">
                      {isOpen && <WorkSampleDetails sample={sample} requirements={job.requirements} />}
                    </div>
                  </div>
                </li>
              )
            })}
          </ul>
        )}
      </Section>

      <AssignmentsOverview jobId={job.id} />

      <WorkSampleEditor
        jobId={job.id}
        sample={editing}
        requirements={job.requirements}
        onDone={saved => {
          setEditing(null)
          if (saved && saved.status === 'DRAFT' && canManage) {
            toast({ tone: 'success', title: 'Work sample saved', body: 'Activate it when the rubric reads right.', action: { label: 'Activate', onClick: () => setStatusChange({ sample: saved, status: 'ACTIVE' }) } })
          }
        }}
      />

      <ActionDialog
        open={statusChange != null}
        size="sm"
        title={statusChange?.status === 'ACTIVE' ? `Activate “${statusChange.sample.title}”?` : `Archive “${statusChange?.sample.title}”?`}
        confirmLabel={statusChange?.status === 'ACTIVE' ? 'Activate' : 'Archive'}
        busy={setStatus.isPending}
        error={setStatus.error}
        onCancel={() => setStatusChange(null)}
        onConfirm={() => { if (statusChange) setStatus.mutate(statusChange) }}
      >
        <p>
          {statusChange?.status === 'ACTIVE'
            ? 'It can then be assigned to shortlisted applicants. It can no longer be edited, so every candidate gets the same task.'
            : 'It can no longer be assigned. Existing assignments and scores are kept.'}
        </p>
      </ActionDialog>
    </div>
  )
}

function requirementLabel(requirements: Requirement[], id: number | null) {
  if (id == null) return null
  return requirements.find(item => item.id === id) ?? null
}

function AnchorGrid({ anchors }: { anchors: RubricCriterion['anchors'] }) {
  return (
    <ol className="hra-anchors">
      {ANCHOR_LEVELS.map(level => (
        <li key={level}>
          <span className="hra-anchor-level numeric" aria-label={`Level ${level}`}>{level}</span>
          <span>{anchors[level]}</span>
        </li>
      ))}
    </ol>
  )
}
export { AnchorGrid }

function WorkSampleDetails({ sample, requirements }: { sample: WorkSample; requirements: Requirement[] }) {
  return (
    <div className="hra-ws-details">
      <h4 className="t-label">Instructions <span className="subtle">(shown to the candidate when they press Start)</span></h4>
      <p className="hra-prose">{sample.instructions}</p>
      <h4 className="t-label">Rubric <span className="subtle">(1 = weak, 4 = strong)</span></h4>
      <ol className="hra-rubric">
        {sample.rubric.map((criterion, index) => {
          const linked = requirementLabel(requirements, criterion.requirementId)
          return (
            <li key={`${criterion.criterion}-${index}`}>
              <p className="hra-rubric-head">
                <strong>{criterion.criterion}</strong>
                {linked ? <span className="subtle">Evidence for: {linked.description}</span> : <span className="subtle">General criterion</span>}
                {linked && <ReqMarker kind={linked.mandatory ? 'must' : 'nice'} />}
              </p>
              <AnchorGrid anchors={criterion.anchors} />
            </li>
          )
        })}
      </ol>
    </div>
  )
}

function emptyCriterion(): RubricCriterion {
  return { requirementId: null, criterion: '', anchors: { '1': '', '2': '', '3': '', '4': '' } }
}

function WorkSampleEditor({ jobId, sample, requirements, onDone }: { jobId: number; sample: WorkSample | null; requirements: Requirement[]; onDone: (saved?: WorkSample) => void }) {
  return (
    <Drawer open={sample != null} onClose={() => onDone()} size="lg" title={sample ? `Edit “${sample.title}”` : 'Edit work sample'} description="Drafts can be edited until they are activated.">
      {sample && <WorkSampleForm key={sample.id} jobId={jobId} sample={sample} requirements={requirements} onDone={onDone} />}
    </Drawer>
  )
}

function WorkSampleForm({ jobId, sample, requirements, onDone }: { jobId: number; sample: WorkSample; requirements: Requirement[]; onDone: (saved?: WorkSample) => void }) {
  const queryClient = useQueryClient()
  const formId = useId()
  const [title, setTitle] = useState(sample.title)
  const [instructions, setInstructions] = useState(sample.instructions)
  const [timeLimit, setTimeLimit] = useState(String(sample.timeLimitMinutes))
  const [rubric, setRubric] = useState<RubricCriterion[]>(() => sample.rubric.map(criterion => ({ ...criterion, anchors: { ...criterion.anchors } })))
  const [errors, setErrors] = useState<Record<string, string>>({})

  const save = useMutation({
    mutationFn: (body: WorkSampleInput) => workSamplesApi.update(jobId, sample.id, body),
    onSuccess: updated => {
      queryClient.setQueryData<WorkSample[]>(qk.workSamples(jobId), current => current?.map(item => (item.id === updated.id ? updated : item)))
      onDone(updated)
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (!title.trim()) next.title = 'Enter a title.'
    if (!instructions.trim()) next.instructions = 'Write the instructions the candidate sees.'
    const minutes = Number(timeLimit)
    if (!Number.isInteger(minutes) || minutes < 5 || minutes > 480) next.timeLimit = 'Use a whole number of minutes from 5 to 480.'
    if (rubric.length === 0) next.rubric = 'Add at least one rubric criterion.'
    rubric.forEach((criterion, index) => {
      if (!criterion.criterion.trim()) next[`c${index}`] = 'Name the criterion.'
      ANCHOR_LEVELS.forEach(level => { if (!criterion.anchors[level].trim()) next[`c${index}-${level}`] = `Describe level ${level}.` })
    })
    setErrors(next)
    if (Object.keys(next).length) {
      window.setTimeout(() => document.querySelector<HTMLElement>('.ui-drawer [aria-invalid="true"]')?.focus(), 0)
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

  const update = (index: number, values: Partial<RubricCriterion>) => setRubric(current => current.map((item, i) => (i === index ? { ...item, ...values } : item)))

  return (
    <form id={formId} className="hra-ws-form" onSubmit={submit} noValidate>
      <Field label="Title" error={errors.title}>
        <Input value={title} maxLength={255} onChange={event => setTitle(event.target.value)} />
      </Field>
      <Field label="Time limit" error={errors.timeLimit} hint="Minutes, 5–480. Submissions are accepted for 5 minutes after the limit.">
        <Input type="number" min={5} max={480} step={5} inputMode="numeric" value={timeLimit} onChange={event => setTimeLimit(event.target.value)} trailing="min" className="hra-num-input" />
      </Field>
      <Field label="Instructions" error={errors.instructions} hint="Hidden from the candidate until they press Start.">
        <Textarea rows={6} maxRows={16} value={instructions} onChange={event => setInstructions(event.target.value)} />
      </Field>

      <fieldset className="hra-rubric-edit">
        <legend className="t-h3">Rubric</legend>
        <p className="subtle">Each criterion has four behavioural anchors (1 = weak, 4 = strong). Link it to a requirement so a score of 3 or 4 counts as demonstrated.</p>
        {errors.rubric && <p className="ui-field-error" role="alert">{errors.rubric}</p>}
        {rubric.map((criterion, index) => (
          <div key={index} className="hra-criterion">
            <div className="hra-criterion-head">
              <Field label={`Criterion ${index + 1}`} error={errors[`c${index}`]} className="grow">
                <Input value={criterion.criterion} maxLength={255} onChange={event => update(index, { criterion: event.target.value })} />
              </Field>
              <IconButton label={`Remove criterion ${index + 1}`} icon={Trash2} size="sm" onClick={() => setRubric(current => current.filter((_, i) => i !== index))} />
            </div>
            <Field label="Evidence for requirement" optional>
              <Select value={criterion.requirementId ?? ''} onChange={event => update(index, { requirementId: event.target.value === '' ? null : Number(event.target.value) })}>
                <option value="">None (general criterion)</option>
                {requirements.map(requirement => <option key={requirement.id} value={requirement.id}>{requirement.description}</option>)}
              </Select>
            </Field>
            <div className="hra-anchor-edit">
              {ANCHOR_LEVELS.map(level => (
                <Field key={level} label={`Level ${level}`} error={errors[`c${index}-${level}`]}>
                  <Textarea rows={2} maxRows={6} value={criterion.anchors[level]} onChange={event => update(index, { anchors: { ...criterion.anchors, [level]: event.target.value } })} />
                </Field>
              ))}
            </div>
          </div>
        ))}
        <Button size="sm" icon={Plus} onClick={() => setRubric(current => [...current, emptyCriterion()])}>Add criterion</Button>
      </fieldset>
      {save.isError && <p className="ui-field-error" role="alert">{errorMessage(save.error)}</p>}
      <div className="hra-drawer-actions">
        <Button variant="ghost" onClick={() => onDone()} disabled={save.isPending}>Cancel</Button>
        <Button type="submit" variant="primary" loading={save.isPending}>Save work sample</Button>
      </div>
    </form>
  )
}

/** Kept for callers that edit a rubric elsewhere. */
export function RubricEditor({ rubric, onChange, requirements }: { rubric: RubricCriterion[]; onChange: (rubric: RubricCriterion[]) => void; requirements: Requirement[]; errors?: Record<string, string> }) {
  return (
    <div className="hra-rubric-edit">
      {rubric.map((criterion, index) => (
        <div key={index} className="hra-criterion">
          <Field label={`Criterion ${index + 1}`}><Input value={criterion.criterion} onChange={event => onChange(rubric.map((c, i) => (i === index ? { ...c, criterion: event.target.value } : c)))} /></Field>
          <Field label="Evidence for requirement" optional>
            <Select value={criterion.requirementId ?? ''} onChange={event => onChange(rubric.map((c, i) => (i === index ? { ...c, requirementId: event.target.value === '' ? null : Number(event.target.value) } : c)))}>
              <option value="">None (general criterion)</option>
              {requirements.map(requirement => <option key={requirement.id} value={requirement.id}>{requirement.description}</option>)}
            </Select>
          </Field>
        </div>
      ))}
    </div>
  )
}

type AssignmentView = 'SUBMITTED' | 'STARTED' | 'ASSIGNED' | 'SCORED' | 'ALL'

/** Assignments across the job (default: submitted, i.e. waiting for a score). Scoring happens on the applicant page. */
function AssignmentsOverview({ jobId }: { jobId: number }) {
  const [status, setStatus] = useState<AssignmentView>('SUBMITTED')
  const assignments = useQuery({
    queryKey: qk.assignments(jobId, status),
    queryFn: ({ signal }) => workSamplesApi.assignments(jobId, status === 'ALL' ? undefined : (status as AssignmentStatus), signal),
  })
  const columns: Column<WorkSampleAssignment>[] = [
    { key: 'app', header: 'Application', primary: true, cell: a => <Link className="hra-row-link" to={`/jobs/${jobId}/applicants/${a.applicationId}?tab=work-samples`}>Application #A-{a.applicationId}</Link> },
    { key: 'ws', header: 'Work sample', cell: a => a.workSampleTitle },
    { key: 'status', header: 'Status', width: '128px', cell: a => <WorkSampleStatusBadge status={a.status} /> },
    { key: 'due', header: 'Start by', width: '160px', hideWhenStacked: true, cell: a => (a.dueAt ? formatDateTime(a.dueAt) : <span className="subtle">No limit</span>) },
    { key: 'submitted', header: 'Submitted', width: '120px', hideWhenStacked: true, cell: a => (a.submittedAt ? <time dateTime={a.submittedAt} title={formatDateTime(a.submittedAt)}>{relativeTime(a.submittedAt)}</time> : <span className="subtle">—</span>) },
    { key: 'score', header: 'Score', width: '88px', numeric: true, cell: a => (a.totalScore != null ? `${a.totalScore.toFixed(2)} / 4` : '—') },
  ]
  const views: { value: AssignmentView; label: string }[] = [
    { value: 'SUBMITTED', label: 'To score' },
    { value: 'STARTED', label: 'In progress' },
    { value: 'ASSIGNED', label: 'Not started' },
    { value: 'SCORED', label: 'Scored' },
    { value: 'ALL', label: 'All' },
  ]
  return (
    <Section title="Assignments" description="Open an application to read the submission and score it against the rubric.">
      {assignments.isError ? (
        <QueryError error={assignments.error} title="Assignments could not be loaded" onRetry={() => void assignments.refetch()} compact />
      ) : (
        <DataTable<WorkSampleAssignment>
          caption={`Work-sample assignments: ${views.find(v => v.value === status)?.label.toLowerCase()}`}
          columns={columns}
          rows={assignments.data ?? []}
          rowKey={a => a.id}
          loading={assignments.isPending}
          loadingRows={3}
          density="compact"
          toolbar={<SegmentedControl<AssignmentView> label="Assignment status" size="sm" value={status} onChange={setStatus} options={views} />}
          empty={<EmptyState compact icon={ListChecks} title={status === 'SUBMITTED' ? 'Nothing waiting for a score' : `No ${views.find(v => v.value === status)?.label.toLowerCase()} assignments`} description={status === 'SUBMITTED' ? 'Submitted work samples appear here until someone scores them.' : undefined} />}
        />
      )}
    </Section>
  )
}
