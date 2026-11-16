import { FormEvent, useEffect, useId, useMemo, useState } from 'react'
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
  Button, DataTable, Drawer, EmptyState, Field, IconButton, Input, LoadingRegion, Menu, ReqMarker, Section, SegmentedControl, Select, Skeleton,
  Textarea, Tooltip, useToast, type Column, type MenuItem,
} from '../../design/components'
import { formatFileSize, formatNumber, plural, relativeTime } from '../../design/lib/format'
import { Archive, ArrowRight, ChevronDown, Copy, Download, Ellipsis, FileText, Icon, ListChecks, Pencil, Plus, Trash2, Undo2, X } from '../../design/icons'
import { formatDateTime, humanize } from '../../lib/format'
import { useDiscardGuard } from '../../lib/leaveGuard'
import { canCancelAssignment, useCancelAssignment, useSubmissionDownload } from '../workSamples/assignmentActions'

/** Server limits (WorkSampleUpdateRequest / WorkSampleService): 1–30 criteria, criterion ≤ 500, anchors ≤ 1000, instructions ≤ 20 000. */
const MAX_CRITERIA = 30
const MAX_CRITERION = 500
const MAX_ANCHOR = 1000
const MAX_INSTRUCTIONS = 20_000

/**
 * Work samples for a job: generate (DRAFT) or write one by hand, edit in a drawer, activate (freezes it), archive,
 * move back to draft while nobody was assigned it, duplicate, and delete a draft. Plus the assignments.
 */
