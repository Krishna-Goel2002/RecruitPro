import { FormEvent, useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { errorMessage, isApiError } from '../../api/client'
import { jobsApi } from '../../api/jobs'
import { qk } from '../../api/queryKeys'
import { EXPERIENCE_LEVELS, ExperienceLevel, JOB_TYPES, JobInput, JobStaff, JobType } from '../../api/types'
import { ActionDialog } from '../../components/ActionDialog'
import { QueryError } from '../../components/QueryError'
import { SaveIndicator, type SaveState } from '../../components/SaveIndicator'
import { JobStatusBadge } from '../../components/StatusBadges'
import {
  Alert, Button, EmptyState, Field, Input, LoadingRegion, PageHeader, ReqMarker, Section, Select, Skeleton, Textarea, buttonProps, useToast,
} from '../../design/components'
import { formatDate, formatNumber, plural, relativeTime } from '../../design/lib/format'
import { ArrowLeft, ArrowRight, BriefcaseBusiness, Check, CircleAlert, FileText, Icon } from '../../design/icons'
import { fromLocalInput, humanize, toLocalInput } from '../../lib/format'
import { clearDraft, readDraft, writeDraft } from '../../lib/localDraft'
import { useSearchState } from '../../lib/useSearchState'
import { useAuth } from '../../auth/AuthContext'
import { CompileSkeleton, CompileSuggestions } from './CompileSuggestions'
import { useJobActions } from './jobActions'
import { RequirementsEditor } from './RequirementsEditor'
import { adoptServerIds, draftFrom, gateAllowed, MAX_REQUIREMENTS, requirementChecks, RequirementDraft, sameRequirements, sameRequirementSet, sortByDisplayOrder, toInput, validateDrafts } from './requirementDrafts'
import './jobs.css'
import './wizard.css'

export type FormState = {
  title: string
  description: string
  department: string
  location: string
  jobType: JobType
  experienceLevel: ExperienceLevel
  minimumSalary: string
  maximumSalary: string
  vacancies: string
  deadline: string
}
type Step = 'basics' | 'requirements' | 'review'
const STEPS: { value: Step; label: string }[] = [
  { value: 'basics', label: 'Basics' },
  { value: 'requirements', label: 'Requirements' },
  { value: 'review', label: 'Review' },
]
const EDITABLE = ['DRAFT', 'PUBLISHED', 'ON_HOLD']
/** Device draft of a job not created yet, per signed-in user (a shared computer never offers another user's draft). */
export const newDraftKey = (userId: number | undefined) => `job-new:${userId ?? 'anon'}`
/** Device drafts older than this are dropped instead of offered. */
export const DRAFT_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000

/**
 * A stored device draft, checked field by field: an old or corrupted entry (another app version, a hand-edited
 * storage value) can never crash the form; unknown values fall back to the defaults. Null when nothing usable is left.
 */
export function readNewJobDraft(key: string, now = Date.now()): { value: FormState; savedAt: number } | null {
  const stored = readDraft<Partial<Record<keyof FormState, unknown>>>(key)
  if (!stored || typeof stored.savedAt !== 'number' || now - stored.savedAt > DRAFT_MAX_AGE_MS) {
    if (stored) clearDraft(key)
    return null
  }
  const raw = stored.value && typeof stored.value === 'object' ? stored.value : {}
  const base = initialForm()
  const text = (k: keyof FormState) => (typeof raw[k] === 'string' ? (raw[k] as string) : base[k] as string)
  const value: FormState = {
    title: text('title'),
    description: text('description'),
    department: text('department'),
    location: text('location'),
    jobType: (JOB_TYPES as readonly string[]).includes(raw.jobType as string) ? (raw.jobType as JobType) : base.jobType,
    experienceLevel: (EXPERIENCE_LEVELS as readonly string[]).includes(raw.experienceLevel as string) ? (raw.experienceLevel as ExperienceLevel) : base.experienceLevel,
    minimumSalary: text('minimumSalary').replace(/[^\d]/g, ''),
    maximumSalary: text('maximumSalary').replace(/[^\d]/g, ''),
    vacancies: text('vacancies'),
    deadline: text('deadline'),
  }
  if (JSON.stringify(value) === JSON.stringify(base)) {
    clearDraft(key)
    return null
  }
  return { value, savedAt: stored.savedAt }
}

export function initialForm(job?: JobStaff): FormState {
  return {
    title: job?.title ?? '',
    description: job?.description ?? '',
    department: job?.department ?? '',
    location: job?.location ?? '',
    jobType: job?.jobType ?? 'FULL_TIME',
    experienceLevel: job?.experienceLevel ?? 'MID',
    minimumSalary: job?.minimumSalary == null ? '' : String(job.minimumSalary),
    maximumSalary: job?.maximumSalary == null ? '' : String(job.maximumSalary),
    vacancies: job ? String(job.vacancies) : '1',
    deadline: toLocalInput(job?.deadline),
  }
}

export function toBody(form: FormState): JobInput {
  return {
    title: form.title.trim(),
    description: form.description.trim(),
    department: form.department.trim(),
    location: form.location.trim(),
    jobType: form.jobType,
    experienceLevel: form.experienceLevel,
    minimumSalary: form.minimumSalary === '' ? null : Number(form.minimumSalary),
    maximumSalary: form.maximumSalary === '' ? null : Number(form.maximumSalary),
    vacancies: Number(form.vacancies),
    deadline: fromLocalInput(form.deadline),
  }
}

type Errors = Partial<Record<keyof FormState, string>>

export function validateBasics(form: FormState, original?: JobStaff, now = Date.now()): Errors {
  const next: Errors = {}
  if (!form.title.trim()) next.title = 'Enter a job title.'
  if (!form.department.trim()) next.department = 'Enter a department.'
  if (!form.location.trim()) next.location = 'Enter a location, for example “Bengaluru (hybrid)”.'
  if (!form.description.trim()) next.description = 'Describe the role: what the person will do and what they must be able to do.'
  const vacancies = Number(form.vacancies)
  if (!Number.isInteger(vacancies) || vacancies < 1) next.vacancies = 'Enter at least one opening.'
  const min = form.minimumSalary === '' ? null : Number(form.minimumSalary)
  const max = form.maximumSalary === '' ? null : Number(form.maximumSalary)
  if (min != null && (Number.isNaN(min) || min < 0)) next.minimumSalary = 'Enter a positive amount.'
  if (max != null && (Number.isNaN(max) || max < 0)) next.maximumSalary = 'Enter a positive amount.'
  if (min != null && max != null && min > max) next.maximumSalary = 'The maximum must be at least the minimum.'
  if (form.deadline) {
    const deadline = fromLocalInput(form.deadline)
    const unchanged = original?.deadline && deadline && Math.abs(new Date(original.deadline).getTime() - new Date(deadline).getTime()) < 60_000
    if (!deadline) next.deadline = 'Enter a valid date and time.'
    else if (!unchanged && new Date(deadline).getTime() <= now) next.deadline = 'The deadline must be in the future.'
  }
  return next
}

/** /jobs/new and /jobs/:jobId/edit (?step=basics|requirements|review). */
export function JobFormPage() {
  const { jobId } = useParams()
  const id = jobId ? Number(jobId) : null
  const job = useQuery({ queryKey: qk.job(id ?? 0), queryFn: ({ signal }) => jobsApi.get(id as number, signal), enabled: id != null && id > 0 })

  if (id != null && (!Number.isInteger(id) || id <= 0 || isApiError(job.error, 404))) {
    return <EmptyState icon={BriefcaseBusiness} title="Job not found" description="It may have been deleted, or it belongs to another organisation." action={<Link {...buttonProps('secondary')} to="/jobs">Back to jobs</Link>} />
  }
  if (id != null && job.isPending) {
    return (
      <LoadingRegion label="Loading job" className="hra-wiz-skel">
        <Skeleton width={160} height={10} />
        <Skeleton width="40%" height={22} />
        <Skeleton width="60%" height={14} />
        {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} width="100%" height={32} />)}
      </LoadingRegion>
    )
  }
  if (id != null && job.isError) return <QueryError error={job.error} title="This job could not be loaded" onRetry={() => void job.refetch()} retrying={job.isFetching} />
  if (job.data && !EDITABLE.includes(job.data.status)) {
    return (
      <div data-width="form">
        <PageHeader title={job.data.title} breadcrumb={[{ label: 'Jobs', to: '/jobs' }, { label: job.data.title, to: `/jobs/${job.data.id}` }, { label: 'Edit' }]} status={<JobStatusBadge status={job.data.status} />} />
        <EmptyState icon={FileText} title="This job can no longer be edited" description="Closed and cancelled jobs are kept as they were, for the audit trail." action={<Link {...buttonProps('secondary')} to={`/jobs/${job.data.id}`}>Back to job</Link>} />
      </div>
    )
  }
  return <JobWizard key={job.data?.id ?? 'new'} job={job.data} />
}

