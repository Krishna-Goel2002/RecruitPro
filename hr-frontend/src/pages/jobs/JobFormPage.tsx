import { FormEvent, useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { errorMessage } from '../../api/client'
import { jobsApi } from '../../api/jobs'
import { qk } from '../../api/queryKeys'
import { EXPERIENCE_LEVELS, ExperienceLevel, JOB_TYPES, JobInput, JobStaff, JobType } from '../../api/types'
import { ConfirmDialog } from '../../components/Dialog'
import { Field } from '../../components/Field'
import { SaveIcon, SparkIcon } from '../../components/Icons'
import { useNotices } from '../../components/Notices'
import { EmptyPanel, ErrorPanel, FormError, LoadingPanel } from '../../components/States'
import { fromLocalInput, humanize, toLocalInput } from '../../lib/format'
import { usePageTitle } from '../../lib/hooks'
import { CompileSuggestions } from './CompileSuggestions'
import { RequirementsEditor } from './RequirementsEditor'
import { draftFrom, RequirementDraft, sameRequirements, toInput, validateDrafts } from './requirementDrafts'

type FormState = {
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

const EDITABLE = ['DRAFT', 'PUBLISHED', 'ON_HOLD']

function initialForm(job?: JobStaff): FormState {
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

/** /jobs/new and /jobs/:jobId/edit */
export function JobFormPage() {
  const { jobId } = useParams()
  const id = jobId ? Number(jobId) : null
  const job = useQuery({ queryKey: qk.job(id ?? 0), queryFn: ({ signal }) => jobsApi.get(id as number, signal), enabled: id != null })

  if (id != null && Number.isNaN(id)) return <EmptyPanel title="Job not found" />
  if (id != null && job.isPending) return <LoadingPanel label="Loading job…" />
  if (id != null && job.isError) return <ErrorPanel error={job.error} title="We couldn’t load this job" onRetry={() => void job.refetch()} />
  if (job.data && !EDITABLE.includes(job.data.status)) {
    return (
      <EmptyPanel title="This job can no longer be edited" action={<Link className="outline-button" to={`/jobs/${job.data.id}`}>Back to job</Link>}>
        Closed and cancelled jobs are kept as they were for the audit trail.
      </EmptyPanel>
    )
  }
  return <JobForm key={job.data?.id ?? 'new'} job={job.data} />
}

class PartialSaveError extends Error {
  constructor(readonly job: JobStaff, cause: unknown) {
    super(`The draft was created, but its requirements could not be saved: ${errorMessage(cause)}`)
  }
}

function JobForm({ job }: { job?: JobStaff }) {
  usePageTitle(job ? `Edit ${job.title}` : 'New job')
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const notices = useNotices()
  const [form, setForm] = useState<FormState>(() => initialForm(job))
  // After a partial save (job created, requirements failed) the unsaved rows are carried over in navigation state.
  const carried = (location.state as { drafts?: RequirementDraft[] } | null)?.drafts
  const [drafts, setDrafts] = useState<RequirementDraft[]>(() => carried ?? (job?.requirements ?? []).map(draftFrom))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [requirementErrors, setRequirementErrors] = useState<Record<string, string>>({})
  const [confirmOpen, setConfirmOpen] = useState(false)

  const original = useMemo(() => (job?.requirements ?? []).map(requirement => toInput(draftFrom(requirement))), [job])
  const requirementsChanged = !sameRequirements(drafts.map(toInput), original)
  const live = job != null && (job.status === 'PUBLISHED' || job.status === 'ON_HOLD')

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => setForm(current => ({ ...current, [key]: value }))

  const compile = useMutation({
    mutationFn: () => jobsApi.compileRequirements({ title: form.title.trim(), description: form.description.trim(), experienceLevel: form.experienceLevel }),
  })

  const save = useMutation({
    mutationFn: async (): Promise<JobStaff> => {
      const body: JobInput = {
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
      const requirements = drafts.map(toInput)
      if (!job) {
        const created = await jobsApi.create(body)
        if (requirements.length === 0) return created
        try {
          return await jobsApi.replaceRequirements(created.id, requirements)
        } catch (cause) {
          throw new PartialSaveError(created, cause)
        }
      }
      const updated = await jobsApi.update(job.id, body)
      return requirementsChanged ? jobsApi.replaceRequirements(job.id, requirements) : updated
    },
    onSuccess: saved => {
      queryClient.setQueryData(qk.job(saved.id), saved)
      void queryClient.invalidateQueries({ queryKey: qk.jobs })
      if (requirementsChanged && live) void queryClient.invalidateQueries({ queryKey: qk.applicants(saved.id) })
      notices.success(job ? (requirementsChanged && live ? 'Job saved. Applicants are being re-matched against the new requirements.' : 'Job saved.') : 'Draft created.')
      navigate(`/jobs/${saved.id}`)
    },
    onError: cause => {
      if (cause instanceof PartialSaveError) {
        void queryClient.invalidateQueries({ queryKey: qk.jobs })
        notices.error(cause.message)
        navigate(`/jobs/${cause.job.id}/edit`, { replace: true, state: { drafts } })
      }
    },
  })

  useEffect(() => { if (save.isError) window.scrollTo({ top: 0, behavior: 'smooth' }) }, [save.isError])

  function validate(): boolean {
    const next: Record<string, string> = {}
    if (!form.title.trim()) next.title = 'Enter a job title.'
    if (!form.description.trim()) next.description = 'Describe the role.'
    if (!form.department.trim()) next.department = 'Enter a department.'
    if (!form.location.trim()) next.location = 'Enter a location.'
    const vacancies = Number(form.vacancies)
    if (!Number.isInteger(vacancies) || vacancies < 1) next.vacancies = 'Enter at least one opening.'
    const min = form.minimumSalary === '' ? null : Number(form.minimumSalary)
    const max = form.maximumSalary === '' ? null : Number(form.maximumSalary)
    if (min != null && (Number.isNaN(min) || min < 0)) next.minimumSalary = 'Enter a positive amount.'
    if (max != null && (Number.isNaN(max) || max < 0)) next.maximumSalary = 'Enter a positive amount.'
    if (min != null && max != null && min > max) next.maximumSalary = 'Maximum must be at least the minimum.'
    if (form.deadline) {
      const deadline = fromLocalInput(form.deadline)
      if (!deadline || new Date(deadline).getTime() <= Date.now()) next.deadline = 'The deadline must be in the future.'
    }
    const reqErrors = validateDrafts(drafts)
    setErrors(next)
    setRequirementErrors(reqErrors)
    const ok = Object.keys(next).length === 0 && Object.keys(reqErrors).length === 0
    if (!ok) {
      window.setTimeout(() => document.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus(), 0)
    }
    return ok
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!validate()) return
    if (live && requirementsChanged) setConfirmOpen(true)
    else save.mutate()
  }

  function draftFromDescription() {
    const next: Record<string, string> = {}
    if (!form.title.trim()) next.title = 'Enter a job title first.'
    if (form.description.trim().length < 20) next.description = 'Write a longer description first (at least a few lines).'
    setErrors(current => ({ ...current, title: next.title ?? '', description: next.description ?? '' }))
    if (Object.keys(next).length) return
    compile.mutate()
  }

  function acceptSuggestions(indexes: number[], mode: 'append' | 'replace') {
    if (!compile.data) return
    const accepted = indexes.map(index => draftFrom(compile.data.requirements[index]))
    if (mode === 'replace') setDrafts(accepted)
    else {
      const existing = new Set(drafts.map(draft => draft.description.trim().toLowerCase()))
      setDrafts([...drafts, ...accepted.filter(draft => !existing.has(draft.description.trim().toLowerCase()))])
    }
    compile.reset()
    notices.success(`${indexes.length} requirement${indexes.length === 1 ? '' : 's'} added. Review them, then save.`)
  }

  const cancelTo = job ? `/jobs/${job.id}` : '/jobs'

  return (
    <section className="editor-card">
      <Link className="back-link" to={cancelTo}>← {job ? 'Back to job' : 'Back to jobs'}</Link>
      <div className="editor-heading">
        <p className="eyebrow">{job ? `${humanize(job.status).toUpperCase()} · JOB #${job.id}` : 'NEW OPPORTUNITY'}</p>
        <h1>{job ? `Edit “${job.title}”` : 'Create a job'}</h1>
        <p>{job ? (live ? 'This job is live. Requirement changes are versioned, audited and trigger re-matching.' : 'Changes stay in draft until you publish.') : 'New jobs are saved as drafts. Publish from the job page when ready.'}</p>
      </div>

      <form className="job-form" onSubmit={submit} noValidate>
        {save.isError && !(save.error instanceof PartialSaveError) && <FormError error={save.error} />}

        <section className="form-section">
          <h2>Position details</h2>
          <p>What candidates see on the job board.</p>
          <div className="form-grid">
            <Field label="Job title" required error={errors.title || null}>
              <input value={form.title} maxLength={255} onChange={event => set('title', event.target.value)} placeholder="e.g. Senior Accountant" />
            </Field>
            <Field label="Department" required error={errors.department || null}>
              <input value={form.department} maxLength={100} onChange={event => set('department', event.target.value)} placeholder="e.g. Finance" />
            </Field>
            <Field label="Location" required error={errors.location || null}>
              <input value={form.location} maxLength={255} onChange={event => set('location', event.target.value)} placeholder="e.g. Bhubaneswar · Hybrid" />
            </Field>
            <Field label="Employment type" required>
              <select value={form.jobType} onChange={event => set('jobType', event.target.value as JobType)}>
                {JOB_TYPES.map(value => <option key={value} value={value}>{humanize(value)}</option>)}
              </select>
            </Field>
            <Field label="Experience level" required hint="Also used to flag excessive experience demands.">
              <select value={form.experienceLevel} onChange={event => set('experienceLevel', event.target.value as ExperienceLevel)}>
                {EXPERIENCE_LEVELS.map(value => <option key={value} value={value}>{humanize(value)}</option>)}
              </select>
            </Field>
            <Field label="Open positions" required error={errors.vacancies || null}>
              <input type="number" min={1} step={1} inputMode="numeric" value={form.vacancies} onChange={event => set('vacancies', event.target.value)} />
            </Field>
            <Field label="Minimum salary" hint="Optional; annual amount" error={errors.minimumSalary || null}>
              <input type="number" min={0} step={1000} inputMode="numeric" value={form.minimumSalary} onChange={event => set('minimumSalary', event.target.value)} placeholder="Not specified" />
            </Field>
            <Field label="Maximum salary" hint="Optional; annual amount" error={errors.maximumSalary || null}>
              <input type="number" min={0} step={1000} inputMode="numeric" value={form.maximumSalary} onChange={event => set('maximumSalary', event.target.value)} placeholder="Not specified" />
            </Field>
            <Field label="Application deadline" hint="Optional; your local time. Published jobs close automatically after it." error={errors.deadline || null}>
              <input type="datetime-local" value={form.deadline} onChange={event => set('deadline', event.target.value)} />
            </Field>
            <Field label="Job description" required className="full-width" error={errors.description || null} hint="Include duties and what a good hire must be able to do; requirements can be drafted from it.">
              <textarea rows={9} value={form.description} onChange={event => set('description', event.target.value)} placeholder="Responsibilities, must-haves and nice-to-haves…" />
            </Field>
          </div>
        </section>

        <section className="form-section requirements-section" aria-labelledby="requirements-heading">
          <div className="section-title-row">
            <div>
              <h2 id="requirements-heading">Requirements</h2>
              <p>Applicants are ranked on verified evidence against these. Must-haves weigh double; a ranking gate holds an applicant out of the ranked group until it is verified — it never rejects anyone automatically.</p>
            </div>
            <button type="button" className="outline-button" onClick={draftFromDescription} disabled={compile.isPending}>
              <SparkIcon /> {compile.isPending ? 'Drafting…' : 'Draft requirements from description'}
            </button>
          </div>
          {compile.isError && <FormError error={compile.error} />}
          {compile.data && (
            <CompileSuggestions result={compile.data} hasExisting={drafts.length > 0} onAccept={acceptSuggestions} onDismiss={() => compile.reset()} />
          )}
          <RequirementsEditor drafts={drafts} onChange={setDrafts} errors={requirementErrors} />
        </section>

        <div className="editor-footer">
          <span>{job ? (requirementsChanged ? 'Requirements changed since the last save.' : 'Requirements unchanged.') : 'New jobs are saved as drafts.'}</span>
          <div>
            <Link className="outline-button" to={cancelTo}>Cancel</Link>
            <button type="submit" className="primary-button workspace-primary" disabled={save.isPending}>
              {save.isPending ? 'Saving…' : <><SaveIcon /> {job ? 'Save changes' : 'Save draft'}</>}
            </button>
          </div>
        </div>
      </form>

      <ConfirmDialog
        open={confirmOpen}
        title="Change requirements of a live job?"
        message={<>
          Saving creates requirements version {(job?.requirementsVersion ?? 1) + 1}, is recorded in the audit log, and re-matches
          every applicant{job?.applicationCount != null ? ` (${job.applicationCount})` : ''}. Rankings may change for a few seconds while matching runs.
        </>}
        confirmLabel="Save and re-match"
        busy={save.isPending}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={() => { setConfirmOpen(false); save.mutate() }}
      />
    </section>
  )
}