export function WorkSamplesTab({ job }: { job: JobStaff }) {
  const { can } = useAuth()
  const canManage = can('workSamples.manage') && job.status !== 'CANCELLED'
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const samples = useQuery({ queryKey: qk.workSamples(job.id), queryFn: ({ signal }) => workSamplesApi.list(job.id, signal) })
  const [editing, setEditing] = useState<EditTarget | null>(null)
  const [expanded, setExpanded] = useState<number | null>(null)
  const [statusChange, setStatusChange] = useState<{ sample: WorkSample; status: WorkSampleStatus } | null>(null)
  const [deleting, setDeleting] = useState<WorkSample | null>(null)

  const replace = (updated: WorkSample) =>
    queryClient.setQueryData<WorkSample[]>(qk.workSamples(job.id), current => current?.map(item => (item.id === updated.id ? { ...item, ...updated } : item)))
  const append = (created: WorkSample) => queryClient.setQueryData<WorkSample[]>(qk.workSamples(job.id), current => [...(current ?? []), created])

  const setStatus = useMutation({
    mutationFn: ({ sample, status }: { sample: WorkSample; status: WorkSampleStatus }) => workSamplesApi.setStatus(job.id, sample.id, status),
    onSuccess: updated => {
      replace(updated)
      setStatusChange(null)
      toast({
        tone: 'success',
        title: updated.status === 'DRAFT' ? `“${updated.title}” is a draft again` : `“${updated.title}” is ${humanize(updated.status).toLowerCase()}`,
        body: updated.status === 'ACTIVE' ? 'Assign it from a shortlisted applicant’s page.' : updated.status === 'DRAFT' ? 'Edit it, then activate it again.' : undefined,
      })
    },
  })

  const remove = useMutation({
    mutationFn: (sample: WorkSample) => workSamplesApi.remove(job.id, sample.id),
    onSuccess: (_, sample) => {
      queryClient.setQueryData<WorkSample[]>(qk.workSamples(job.id), current => current?.filter(item => item.id !== sample.id))
      setDeleting(null)
      if (expanded === sample.id) setExpanded(null)
      toast({ tone: 'success', title: `Draft “${sample.title}” deleted`, body: 'Recorded in the audit log.' })
    },
  })

  const duplicate = useMutation({
    mutationFn: (sample: WorkSample) => workSamplesApi.duplicate(job.id, sample.id),
    onSuccess: copy => {
      append(copy)
      setExpanded(copy.id)
      toast({ tone: 'success', title: `Copied as “${copy.title}”`, body: 'The copy is a draft: edit it, then activate it.', action: { label: 'Edit', onClick: () => setEditing({ sample: copy }) } })
    },
    onError: (cause, sample) => toast({ tone: 'danger', title: 'The work sample could not be copied', body: errorMessage(cause), action: { label: 'Try again', onClick: () => duplicate.mutate(sample) } }),
  })

  const generate = useMutation({
    mutationFn: () => workSamplesApi.generate(job.id),
    onSuccess: sample => {
      append(sample)
      setExpanded(sample.id)
      setEditing({ sample })
    },
    onError: cause => toast({ tone: 'danger', title: 'The work sample could not be generated', body: errorMessage(cause), action: { label: 'Write one yourself', onClick: () => setEditing({}) } }),
  })

  const askStatus = (sample: WorkSample, status: WorkSampleStatus) => { setStatus.reset(); setStatusChange({ sample, status }) }
  const askDelete = (sample: WorkSample) => { remove.reset(); setDeleting(sample) }

  const noRequirements = job.requirements.length === 0
  const hasSamples = Boolean(samples.data?.length)
  const writeButton = canManage && (
    <Button icon={Pencil} variant="secondary" onClick={() => setEditing({})}>Write one yourself</Button>
  )
  // A disabled button cannot show a tooltip (no hover or focus), so the reason is visible text instead.
  const generateButton = canManage && (noRequirements ? (
    <span className="hra-disabled-reason">
      <Button icon={Plus} variant="secondary" disabled>Generate from requirements</Button>
      <span className="subtle">Add requirements to the job first.</span>
    </span>
  ) : (
    <Tooltip content="Drafts a timed task and a four-level rubric from the must-haves." labelOnly>
      <Button icon={Plus} variant={hasSamples ? 'secondary' : 'primary'} loading={generate.isPending} onClick={() => generate.mutate()}>
        {generate.isPending ? 'Generating…' : 'Generate from requirements'}
      </Button>
    </Tooltip>
  ))
  const headerActions = canManage ? <span className="cluster">{writeButton}{generateButton}</span> : undefined

  const rowMenu = (sample: WorkSample): MenuItem[] => {
    const unassigned = (sample.assignmentCount ?? 0) === 0
    const items: MenuItem[] = []
    if (sample.status === 'DRAFT') items.push({ label: 'Edit', icon: Pencil, onSelect: () => setEditing({ sample }) })
    items.push({ label: 'Duplicate as draft', icon: Copy, disabled: duplicate.isPending, onSelect: () => duplicate.mutate(sample) })
    if (sample.status !== 'DRAFT' && unassigned) items.push({ label: 'Move back to draft', icon: Undo2, onSelect: () => askStatus(sample, 'DRAFT') })
    if (sample.status !== 'ARCHIVED') items.push({ label: 'Archive', icon: Archive, onSelect: () => askStatus(sample, 'ARCHIVED') })
    if (sample.status !== 'ARCHIVED') items.push({ label: sample.status === 'DRAFT' ? 'Delete draft…' : 'Delete…', icon: Trash2, danger: true, separated: true, onSelect: () => askDelete(sample) })
    return items
  }

  const del = deleting
  const deletable = del != null && del.status === 'DRAFT' && (del.assignmentCount ?? 0) === 0

  return (
    <div className="hra-tab-stack">
      <Section
        title="Work samples"
        description="A short timed task scored with a four-level rubric. A criterion linked to a requirement and scored 3 or 4 marks that requirement as demonstrated."
        actions={hasSamples ? headerActions : undefined}
      >
        {samples.isPending ? (
          <LoadingRegion label="Loading work samples"><div className="hra-list-skel">{[0, 1].map(i => <Skeleton key={i} width="100%" height={44} />)}</div></LoadingRegion>
        ) : samples.isError ? (
          <QueryError error={samples.error} title="Work samples could not be loaded" onRetry={() => void samples.refetch()} compact />
        ) : samples.data.length === 0 ? (
          <EmptyState
            icon={FileText}
            title="No work samples yet"
            description={canManage
              ? 'Generate one from the job’s requirements or write your own, review the rubric, then activate it so shortlisted applicants can be assigned the same task.'
              : job.status === 'CANCELLED' ? 'This job was cancelled, so it cannot get work samples.' : 'An HR manager can create one from the job’s requirements.'}
            action={canManage ? <span className="cluster hra-empty-actions">{generateButton}{writeButton}</span> : undefined}
          />
        ) : (
          <ul className="hra-ws-list">
            {samples.data.map(sample => {
              const isOpen = expanded === sample.id
              const assigned = sample.assignmentCount ?? 0
              return (
                <li key={sample.id} className="hra-ws-item" data-open={isOpen || undefined}>
                  <div className="hra-ws-row">
                    <button type="button" className="hra-ws-toggle" aria-expanded={isOpen} aria-controls={`ws-${sample.id}`} onClick={() => setExpanded(isOpen ? null : sample.id)}>
                      <Icon icon={ChevronDown} size={16} className="hra-chevron" />
                      <span className="hra-ws-title" title={sample.title}>{sample.title}</span>
                      <span className="hra-ws-meta numeric">
                        {sample.timeLimitMinutes} min · {plural(sample.rubric.length, 'criterion', 'criteria')}{assigned > 0 ? ` · ${formatNumber(assigned)} assigned` : ''} · updated {relativeTime(sample.updatedAt)}
                      </span>
                    </button>
                    <WorkSampleStatusBadge status={sample.status} />
                    {canManage && (
                      <span className="hra-ws-actions">
                        {sample.status === 'DRAFT' && <IconButton label={`Edit ${sample.title}`} icon={Pencil} size="sm" onClick={() => setEditing({ sample })} />}
                        {sample.status !== 'ACTIVE' && <Button size="sm" variant={sample.status === 'DRAFT' ? 'primary' : 'secondary'} onClick={() => askStatus(sample, 'ACTIVE')}>Activate</Button>}
                        <Menu
                          label={`More actions for ${sample.title}`}
                          items={rowMenu(sample)}
                          trigger={props => <IconButton {...props} label={`More actions for ${sample.title}`} icon={Ellipsis} size="sm" noTooltip />}
                        />
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
        target={editing}
        requirements={job.requirements}
        onDone={(saved, created) => {
          setEditing(null)
          if (!saved) return
          if (created) {
            append(saved)
            setExpanded(saved.id)
          }
          if (saved.status === 'DRAFT' && canManage) {
            toast({ tone: 'success', title: created ? 'Work sample created' : 'Work sample saved', body: 'Activate it when the rubric reads right.', action: { label: 'Activate', onClick: () => askStatus(saved, 'ACTIVE') } })
          }
        }}
      />

      <ActionDialog
        open={statusChange != null}
        size="sm"
        title={statusChange?.status === 'ACTIVE' ? `Activate “${statusChange.sample.title}”?` : statusChange?.status === 'DRAFT' ? `Move “${statusChange.sample.title}” back to draft?` : `Archive “${statusChange?.sample.title}”?`}
        confirmLabel={statusChange?.status === 'ACTIVE' ? 'Activate' : statusChange?.status === 'DRAFT' ? 'Move to draft' : 'Archive'}
        busy={setStatus.isPending}
        error={setStatus.error}
        onCancel={() => setStatusChange(null)}
        onConfirm={() => { if (statusChange) setStatus.mutate(statusChange) }}
      >
        <p>
          {statusChange?.status === 'ACTIVE'
            ? 'It can then be assigned to shortlisted applicants. It can no longer be edited, so every candidate gets the same task.'
            : statusChange?.status === 'DRAFT'
              ? 'Nobody has been assigned it yet, so it can be edited again. It cannot be assigned until you activate it.'
              : 'It can no longer be assigned. Existing assignments, submissions and scores are kept. You can activate it again later.'}
        </p>
      </ActionDialog>

      <ActionDialog
        open={del != null}
        size="sm"
        tone={deletable ? 'danger' : 'primary'}
        title={deletable ? `Delete the draft “${del?.title}”?` : `“${del?.title}” cannot be deleted`}
        confirmLabel={deletable ? 'Delete draft' : 'Archive instead'}
        cancelLabel={deletable ? 'Keep draft' : 'Cancel'}
        busy={remove.isPending || setStatus.isPending}
        error={remove.error ?? setStatus.error}
        onCancel={() => setDeleting(null)}
        onConfirm={() => {
          if (!del) return
          if (deletable) remove.mutate(del)
          else setStatus.mutate({ sample: del, status: 'ARCHIVED' }, { onSuccess: () => setDeleting(null) })
        }}
      >
        <p>
          {deletable
            ? 'The draft and its rubric are removed permanently. Nobody has been assigned it. This is recorded in the audit log.'
            : `Only drafts that were never assigned can be deleted. ${(del?.assignmentCount ?? 0) > 0 ? `It has ${plural(del?.assignmentCount ?? 0, 'assignment')}, and their` : 'Its'} submissions and scores belong to the hiring record. Archive it instead: it can no longer be assigned, and the history is kept.`}
        </p>
      </ActionDialog>
    </div>
  )
}

type EditTarget = { sample?: WorkSample }

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

function WorkSampleEditor({ jobId, target, requirements, onDone }: {
  jobId: number
  target: EditTarget | null
  requirements: Requirement[]
  onDone: (saved?: WorkSample, created?: boolean) => void
}) {
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const guard = useDiscardGuard(dirty, () => { setDirty(false); onDone() }, 'changes to this work sample')
  const sample = target?.sample
  return (
    <>
      <Drawer
        open={target != null}
        onClose={guard.request}
        busy={busy}
        size="lg"
        title={sample ? `Edit “${sample.title}”` : 'New work sample'}
        description={sample ? 'Drafts can be edited until they are activated.' : 'Saved as a draft. Activate it when the rubric reads right.'}
      >
        {target && (
          <WorkSampleForm
            key={sample?.id ?? 'new'}
            jobId={jobId}
            sample={sample}
            requirements={requirements}
            onDirtyChange={setDirty}
            onBusyChange={setBusy}
            onCancel={guard.request}
            onSaved={(saved, created) => { setDirty(false); onDone(saved, created) }}
          />
        )}
      </Drawer>
      {guard.dialog}
    </>
  )
}

function WorkSampleForm({ jobId, sample, requirements, onDirtyChange, onBusyChange, onCancel, onSaved }: {
  jobId: number
  sample?: WorkSample
  requirements: Requirement[]
  onDirtyChange: (dirty: boolean) => void
  onBusyChange: (busy: boolean) => void
  onCancel: () => void
  onSaved: (saved: WorkSample, created: boolean) => void
}) {
  const queryClient = useQueryClient()
  const formId = useId()
  const initial = useMemo(() => ({
    title: sample?.title ?? '',
    instructions: sample?.instructions ?? '',
    timeLimit: String(sample?.timeLimitMinutes ?? 30),
    rubric: sample ? sample.rubric.map(criterion => ({ ...criterion, anchors: { ...criterion.anchors } })) : [emptyCriterion()],
  }), [sample])
  const [title, setTitle] = useState(initial.title)
  const [instructions, setInstructions] = useState(initial.instructions)
  const [timeLimit, setTimeLimit] = useState(initial.timeLimit)
  const [rubric, setRubric] = useState<RubricCriterion[]>(initial.rubric)
  const [errors, setErrors] = useState<Record<string, string>>({})

  const dirty = title !== initial.title || instructions !== initial.instructions || timeLimit !== initial.timeLimit
    || JSON.stringify(rubric) !== JSON.stringify(initial.rubric)
  useEffect(() => { onDirtyChange(dirty) }, [dirty, onDirtyChange])

  const save = useMutation({
    mutationFn: (body: WorkSampleInput) => (sample ? workSamplesApi.update(jobId, sample.id, body) : workSamplesApi.create(jobId, body)),
    onSuccess: saved => {
      if (sample) queryClient.setQueryData<WorkSample[]>(qk.workSamples(jobId), current => current?.map(item => (item.id === saved.id ? { ...item, ...saved } : item)))
      onSaved(saved, !sample)
    },
  })
  useEffect(() => { onBusyChange(save.isPending) }, [save.isPending, onBusyChange])

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
      else if (criterion.criterion.trim().length > MAX_CRITERION) next[`c${index}`] = `Use ${MAX_CRITERION} characters or fewer.`
      ANCHOR_LEVELS.forEach(level => {
        const text = (criterion.anchors[level] ?? '').trim()
        if (!text) next[`c${index}-${level}`] = `Describe level ${level}.`
        else if (text.length > MAX_ANCHOR) next[`c${index}-${level}`] = `Use ${MAX_ANCHOR} characters or fewer.`
      })
    })
    if (rubric.length > MAX_CRITERIA) next.rubric = `Use at most ${MAX_CRITERIA} criteria.`
    if (instructions.trim().length > MAX_INSTRUCTIONS) next.instructions = `Keep the instructions under ${formatNumber(MAX_INSTRUCTIONS)} characters.`
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
        anchors: { '1': (criterion.anchors['1'] ?? '').trim(), '2': (criterion.anchors['2'] ?? '').trim(), '3': (criterion.anchors['3'] ?? '').trim(), '4': (criterion.anchors['4'] ?? '').trim() },
      })),
    })
  }

  const update = (index: number, values: Partial<RubricCriterion>) => setRubric(current => current.map((item, i) => (i === index ? { ...item, ...values } : item)))

  return (
    <form id={formId} className="hra-ws-form" onSubmit={submit} noValidate>
      <Field label="Title" error={errors.title}>
        <Input value={title} maxLength={255} autoFocus={!sample} placeholder={sample ? undefined : 'For example: Design a rate limiter'} onChange={event => setTitle(event.target.value)} />
      </Field>
      <Field label="Time limit" error={errors.timeLimit} hint="Minutes, 5–480. Submissions are accepted for 5 minutes after the limit.">
        <Input type="number" min={5} max={480} step={5} inputMode="numeric" value={timeLimit} onChange={event => setTimeLimit(event.target.value)} trailing="min" className="hra-num-input" />
      </Field>
      <Field label="Instructions" error={errors.instructions} hint="Hidden from the candidate until they press Start.">
        <Textarea rows={6} maxRows={16} maxLength={MAX_INSTRUCTIONS} value={instructions} onChange={event => setInstructions(event.target.value)} />
      </Field>

      <fieldset className="hra-rubric-edit">
        <legend className="t-h3">Rubric</legend>
        <p className="subtle">Each criterion has four behavioural anchors (1 = weak, 4 = strong). Link it to a requirement so a score of 3 or 4 counts as demonstrated.</p>
        {errors.rubric && <p className="ui-field-error" role="alert">{errors.rubric}</p>}
        {rubric.map((criterion, index) => (
          <div key={index} className="hra-criterion">
            <div className="hra-criterion-head">
              <Field label={`Criterion ${index + 1}`} error={errors[`c${index}`]} className="grow">
                <Input value={criterion.criterion} maxLength={MAX_CRITERION} onChange={event => update(index, { criterion: event.target.value })} />
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
                  <Textarea rows={2} maxRows={6} maxLength={MAX_ANCHOR} value={criterion.anchors[level] ?? ''} onChange={event => update(index, { anchors: { ...criterion.anchors, [level]: event.target.value } })} />
                </Field>
              ))}
            </div>
          </div>
        ))}
        {rubric.length < MAX_CRITERIA
          ? <Button size="sm" icon={Plus} onClick={() => setRubric(current => [...current, emptyCriterion()])}>Add criterion</Button>
          : <p className="subtle">{MAX_CRITERIA} criteria is the maximum.</p>}
      </fieldset>
      {save.isError && <p className="ui-field-error" role="alert">{errorMessage(save.error)}</p>}
      <div className="hra-drawer-actions">
        <Button variant="ghost" onClick={onCancel} disabled={save.isPending}>Cancel</Button>
        <Button type="submit" variant="primary" loading={save.isPending}>{sample ? 'Save work sample' : 'Create draft'}</Button>
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