function JobWizard({ job }: { job?: JobStaff }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const search = useSearchState()
  const actions = useJobActions()
  const { user } = useAuth()
  const draftKey = newDraftKey(user?.id)
  const mode: 'new' | 'draft' | 'live' = !job ? 'new' : job.status === 'DRAFT' ? 'draft' : 'live'

  const requestedStep = search.get('step') as Step
  const step: Step = !job ? 'basics' : STEPS.some(s => s.value === requestedStep) ? requestedStep : 'basics'
  const goTo = useCallback((next: Step) => {
    search.set({ step: next === 'basics' ? null : next })
    window.scrollTo({ top: 0 })
  }, [search])

  const [form, setForm] = useState<FormState>(() => initialForm(job))
  const [drafts, setDrafts] = useState<RequirementDraft[]>(() => sortByDisplayOrder(job?.requirements ?? []).map(draftFrom))
  const [errors, setErrors] = useState<Errors>({})
  const [touched, setTouched] = useState<Set<keyof FormState>>(new Set())
  const [reqErrors, setReqErrors] = useState<Record<string, string>>({})
  const [saveState, setSaveState] = useState<SaveState>({ kind: 'idle' })
  const [confirmLive, setConfirmLive] = useState(false)
  const [openWarnings, setOpenWarnings] = useState(0)

  // What the server has (for dirty checks and autosave).
  const saved = useRef({ form: initialForm(job), requirements: sortByDisplayOrder(job?.requirements ?? []).map(r => toInput(draftFrom(r))) })
  const formDirty = JSON.stringify(form) !== JSON.stringify(saved.current.form)
  const requirementsDirty = !sameRequirements(drafts.map(toInput), saved.current.requirements)
  // A pure reorder only saves the order on the server: no new version, no audit entry, no re-match (integration pass).
  const reorderOnly = requirementsDirty && sameRequirementSet(drafts.map(toInput), saved.current.requirements)
  const rematches = requirementsDirty && !reorderOnly
  const dirty = formDirty || requirementsDirty

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm(current => ({ ...current, [key]: value }))
    if (errors[key]) setErrors(current => ({ ...current, [key]: undefined }))
  }
  const blur = (key: keyof FormState) => {
    setTouched(current => new Set(current).add(key))
    const problem = validateBasics({ ...form }, job)[key]
    setErrors(current => ({ ...current, [key]: problem }))
  }

  // ------------------------------------------------------------------ new job: keep typing on this device
  const [resume, setResume] = useState(() => (!job ? readNewJobDraft(draftKey) : null))
  useEffect(() => {
    if (mode !== 'new' || resume) return
    if (JSON.stringify(form) === JSON.stringify(initialForm())) return
    const timer = window.setTimeout(() => {
      const at = writeDraft(draftKey, form)
      if (at) setSaveState({ kind: 'saved', at, where: 'device' })
    }, 800)
    return () => window.clearTimeout(timer)
  }, [form, mode, resume, draftKey])

  // ------------------------------------------------------------------ saving
  // Saves run one after another: an autosave that fires while the previous one is still in flight waits for it, so
  // two PUTs (or two requirement replaces) never race and an older body can never land after a newer one.
  const saveChain = useRef<Promise<unknown>>(Promise.resolve())
  const persistNow = useCallback(async (opts: { requirements: boolean }): Promise<JobStaff> => {
    let current = job
    const body = toBody(form)
    if (!current) {
      current = await jobsApi.create(body)
    } else if (JSON.stringify(form) !== JSON.stringify(saved.current.form)) {
      current = await jobsApi.update(current.id, body)
    }
    saved.current.form = form
    const inputs = drafts.map(toInput)
    if (opts.requirements && !sameRequirements(inputs, saved.current.requirements)) {
      current = await jobsApi.replaceRequirements(current.id, inputs)
      const withIds = adoptServerIds(drafts, current.requirements ?? [])
      saved.current.requirements = withIds.map(toInput)
      if (withIds !== drafts) {
        const ids = new Map(withIds.map(d => [d.key, d.id]))
        // The user may have kept typing while the save ran: only fill in ids, never overwrite their edits.
        setDrafts(currentDrafts => currentDrafts.map(d => (d.id == null && ids.get(d.key) != null && d.description.trim() === withIds.find(w => w.key === d.key)?.description.trim() ? { ...d, id: ids.get(d.key) } : d)))
      }
    }
    queryClient.setQueryData(qk.job(current.id), current)
    void queryClient.invalidateQueries({ queryKey: qk.jobs })
    return current
  }, [job, form, drafts, queryClient])
  const persist = useCallback((opts: { requirements: boolean }): Promise<JobStaff> => {
    const run = saveChain.current.catch(() => undefined).then(() => persistNow(opts))
    saveChain.current = run
    return run
  }, [persistNow])

  const create = useMutation({
    mutationFn: () => persist({ requirements: false }),
    onSuccess: created => {
      clearDraft(draftKey)
      toast({ tone: 'success', title: 'Draft created', body: 'Saved automatically from now on.', duration: 4000 })
      navigate(`/jobs/${created.id}/edit?step=requirements`, { replace: true, state: { autoCompile: true } })
    },
  })

  // Drafts autosave to the server 1.5 s after the last valid change.
  const autosaveRun = useRef(0)
  useEffect(() => {
    if (mode !== 'draft' || !dirty) return
    const basicsOk = Object.keys(validateBasics(form, job)).length === 0
    const reqsOk = Object.keys(validateDrafts(drafts)).length === 0
    if (!basicsOk || !reqsOk) {
      setSaveState({ kind: 'dirty' })
      return
    }
    setSaveState({ kind: 'dirty' })
    const run = ++autosaveRun.current
    const timer = window.setTimeout(() => {
      setSaveState({ kind: 'saving' })
      persist({ requirements: true })
        .then(() => { if (run === autosaveRun.current) setSaveState({ kind: 'saved', at: Date.now(), where: 'server' }) })
        .catch(cause => {
          if (run === autosaveRun.current) setSaveState({ kind: 'error', message: errorMessage(cause), retry: () => { autosaveRun.current += 1; setForm(f => ({ ...f })) } })
        })
    }, 1500)
    return () => window.clearTimeout(timer)
  }, [form, drafts, mode, dirty, job, persist])

  const saveLive = useMutation({
    mutationFn: () => persist({ requirements: true }),
    onSuccess: savedJob => {
      setConfirmLive(false)
      toast({ tone: 'success', title: 'Job saved', body: rematches ? `Requirements v${savedJob.requirementsVersion ?? ''}: applicants are being re-matched.` : reorderOnly ? 'Requirement order saved.' : undefined })
      if (rematches) void queryClient.invalidateQueries({ queryKey: qk.applicants(savedJob.id) })
      navigate(`/jobs/${savedJob.id}`)
    },
  })

  // Leaving with unsaved changes asks first (live edits, or a draft autosave still pending).
  const unsaved = (mode === 'live' && dirty && !saveLive.isPending && !saveLive.isSuccess) || saveState.kind === 'saving'
    || (mode === 'draft' && dirty && (saveState.kind === 'error' || saveState.kind === 'dirty'))
  // A draft published from the review step: go to the job page.
  const startedAsDraft = useRef(mode === 'draft')
  useEffect(() => {
    if (startedAsDraft.current && job?.status === 'PUBLISHED') navigate(`/jobs/${job.id}`, { replace: true })
  }, [job?.status, job?.id, navigate])
  useEffect(() => {
    if (!unsaved) return
    const onUnload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', onUnload)
    return () => window.removeEventListener('beforeunload', onUnload)
  }, [unsaved])

  // ------------------------------------------------------------------ compiler
  const compile = useMutation({
    // Server limits (CompileRequirementsRequest, integration pass): the job's own limits, title ≤ 255 and description ≤ 50 000.
    mutationFn: () => jobsApi.compileRequirements({ title: form.title.trim().slice(0, 255), description: form.description.trim().slice(0, 50_000), experienceLevel: form.experienceLevel }),
    onSuccess: result => setOpenWarnings(result.warnings.length),
  })
  const compileTried = useRef(false)
  useEffect(() => {
    if (step !== 'requirements' || compileTried.current) return
    compileTried.current = true
    if (drafts.length === 0 && form.description.trim().length >= 20 && form.title.trim()) compile.mutate()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [step])

  function acceptSuggestions(indexes: number[], how: 'append' | 'replace') {
    if (!compile.data) return
    const accepted = indexes.map(index => {
      const draft = draftFrom(compile.data!.requirements[index])
      return { ...draft, knockout: draft.knockout && gateAllowed(draft) }
    })
    if (how === 'replace') setDrafts(accepted.slice(0, MAX_REQUIREMENTS))
    else {
      const existing = new Set(drafts.map(draft => draft.description.trim().toLowerCase()))
      setDrafts([...drafts, ...accepted.filter(draft => !existing.has(draft.description.trim().toLowerCase()))].slice(0, MAX_REQUIREMENTS))
    }
    compile.reset()
    toast({ tone: 'success', title: `${plural(indexes.length, 'requirement')} added`, body: 'Adjust them below; nothing is fixed until you publish.', duration: 4000 })
  }

  // ------------------------------------------------------------------ step navigation
  function continueFromBasics(event?: FormEvent) {
    event?.preventDefault()
    const next = validateBasics(form, job)
    setErrors(next)
    setTouched(new Set(Object.keys(form) as (keyof FormState)[]))
    if (Object.keys(next).length) {
      window.setTimeout(() => document.querySelector<HTMLElement>('.hra-wiz [aria-invalid="true"]')?.focus(), 0)
      return
    }
    if (mode === 'new') create.mutate()
    else goTo('requirements')
  }

  function continueFromRequirements() {
    const next = validateDrafts(drafts)
    setReqErrors(next)
    if (Object.keys(next).length) {
      window.setTimeout(() => document.querySelector<HTMLElement>('.hra-req-list [aria-invalid="true"]')?.focus(), 0)
      return
    }
    goTo('review')
  }

  function submitLive() {
    const basics = validateBasics(form, job)
    const reqs = validateDrafts(drafts)
    setErrors(basics)
    setReqErrors(reqs)
    if (Object.keys(basics).length) return goTo('basics')
    if (Object.keys(reqs).length) return goTo('requirements')
    if (rematches) setConfirmLive(true)
    else saveLive.mutate()
  }

  const title = mode === 'new' ? 'New job' : `Edit ${job!.title}`
  const stepIndex = STEPS.findIndex(s => s.value === step)
  const canJump = (target: Step) => mode !== 'new' || target === 'basics'

  return (
    <div className="hra-wiz" data-width="wide">
      <PageHeader
        documentTitle={title}
        title={mode === 'new' ? 'New job' : job!.title}
        breadcrumb={mode === 'new' ? [{ label: 'Jobs', to: '/jobs' }, { label: 'New job' }] : [{ label: 'Jobs', to: '/jobs' }, { label: job!.title, to: `/jobs/${job!.id}` }, { label: 'Edit' }]}
        status={job ? <JobStatusBadge status={job.status} /> : undefined}
        actions={mode !== 'live' ? <SaveIndicator state={saveState} /> : undefined}
      />

      <nav className="hra-wsteps" aria-label="Job set-up steps">
        <ol>
          {STEPS.map((s, i) => {
            const state = i < stepIndex ? 'done' : i === stepIndex ? 'current' : 'upcoming'
            const content = (
              <>
                <span className="hra-wstep-dot numeric" aria-hidden="true">{state === 'done' ? <Icon icon={Check} size={12} strokeWidth={2} /> : i + 1}</span>
                <span className="hra-wstep-label">{s.label}</span>
              </>
            )
            return (
              <li key={s.value} data-state={state}>
                {canJump(s.value) && state !== 'current' ? (
                  <button type="button" className="hra-wstep" onClick={() => goTo(s.value)}>{content}<span className="sr-only">{state === 'done' ? ' (done)' : ''}</span></button>
                ) : (
                  <span className="hra-wstep" aria-current={state === 'current' ? 'step' : undefined}>{content}</span>
                )}
              </li>
            )
          })}
        </ol>
      </nav>

      {mode === 'live' && (
        <Alert tone="info" className="hra-wiz-alert">
          This job is live. Requirement changes create a new requirements version, are recorded in the audit log and re-match {job?.applicationCount != null ? plural(job.applicationCount, 'applicant') : 'every applicant'}.
        </Alert>
      )}

      {resume && mode === 'new' && (
        <Alert
          tone="info"
          className="hra-wiz-alert"
          title="You have an unfinished job on this device"
          action={
            <span className="cluster">
              <Button size="sm" variant="primary" onClick={() => { setForm(resume.value); setResume(null) }}>Resume</Button>
              <Button size="sm" variant="ghost" onClick={() => { clearDraft(draftKey); setResume(null) }}>Start over</Button>
            </span>
          }
        >
          “{resume.value.title || 'Untitled job'}”, last edited {relativeTime(resume.savedAt)}.
        </Alert>
      )}

      <div className="hra-wiz-body" key={step}>
        {step === 'basics' && (
          <form className="hra-wiz-form" onSubmit={continueFromBasics} noValidate>
            {create.isError && <Alert tone="danger" live className="hra-wiz-alert">{errorMessage(create.error)}</Alert>}
            <Section title="The role" description="What candidates see on the job board.">
              <div className="hra-grid">
                <Field label="Job title" error={touched.has('title') ? errors.title : undefined} className="hra-span-2">
                  <Input value={form.title} maxLength={255} onChange={e => set('title', e.target.value)} onBlur={() => blur('title')} placeholder="e.g. Senior Backend Engineer" autoFocus={mode === 'new' && !resume} />
                </Field>
                <Field label="Department" error={touched.has('department') ? errors.department : undefined}>
                  <Input value={form.department} maxLength={100} onChange={e => set('department', e.target.value)} onBlur={() => blur('department')} placeholder="e.g. Engineering" />
                </Field>
                <Field label="Location" error={touched.has('location') ? errors.location : undefined}>
                  <Input value={form.location} maxLength={255} onChange={e => set('location', e.target.value)} onBlur={() => blur('location')} placeholder="e.g. Bengaluru (hybrid)" />
                </Field>
                <Field label="Employment type">
                  <Select value={form.jobType} onChange={e => set('jobType', e.target.value as JobType)}>
                    {JOB_TYPES.map(value => <option key={value} value={value}>{humanize(value)}</option>)}
                  </Select>
                </Field>
                <Field label="Experience level" hint="Also used to flag excessive experience demands.">
                  <Select value={form.experienceLevel} onChange={e => set('experienceLevel', e.target.value as ExperienceLevel)}>
                    {EXPERIENCE_LEVELS.map(value => <option key={value} value={value}>{humanize(value)}</option>)}
                  </Select>
                </Field>
              </div>
            </Section>
            <Section title="Pay, openings and deadline">
              <div className="hra-grid">
                <MoneyField label="Minimum salary" value={form.minimumSalary} onChange={v => set('minimumSalary', v)} onBlur={() => blur('minimumSalary')} error={touched.has('minimumSalary') ? errors.minimumSalary : undefined} />
                <MoneyField label="Maximum salary" value={form.maximumSalary} onChange={v => set('maximumSalary', v)} onBlur={() => blur('maximumSalary')} error={touched.has('maximumSalary') ? errors.maximumSalary : undefined} />
                <Field label="Openings" error={touched.has('vacancies') ? errors.vacancies : undefined}>
                  <Input type="number" min={1} step={1} inputMode="numeric" value={form.vacancies} onChange={e => set('vacancies', e.target.value)} onBlur={() => blur('vacancies')} className="hra-num-input" />
                </Field>
                <Field label="Application deadline" optional hint="Your local time. A published job closes automatically after it." error={touched.has('deadline') ? errors.deadline : undefined}>
                  <Input type="datetime-local" value={form.deadline} onChange={e => set('deadline', e.target.value)} onBlur={() => blur('deadline')} />
                </Field>
              </div>
            </Section>
            <Section title="Description" description="Duties, and what a good hire must be able to do. Next, we suggest requirements from it.">
              <Field label="Job description" hideLabel error={touched.has('description') ? errors.description : undefined} hint={form.description.trim().length > 0 && form.description.trim().length < 20 ? 'A few more lines help the requirement suggestions.' : undefined}>
                <Textarea rows={8} maxRows={24} value={form.description} onChange={e => set('description', e.target.value)} onBlur={() => blur('description')} placeholder={'About the role\n\nWhat you will do\n\nWhat you need\n\nNice to have'} />
              </Field>
            </Section>
            <div className="hra-wiz-foot">
              <Link {...buttonProps('ghost')} to={job ? `/jobs/${job.id}` : '/jobs'}>Cancel</Link>
              <span className="cluster">
                {mode === 'live' && <Button onClick={submitLive} loading={saveLive.isPending}>Save changes</Button>}
                <Button type="submit" variant={mode === 'live' ? 'secondary' : 'primary'} trailingIcon={ArrowRight} loading={create.isPending}>
                  {mode === 'new' ? 'Continue to requirements' : 'Requirements'}
                </Button>
              </span>
            </div>
          </form>
        )}

        {step === 'requirements' && (
          <div className="hra-wiz-form">
            <Section
              title="Requirements"
              description="Applicants are ranked on the evidence they show for each requirement. Must-haves count double; a gate holds an applicant out of the ranked group until it is verified. Nobody is rejected automatically."
              actions={!compile.data && !compile.isPending ? <Button size="sm" icon={FileText} onClick={() => compile.mutate()} disabled={form.description.trim().length < 20}>Suggest from description</Button> : undefined}
            >
              {compile.isPending && <CompileSkeleton />}
              {compile.isError && (
                <Alert tone="warning" className="hra-wiz-alert" action={<Button size="sm" onClick={() => compile.mutate()}>Try again</Button>}>
                  {isApiError(compile.error, 503) ? 'Suggestions are unavailable right now. Add requirements by hand; you can try again later.' : errorMessage(compile.error)}
                </Alert>
              )}
              {compile.data && <CompileSuggestions result={compile.data} hasExisting={drafts.length > 0} onAccept={acceptSuggestions} onDismiss={() => compile.reset()} />}
              <RequirementsEditor drafts={drafts} onChange={next => { setDrafts(next); if (Object.keys(reqErrors).length) setReqErrors(validateDrafts(next)) }} errors={reqErrors} />
            </Section>
            <div className="hra-wiz-foot">
              <Button variant="ghost" icon={ArrowLeft} onClick={() => goTo('basics')}>Basics</Button>
              <span className="cluster">
                {mode === 'live' && <Button onClick={submitLive} loading={saveLive.isPending}>Save changes</Button>}
                <Button variant={mode === 'live' ? 'secondary' : 'primary'} trailingIcon={ArrowRight} onClick={continueFromRequirements}>Review</Button>
              </span>
            </div>
          </div>
        )}

        {step === 'review' && job && (
          <ReviewStep
            job={job}
            form={form}
            drafts={drafts}
            openWarnings={openWarnings}
            onFix={goTo}
            footer={
              <div className="hra-wiz-foot">
                <Button variant="ghost" icon={ArrowLeft} onClick={() => goTo('requirements')}>Requirements</Button>
                <span className="cluster">
                  {mode === 'live' ? (
                    <Button variant="primary" onClick={submitLive} loading={saveLive.isPending} disabled={!dirty}>{dirty ? 'Save changes' : 'No changes to save'}</Button>
                  ) : (
                    <>
                      <Link {...buttonProps('secondary')} to={`/jobs/${job.id}`}>Keep as draft</Link>
                      <Button
                        variant="primary"
                        onClick={async () => {
                          // Same checks as the other steps: never send an invalid job just to publish it.
                          const basics = validateBasics(form, job)
                          const reqs = validateDrafts(drafts)
                          setErrors(basics)
                          setReqErrors(reqs)
                          setTouched(new Set(Object.keys(form) as (keyof FormState)[]))
                          if (Object.keys(basics).length) return goTo('basics')
                          if (Object.keys(reqs).length) return goTo('requirements')
                          try {
                            const latest = await persist({ requirements: true })
                            actions.run(latest, 'PUBLISH')
                          } catch (cause) {
                            toast({ tone: 'danger', title: 'Could not save before publishing', body: errorMessage(cause) })
                          }
                        }}
                      >
                        Publish
                      </Button>
                    </>
                  )}
                </span>
              </div>
            }
          />
        )}
      </div>

      {mode === 'live' && dirty && (
        <div className="hra-sticky-bar hra-wiz-live" role="region" aria-label="Unsaved changes">
          <p>
            <strong>Unsaved changes</strong>
            <span className="subtle"> · {[formDirty ? 'role details' : null, rematches ? 'requirements (re-matches applicants)' : reorderOnly ? 'requirement order' : null].filter(Boolean).join(' and ')}</span>
          </p>
          <span className="cluster">
            <Button variant="ghost" onClick={() => { setForm(saved.current.form); setDrafts(sortByDisplayOrder(job?.requirements ?? []).map(draftFrom)) }}>Discard</Button>
            <Button variant="primary" onClick={submitLive} loading={saveLive.isPending}>Save changes</Button>
          </span>
        </div>
      )}

      <ActionDialog
        open={confirmLive}
        size="sm"
        title="Change the requirements of a live job?"
        confirmLabel="Save and re-match"
        busy={saveLive.isPending}
        error={saveLive.error}
        onCancel={() => setConfirmLive(false)}
        onConfirm={() => saveLive.mutate()}
      >
        <p>
          Saving creates requirements version {(job?.requirementsVersion ?? 1) + 1}, is recorded in the audit log, and re-matches
          {job?.applicationCount != null ? ` ${plural(job.applicationCount, 'applicant')}` : ' every applicant'}. Rankings may shift for a few seconds while matching runs.
        </p>
      </ActionDialog>

      {actions.dialog}
    </div>
  )
}

/** ₹ amount: digits while editing, en-IN grouping when not focused. */
function MoneyField({ label, value, onChange, onBlur, error }: { label: string; value: string; onChange: (v: string) => void; onBlur: () => void; error?: string }) {
  const [focused, setFocused] = useState(false)
  const shown = focused || value === '' || Number.isNaN(Number(value)) ? value : formatNumber(Number(value))
  return (
    <Field label={label} optional hint="Per year" error={error}>
      <Input
        inputMode="numeric"
        leading="₹"
        value={shown}
        placeholder="Not stated"
        onFocus={() => setFocused(true)}
        onBlur={() => { setFocused(false); onBlur() }}
        onChange={e => onChange(e.target.value.replace(/[^\d]/g, ''))}
      />
    </Field>
  )
}

function ReviewStep({ job, form, drafts, openWarnings, onFix, footer }: {
  job: JobStaff
  form: FormState
  drafts: RequirementDraft[]
  openWarnings: number
  onFix: (step: Step) => void
  footer: ReactNode
}) {
  const scored = drafts.filter(d => d.type !== 'OTHER' && d.description.trim())
  const ordered = [...scored].sort((a, b) => rank(a) - rank(b))
  const total = scored.reduce((n, d) => n + (d.mandatory ? 2 : 1), 0)
  const checks = requirementChecks(drafts, form.experienceLevel, fromLocalInput(form.deadline), openWarnings)
  const others = drafts.filter(d => d.type === 'OTHER' && d.description.trim())
  return (
    <div className="hra-wiz-form">
      <div className="hra-review">
        <Section title="How applicants will be ranked" description="Coverage is the share of weighted requirements with evidence. Must-haves count double. It is not a probability of success.">
          {ordered.length === 0 ? (
            <p className="subtle">No scored requirements yet.</p>
          ) : (
            <ol className="hra-weights">
              {ordered.map((d, i) => {
                const weight = d.mandatory ? 2 : 1
                const share = total ? weight / total : 0
                return (
                  <li key={d.key} style={{ ['--i' as string]: Math.min(i, 8) }}>
                    <span className="hra-weight-text">
                      <span>{d.description}</span>
                      <span className="hra-weight-tags">
                        <ReqMarker kind={d.mandatory ? 'must' : 'nice'} />
                        {d.mandatory && d.knockout && gateAllowed(d) && <ReqMarker kind="gate" />}
                      </span>
                    </span>
                    <span className="hra-weight-bar" aria-hidden="true"><span style={{ ['--v' as string]: share }} /></span>
                    <span className="hra-weight-num numeric">{Math.round(share * 100)}%</span>
                  </li>
                )
              })}
            </ol>
          )}
          {others.length > 0 && <p className="subtle hra-review-note">{plural(others.length, '“Other” requirement')} reviewed by people, not scored: {others.map(o => o.description).join(', ')}.</p>}
        </Section>

        <Section title="Checks">
          <ul className="hra-checks">
            {checks.map(check => (
              <li key={check.id} data-ok={check.ok || undefined}>
                <Icon icon={check.ok ? Check : CircleAlert} size={16} />
                <span>{check.text}</span>
                {!check.ok && <button type="button" className="hra-link-inline" onClick={() => onFix(check.step)}>Fix</button>}
              </li>
            ))}
          </ul>
        </Section>

        <Section title="What candidates see" description="The requirements as the job board lists them. Gates are not shown to candidates.">
          <div className="hra-preview">
            <p className="hra-preview-title">{form.title || job.title}</p>
            <p className="hra-preview-meta">{[form.location, humanize(form.jobType), form.deadline ? `Apply by ${formatDate(fromLocalInput(form.deadline) ?? '')}` : null].filter(Boolean).join(' · ')}</p>
            <ul className="hra-preview-reqs">
              {[...drafts].filter(d => d.description.trim()).sort((a, b) => Number(b.mandatory) - Number(a.mandatory)).map(d => (
                <li key={d.key}><ReqMarker kind={d.mandatory ? 'must' : 'nice'} /><span>{d.description}</span></li>
              ))}
            </ul>
            <p className="hra-preview-foot">A missing must-have does not reject anyone automatically; a person reviews every application.</p>
          </div>
        </Section>
      </div>
      {footer}
    </div>
  )
}

function rank(d: RequirementDraft) {
  return d.mandatory ? (d.knockout && gateAllowed(d) ? 0 : 1) : 2
}