/**
 * Assignments across the job (default: submitted, i.e. waiting for a score). Scoring happens on the applicant page;
 * submitted files can be downloaded (audited) and assignments cancelled (with a reason) right here.
 */
function AssignmentsOverview({ jobId }: { jobId: number }) {
  const { can } = useAuth()
  const [status, setStatus] = useState<AssignmentView>('SUBMITTED')
  const assignments = useQuery({
    queryKey: qk.assignments(jobId, status),
    queryFn: ({ signal }) => workSamplesApi.assignments(jobId, status === 'ALL' ? undefined : (status as AssignmentStatus), signal),
  })
  const cancel = useCancelAssignment(jobId)
  const download = useSubmissionDownload()
  const canDownload = can('workSamples.downloadFiles')
  const canCancel = can('workSamples.cancel')
  const rowActions = (a: WorkSampleAssignment): MenuItem[] => {
    const items: MenuItem[] = [{ label: 'Open application', icon: ArrowRight, to: `/jobs/${jobId}/applicants/${a.applicationId}?tab=work-samples` }]
    if (canDownload) {
      for (const file of a.files ?? []) {
        items.push({
          label: `Download ${file.originalName} (${formatFileSize(file.sizeBytes)})`,
          icon: Download,
          disabled: download.busyFileId === file.id,
          onSelect: () => download.start(a.id, file),
          separated: items.length === 1,
        })
      }
    }
    if (canCancel && canCancelAssignment(a)) items.push({ label: 'Cancel assignment…', icon: X, danger: true, separated: true, onSelect: () => cancel.request(a) })
    return items
  }
  const columns: Column<WorkSampleAssignment>[] = [
    { key: 'app', header: 'Application', primary: true, cell: a => <Link className="hra-row-link" to={`/jobs/${jobId}/applicants/${a.applicationId}?tab=work-samples`}>Application #A-{a.applicationId}</Link> },
    { key: 'ws', header: 'Work sample', cell: a => a.workSampleTitle },
    { key: 'status', header: 'Status', width: '128px', cell: a => <WorkSampleStatusBadge status={a.status} /> },
    { key: 'due', header: 'Start by', width: '160px', hideWhenStacked: true, cell: a => (a.dueAt ? formatDateTime(a.dueAt) : <span className="subtle">No limit</span>) },
    { key: 'submitted', header: 'Submitted', width: '120px', hideWhenStacked: true, cell: a => (a.submittedAt ? <time dateTime={a.submittedAt} title={formatDateTime(a.submittedAt)}>{relativeTime(a.submittedAt)}</time> : <span className="subtle">—</span>) },
    { key: 'files', header: 'Files', width: '72px', numeric: true, hideWhenStacked: true, cell: a => ((a.files?.length ?? 0) > 0 ? <span title={(a.files ?? []).map(f => f.originalName).join(', ')}>{a.files!.length}</span> : <span className="subtle">—</span>) },
    { key: 'score', header: 'Score', width: '88px', numeric: true, cell: a => (a.totalScore != null ? `${a.totalScore.toFixed(2)} / 4` : '—') },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      width: '52px',
      align: 'end',
      cell: a => (
        <Menu
          label={`Actions for application #A-${a.applicationId}: ${a.workSampleTitle}`}
          items={rowActions(a)}
          trigger={props => <IconButton {...props} label={`Actions for application #A-${a.applicationId}`} icon={Ellipsis} size="sm" noTooltip />}
        />
      ),
    },
  ]
  const views: { value: AssignmentView; label: string }[] = [
    { value: 'SUBMITTED', label: 'To score' },
    { value: 'STARTED', label: 'In progress' },
    { value: 'ASSIGNED', label: 'Not started' },
    { value: 'SCORED', label: 'Scored' },
    { value: 'ALL', label: 'All' },
  ]
  return (
    <Section title="Assignments" description={`Open an application to read the submission and score it against the rubric. Each row’s “⋯” menu downloads submitted files (audited)${canCancel ? ' or cancels the assignment' : ''}.`}>
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
      {cancel.dialog}
    </Section>
  )
}
