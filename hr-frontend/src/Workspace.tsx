import React, { FormEvent, useCallback, useEffect, useState } from 'react'
import {
  AdminDashboardView,
  AdminUsersView,
  AdminHrTeamView,
  AdminJobOversightView,
  AdminAuditLogsView,
  Session as AdminSession,
} from './AdminViews'
import { ApplicantIntelligencePanel } from './ApplicantIntelligencePanel'

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1').replace(/\/$/, '')

type Role = 'ADMIN' | 'HR_MANAGER' | 'RECRUITER'
type Session = { accessToken: string; refreshToken: string; role: Role; email: string; firstName: string; lastName: string }
type Requirement = { id?: number; type: string; description: string; mandatory: boolean; minimumYears: number | null }
type Job = {
  id: number; title: string; description: string; department: string; location: string; jobType: string
  experienceLevel: string; minimumSalary: number | null; maximumSalary: number | null; vacancies: number
  status: string; publishedAt: string | null; deadline: string | null; createdAt: string; requirements: Requirement[]
  createdByEmail?: string
}
type Page<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number; first: boolean; last: boolean }
type Envelope<T> = { success: boolean; message?: string; data?: T; errors?: { field: string; message: string }[] }
type JobForm = Omit<Job, 'id' | 'status' | 'publishedAt' | 'createdAt'>

const statuses = ['DRAFT', 'PUBLISHED', 'ON_HOLD', 'CLOSED', 'CANCELLED']
const jobTypes = ['FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERNSHIP']
const levels = ['ENTRY', 'MID', 'SENIOR', 'LEAD', 'EXECUTIVE']
const requirementTypes = ['SKILL', 'EDUCATION', 'CERTIFICATION', 'EXPERIENCE', 'OTHER']

function readMessage(body: unknown, fallback: string) {
  if (body && typeof body === 'object' && 'message' in body && typeof body.message === 'string') return body.message
  return fallback
}

function formatStatus(status: string) { return status.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase()) }
function dateLabel(value?: string | null) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(date)
}
function money(value: number | null) {
  if (value == null) return ''
  return new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(value)
}
function salaryLabel(job: Job) {
  if (job.minimumSalary == null && job.maximumSalary == null) return 'Not specified'
  if (job.minimumSalary != null && job.maximumSalary != null) return `${money(job.minimumSalary)} – ${money(job.maximumSalary)}`
  return `${money(job.minimumSalary ?? job.maximumSalary)}+`
}
function emptyForm(): JobForm {
  return { title: '', description: '', department: '', location: '', jobType: jobTypes[0], experienceLevel: levels[0], minimumSalary: null, maximumSalary: null, vacancies: 1, deadline: null, requirements: [] }
}

async function request<T>(session: Session, path: string, onExpired: () => void, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${session.accessToken}`, ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...init.headers },
  })
  let envelope: Envelope<T> | null = null
  try { envelope = await response.json() as Envelope<T> } catch { /* handled below */ }
  if (response.status === 401) {
    onExpired()
    throw new Error('Your session has expired. Sign in again to continue.')
  }
  if (!response.ok || envelope?.success !== true) {
    const details = envelope?.errors?.map(error => `${error.field}: ${error.message}`).join(' · ')
    throw new Error(details || readMessage(envelope, `Request failed (${response.status}).`))
  }
  return envelope.data as T
}

export default function Workspace({ session, signingOut, onSignOut, onSessionExpired }: { session: Session; signingOut: boolean; onSignOut: () => void; onSessionExpired: () => void }) {
  const [pageData, setPageData] = useState<Page<Job> | null>(null)
  const [stats, setStats] = useState({ total: 0, published: 0, draft: 0, closed: 0 })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [searchInput, setSearchInput] = useState('')
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState('ALL')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Job | null>(null)
  const [editor, setEditor] = useState<{ job?: Job } | null>(null)
  const [notice, setNotice] = useState('')
  const [activeAdminTab, setActiveAdminTab] = useState<'DASHBOARD' | 'USERS' | 'HR_TEAM' | 'JOBS' | 'AUDIT' | 'HR_WORKSPACE'>(
    session.role === 'ADMIN' ? 'DASHBOARD' : 'HR_WORKSPACE'
  )
  const canManage = session.role === 'ADMIN' || session.role === 'HR_MANAGER'
  const fullName = [session.firstName, session.lastName].filter(Boolean).join(' ') || session.email

  const loadJobs = useCallback(async () => {
    setLoading(true); setError('')
    const params = new URLSearchParams({ page: String(page), size: '20', sort: 'createdAt', direction: 'DESC' })
    if (filter !== 'ALL') params.set('status', filter)
    if (search.trim()) params.set('search', search.trim())
    try {
      const [listing, all, published, draft, closed] = await Promise.all([
        request<Page<Job>>(session, `/jobs?${params}`, onSessionExpired),
        request<Page<Job>>(session, '/jobs?page=0&size=1', onSessionExpired),
        request<Page<Job>>(session, '/jobs?page=0&size=1&status=PUBLISHED', onSessionExpired),
        request<Page<Job>>(session, '/jobs?page=0&size=1&status=DRAFT', onSessionExpired),
        request<Page<Job>>(session, '/jobs?page=0&size=1&status=CLOSED', onSessionExpired),
      ])
      setPageData(listing)
      setStats({ total: all.totalElements, published: published.totalElements, draft: draft.totalElements, closed: closed.totalElements })
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load jobs. Try again.')
    } finally { setLoading(false) }
  }, [session, page, filter, search, onSessionExpired])

  useEffect(() => { void loadJobs() }, [loadJobs])
  const jobs = pageData?.content ?? []
  const startIndex = pageData && pageData.totalElements ? pageData.page * pageData.size + 1 : 0
  const endIndex = pageData ? Math.min((pageData.page + 1) * pageData.size, pageData.totalElements) : 0

  async function openDetails(job: Job) {
    setSelected(job)
    try { setSelected(await request<Job>(session, `/jobs/${job.id}`, onSessionExpired)) }
    catch (cause) { setNotice(cause instanceof Error ? cause.message : 'Could not load job details.'); setSelected(null) }
  }

  async function changeStatus(job: Job, status: string) {
    const action = status === 'PUBLISHED' ? 'publish' : status === 'CLOSED' ? 'close' : `change the status to ${formatStatus(status).toLowerCase()}`
    if (!window.confirm(`Are you sure you want to ${action} “${job.title}”?`)) return
    setNotice('')
    try {
      const updated = await request<Job>(session, `/jobs/${job.id}/status`, onSessionExpired, { method: 'PATCH', body: JSON.stringify({ status }) })
      setSelected(updated); setNotice(`“${job.title}” is now ${formatStatus(updated.status)}.`); await loadJobs()
    } catch (cause) { setNotice(cause instanceof Error ? cause.message : 'Status update failed.') }
  }

  async function saveJob(form: JobForm, existing?: Job) {
    setNotice('')
    const payload = { ...form, minimumSalary: form.minimumSalary === null ? null : Number(form.minimumSalary), maximumSalary: form.maximumSalary === null ? null : Number(form.maximumSalary), vacancies: Number(form.vacancies), deadline: form.deadline || null }
    const target = existing ? `/jobs/${existing.id}` : '/jobs'
    const saved = await request<Job>(session, target, onSessionExpired, { method: existing ? 'PUT' : 'POST', body: JSON.stringify(payload) })
    try {
      const keep = new Set(form.requirements.filter(item => item.description.trim()).map(item => item.id).filter(Boolean))
      for (const requirement of existing?.requirements ?? []) {
        if (!keep.has(requirement.id)) await request<unknown>(session, `/jobs/${saved.id}/requirements/${requirement.id}`, onSessionExpired, { method: 'DELETE' })
      }
      for (const requirement of form.requirements.filter(item => item.description.trim())) {
        const reqPayload = { type: requirement.type, description: requirement.description.trim(), mandatory: requirement.mandatory, minimumYears: requirement.minimumYears }
        if (requirement.id) await request<Requirement>(session, `/jobs/${saved.id}/requirements/${requirement.id}`, onSessionExpired, { method: 'PUT', body: JSON.stringify(reqPayload) })
        else await request<Requirement>(session, `/jobs/${saved.id}/requirements`, onSessionExpired, { method: 'POST', body: JSON.stringify(reqPayload) })
      }
    } catch (cause) {
      if (!existing) {
        setEditor(null)
        setNotice(`Draft #${saved.id} was created, but a requirement could not be saved: ${cause instanceof Error ? cause.message : 'request failed'}. Open the draft to continue editing.`)
        await loadJobs()
        return
      }
      throw cause
    }
    setEditor(null); setSelected(null)
    setNotice(existing ? 'Draft updated.' : 'Draft created successfully.')
    await loadJobs()
  }

  function beginEdit(job?: Job) { setSelected(null); setEditor({ job }) }

  return <div className="workspace-shell">
    <aside className="workspace-sidebar">
      <Brand />
      {session.role === 'ADMIN' ? (
        <>
          <div className="sidebar-caption">ADMIN CONSOLE</div>
          <a
            className={`nav-item ${activeAdminTab === 'DASHBOARD' ? 'active' : ''}`}
            href="#admin-dashboard"
            onClick={event => { event.preventDefault(); setActiveAdminTab('DASHBOARD'); setSelected(null); setEditor(null) }}
          >
            <GridIcon /> Platform Overview
          </a>
          <a
            className={`nav-item ${activeAdminTab === 'USERS' ? 'active' : ''}`}
            href="#admin-users"
            onClick={event => { event.preventDefault(); setActiveAdminTab('USERS'); setSelected(null); setEditor(null) }}
          >
            <UsersNavIcon /> User Accounts
          </a>
          <a
            className={`nav-item ${activeAdminTab === 'HR_TEAM' ? 'active' : ''}`}
            href="#admin-hr-team"
            onClick={event => { event.preventDefault(); setActiveAdminTab('HR_TEAM'); setSelected(null); setEditor(null) }}
          >
            <UserCheckNavIcon /> HR Directory
          </a>
          <a
            className={`nav-item ${activeAdminTab === 'JOBS' ? 'active' : ''}`}
            href="#admin-jobs"
            onClick={event => { event.preventDefault(); setActiveAdminTab('JOBS'); setSelected(null); setEditor(null) }}
          >
            <ShieldNavIcon /> Job Oversight
          </a>
          <a
            className={`nav-item ${activeAdminTab === 'AUDIT' ? 'active' : ''}`}
            href="#admin-audit"
            onClick={event => { event.preventDefault(); setActiveAdminTab('AUDIT'); setSelected(null); setEditor(null) }}
          >
            <DocumentNavIcon /> Audit Trail
          </a>

          <div className="sidebar-caption" style={{ marginTop: '20px' }}>HR WORKSPACE</div>
          <a
            className={`nav-item ${activeAdminTab === 'HR_WORKSPACE' ? 'active' : ''}`}
            href="#jobs"
            onClick={event => { event.preventDefault(); setActiveAdminTab('HR_WORKSPACE'); setSelected(null); setEditor(null) }}
          >
            <BriefcaseIcon /> Job postings
          </a>
        </>
      ) : (
        <>
          <div className="sidebar-caption">WORKSPACE</div>
          <a className="nav-item active" href="#jobs" onClick={event => { event.preventDefault(); setSelected(null); setEditor(null) }}><GridIcon /> Overview</a>
          <a className="nav-item" href="#jobs" onClick={event => { event.preventDefault(); setFilter('ALL'); setPage(0); setSelected(null); setEditor(null) }}><BriefcaseIcon /> Job postings</a>
        </>
      )}
      <div className="sidebar-bottom">
        <span className="sidebar-secure"><LockIcon /> {session.role === 'ADMIN' ? 'Secure Admin Console' : 'Private HR workspace'}</span>
        <span className="sidebar-version">RecruitPro · Platform v1.0</span>
      </div>
    </aside>
    <main className="workspace-main">
      <header className="workspace-header">
        <div className="mobile-workspace-brand"><Brand /></div>
        <div className="breadcrumb">
          <span>{session.role === 'ADMIN' ? 'Admin Console' : 'Workspace'}</span>
          <b>/</b>
          <strong>
            {session.role === 'ADMIN' && activeAdminTab === 'DASHBOARD'
              ? 'Platform Overview'
              : session.role === 'ADMIN' && activeAdminTab === 'USERS'
              ? 'User Accounts'
              : session.role === 'ADMIN' && activeAdminTab === 'HR_TEAM'
              ? 'HR Directory'
              : session.role === 'ADMIN' && activeAdminTab === 'JOBS'
              ? 'Job Oversight'
              : session.role === 'ADMIN' && activeAdminTab === 'AUDIT'
              ? 'Audit Trail'
              : editor ? (editor.job ? 'Edit draft' : 'New job') : selected ? 'Job details' : 'Job postings'}
          </strong>
        </div>
        <div className="user-menu"><span className="user-avatar">{fullName[0]?.toUpperCase()}</span><span className="user-name"><strong>{fullName}</strong><small>{formatStatus(session.role)}</small></span><button className="signout-link" onClick={onSignOut} disabled={signingOut}>{signingOut ? 'Signing out…' : 'Sign out'}</button></div>
      </header>
      <div className="workspace-content">
        {session.role === 'ADMIN' && activeAdminTab === 'DASHBOARD' && (
          <AdminDashboardView session={session as unknown as AdminSession} onSessionExpired={onSessionExpired} />
        )}
        {session.role === 'ADMIN' && activeAdminTab === 'USERS' && (
          <AdminUsersView session={session as unknown as AdminSession} onSessionExpired={onSessionExpired} />
        )}
        {session.role === 'ADMIN' && activeAdminTab === 'HR_TEAM' && (
          <AdminHrTeamView session={session as unknown as AdminSession} onSessionExpired={onSessionExpired} />
        )}
        {session.role === 'ADMIN' && activeAdminTab === 'JOBS' && (
          <AdminJobOversightView session={session as unknown as AdminSession} onSessionExpired={onSessionExpired} />
        )}
        {session.role === 'ADMIN' && activeAdminTab === 'AUDIT' && (
          <AdminAuditLogsView session={session as unknown as AdminSession} onSessionExpired={onSessionExpired} />
        )}
        {(session.role !== 'ADMIN' || activeAdminTab === 'HR_WORKSPACE') && (
          editor ? <JobEditor key={editor.job?.id ?? 'new'} job={editor.job} onCancel={() => setEditor(null)} onSave={saveJob} /> : selected ? <JobDetails job={selected} session={session} onSessionExpired={onSessionExpired} canManage={canManage} onBack={() => setSelected(null)} onEdit={() => beginEdit(selected)} onStatus={status => void changeStatus(selected, status)} /> : <>
          <div className="page-title-row"><div><p className="eyebrow">HIRING OPERATIONS</p><h1>Job postings</h1><p className="page-subtitle">Create, organize, and publish opportunities for your team.</p></div>{canManage && <button className="primary-button workspace-primary" onClick={() => beginEdit()}><PlusIcon /> Create job</button>}</div>
          <section className="stat-grid" aria-label="Job summary">
            <Stat label="Total jobs" value={stats.total} icon="all" loading={loading && !pageData} />
            <Stat label="Published" value={stats.published} icon="published" loading={loading && !pageData} />
            <Stat label="Drafts" value={stats.draft} icon="draft" loading={loading && !pageData} />
            <Stat label="Closed" value={stats.closed} icon="closed" loading={loading && !pageData} />
          </section>
          <section className="jobs-card" id="jobs">
            <div className="jobs-card-head"><div><h2>All opportunities</h2><p>Review and manage your current job postings.</p></div><span className="record-count">{pageData ? `${pageData.totalElements} records` : '—'}</span></div>
            <form className="jobs-toolbar" onSubmit={event => { event.preventDefault(); setPage(0); setSearch(searchInput.trim()) }}>
              <label className="search-field"><SearchIcon /><input aria-label="Search jobs by title" placeholder="Search job titles…" value={searchInput} onChange={event => setSearchInput(event.target.value)} /><button type="submit">Search</button></label>
              <label className="status-filter"><span>Status</span><select value={filter} onChange={event => { setFilter(event.target.value); setPage(0) }}><option value="ALL">All statuses</option>{statuses.map(status => <option key={status} value={status}>{formatStatus(status)}</option>)}</select></label>
            </form>
            {notice && <div className="inline-notice" role="status"><span>{notice}</span><button onClick={() => setNotice('')} aria-label="Dismiss message">×</button></div>}
            {error ? <div className="state-panel error-state"><div className="state-icon">!</div><h3>We couldn’t load your jobs</h3><p>{error}</p><button className="outline-button" onClick={() => void loadJobs()}>Try again</button></div> : loading ? <div className="state-panel"><span className="spinner dark-spinner" /><p>Loading your job postings…</p></div> : jobs.length === 0 ? <div className="state-panel empty-state"><span className="empty-icon"><BriefcaseIcon /></span><h3>{search || filter !== 'ALL' ? 'No matching jobs' : 'Your job board is ready'}</h3><p>{search || filter !== 'ALL' ? 'Try another title or status filter.' : canManage ? 'Create your first job draft to get started.' : 'There are no job postings to show yet.'}</p>{canManage && !search && filter === 'ALL' && <button className="outline-button" onClick={() => beginEdit()}><PlusIcon /> Create a job</button>}</div> : <>
              <div className="job-table-wrap"><table className="job-table"><thead><tr><th>JOB TITLE</th><th>DEPARTMENT</th><th>LOCATION</th><th>STATUS</th><th>UPDATED</th><th><span className="sr-only">Actions</span></th></tr></thead><tbody>{jobs.map(job => <tr key={job.id}>
                <td><button className="job-title-link" onClick={() => void openDetails(job)}>{job.title}</button><span className="job-meta">{formatStatus(job.jobType)} · {formatStatus(job.experienceLevel)}</span></td>
                <td>{job.department}</td><td>{job.location}</td><td><StatusBadge status={job.status} /></td><td>{dateLabel(job.createdAt)}</td>
                <td><button className="row-action" onClick={() => void openDetails(job)} aria-label={`View ${job.title}`}>View <ArrowIcon /></button></td>
              </tr>)}</tbody></table></div>
              <div className="table-footer"><span>Showing {startIndex}–{endIndex} of {pageData?.totalElements ?? 0}</span><div className="pagination"><button className="outline-button" disabled={pageData?.first} onClick={() => setPage(value => Math.max(0, value - 1))}>Previous</button><span>Page {(pageData?.page ?? 0) + 1} of {Math.max(pageData?.totalPages ?? 1, 1)}</span><button className="outline-button" disabled={pageData?.last} onClick={() => setPage(value => value + 1)}>Next</button></div></div>
            </>}
          </section>
        </>
        )}
      </div>
    </main>
  </div>
}

function Stat({ label, value, icon, loading }: { label: string; value: number; icon: string; loading: boolean }) {
  return <article className="stat-card"><span className={`stat-icon ${icon}`}><BriefcaseIcon /></span><div><span className="stat-label">{label}</span><strong className="stat-value">{loading ? '—' : value.toLocaleString()}</strong></div></article>
}

function StatusBadge({ status }: { status: string }) { return <span className={`status-badge status-${status.toLowerCase()}`}><i />{formatStatus(status)}</span> }

function JobDetails({ job, session, onSessionExpired, canManage, onBack, onEdit, onStatus }: { job: Job; session: Session; onSessionExpired: () => void; canManage: boolean; onBack: () => void; onEdit: () => void; onStatus: (status: string) => void }) {
  const [showApplicants, setShowApplicants] = useState(false)
  const isJobManager = session.role === 'ADMIN' || (session.role === 'HR_MANAGER' && (!job.createdByEmail || job.createdByEmail.toLowerCase() === session.email.toLowerCase()))
  return <>
    <button className="back-link" onClick={onBack}>← Back to jobs</button>
    <section className="detail-card"><div className="detail-heading"><div><p className="eyebrow">JOB POSTING · #{job.id}</p><h1>{job.title}</h1><div className="detail-tags"><span>{job.department}</span><span>{job.location}</span><span>{formatStatus(job.jobType)}</span><span>{formatStatus(job.experienceLevel)}</span></div></div><StatusBadge status={job.status} /></div>
      {canManage && isJobManager && <div className="detail-actions">{job.status === 'DRAFT' && <><button className="outline-button" onClick={onEdit}>Edit draft</button><button className="primary-button workspace-primary" onClick={() => onStatus('PUBLISHED')}>Publish job <ArrowIcon /></button></>}{job.status === 'PUBLISHED' && <><button className="outline-button" onClick={() => onStatus('ON_HOLD')}>Put on hold</button><button className="outline-button" onClick={() => onStatus('CLOSED')}>Close job</button></>}{job.status === 'ON_HOLD' && <><button className="primary-button workspace-primary" onClick={() => onStatus('PUBLISHED')}>Resume publishing</button><button className="outline-button" onClick={() => onStatus('CLOSED')}>Close job</button></>}</div>}
      {canManage && !isJobManager && <div className="detail-actions"><span className="applicant-muted">Managed by {job.createdByEmail || 'another HR manager'} (view only)</span></div>}
      <div className="detail-columns"><div className="detail-main"><h2>About the role</h2><p className="description-text">{job.description}</p><h2>Requirements</h2>{job.requirements?.length ? <ul className="requirement-list">{job.requirements.map(req => <li key={req.id}><span className="requirement-bullet">✓</span><span><strong>{formatStatus(req.type)}</strong><br />{req.description}{req.minimumYears != null && <small> · {req.minimumYears}+ years</small>}</span>{req.mandatory && <em>Required</em>}</li>)}</ul> : <p className="muted-copy">No requirements listed.</p>}</div>
        <aside className="detail-facts"><h2>Role details</h2><Fact label="Employment type" value={formatStatus(job.jobType)} /><Fact label="Experience level" value={formatStatus(job.experienceLevel)} /><Fact label="Department" value={job.department} /><Fact label="Location" value={job.location} /><Fact label="Salary range" value={salaryLabel(job)} /><Fact label="Openings" value={String(job.vacancies)} /><Fact label="Application deadline" value={dateLabel(job.deadline)} /><Fact label="Published" value={dateLabel(job.publishedAt)} /></aside></div>
      <div className="applicant-review-toggle"><div><h2>Applicant intelligence</h2><p>Review requirement evidence and compare submitted applicants. Scores are evidence coverage, not hiring decisions.</p></div><button className="outline-button" onClick={() => setShowApplicants(value => !value)}>{showApplicants ? 'Hide applicants' : 'Review applicants'}</button></div>
      {showApplicants && <ApplicantIntelligencePanel jobId={job.id} session={session} onSessionExpired={onSessionExpired} canRetry={canManage && isJobManager} />}
    </section>
  </>
}

function Fact({ label, value }: { label: string; value: string }) { return <div className="fact-row"><span>{label}</span><strong>{value}</strong></div> }

function JobEditor({ job, onCancel, onSave }: { job?: Job; onCancel: () => void; onSave: (form: JobForm, existing?: Job) => Promise<void> }) {
  const [form, setForm] = useState<JobForm>(() => job ? { title: job.title, description: job.description, department: job.department, location: job.location, jobType: job.jobType, experienceLevel: job.experienceLevel, minimumSalary: job.minimumSalary, maximumSalary: job.maximumSalary, vacancies: job.vacancies, deadline: job.deadline?.slice(0, 16) ?? null, requirements: (job.requirements ?? []).map(req => ({ ...req })) } : emptyForm())
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const update = <K extends keyof JobForm>(key: K, value: JobForm[K]) => setForm(previous => ({ ...previous, [key]: value }))
  const updateRequirement = (index: number, values: Partial<Requirement>) => update('requirements', form.requirements.map((req, i) => i === index ? { ...req, ...values } : req))
  const addRequirement = () => update('requirements', [...form.requirements, { type: 'SKILL', description: '', mandatory: true, minimumYears: null }])
  const removeRequirement = (index: number) => update('requirements', form.requirements.filter((_, i) => i !== index))
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(''); setFieldErrors({})
    const errors: Record<string, string> = {}
    if (!form.title.trim()) errors.title = 'Job title is required.'
    if (!form.description.trim()) errors.description = 'Description is required.'
    if (!form.department.trim()) errors.department = 'Department is required.'
    if (!form.location.trim()) errors.location = 'Location is required.'
    if (!Number.isInteger(Number(form.vacancies)) || Number(form.vacancies) < 1) errors.vacancies = 'Enter at least one opening.'
    if (form.minimumSalary != null && form.maximumSalary != null && Number(form.minimumSalary) > Number(form.maximumSalary)) errors.maximumSalary = 'Maximum salary must be at least the minimum.'
    if (form.deadline && new Date(form.deadline).getTime() <= Date.now()) errors.deadline = 'Deadline must be in the future.'
    form.requirements.forEach((req, index) => { if (req.description.trim().length > 500) errors[`requirements.${index}`] = 'Requirement must be 500 characters or fewer.' })
    if (Object.keys(errors).length) { setFieldErrors(errors); return }
    setSaving(true)
    try { await onSave(form, job) } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not save this job.') } finally { setSaving(false) }
  }
  return <section className="editor-card"><button className="back-link" onClick={onCancel}>← Back to jobs</button><div className="editor-heading"><p className="eyebrow">{job ? 'DRAFT EDITOR' : 'NEW OPPORTUNITY'}</p><h1>{job ? 'Edit job draft' : 'Create a job posting'}</h1><p>Save your changes as a draft. You can publish when everything is ready.</p></div>
    <form className="job-form" onSubmit={event => void submit(event)} noValidate>
      {error && <div className="form-api-error" role="alert">{error}</div>}
      <section className="form-section"><h2>Position details</h2><p>Set the role information candidates will see.</p><div className="form-grid">
        <Field label="Job title" required error={fieldErrors.title}><input value={form.title} maxLength={255} onChange={event => update('title', event.target.value)} placeholder="e.g. Senior Product Designer" /></Field>
        <Field label="Department" required error={fieldErrors.department}><input value={form.department} maxLength={100} onChange={event => update('department', event.target.value)} placeholder="e.g. Product" /></Field>
        <Field label="Location" required error={fieldErrors.location}><input value={form.location} maxLength={255} onChange={event => update('location', event.target.value)} placeholder="e.g. Bengaluru · Hybrid" /></Field>
        <Field label="Employment type" required><select value={form.jobType} onChange={event => update('jobType', event.target.value)}>{jobTypes.map(v => <option key={v} value={v}>{formatStatus(v)}</option>)}</select></Field>
        <Field label="Experience level" required><select value={form.experienceLevel} onChange={event => update('experienceLevel', event.target.value)}>{levels.map(v => <option key={v} value={v}>{formatStatus(v)}</option>)}</select></Field>
        <Field label="Open positions" required error={fieldErrors.vacancies}><input type="number" min="1" step="1" value={form.vacancies} onChange={event => update('vacancies', Number(event.target.value))} /></Field>
        <Field label="Minimum salary" hint="Optional; annual amount"><input type="number" min="0" step="1000" value={form.minimumSalary ?? ''} onChange={event => update('minimumSalary', event.target.value === '' ? null : Number(event.target.value))} placeholder="Not specified" /></Field>
        <Field label="Maximum salary" hint="Optional; annual amount" error={fieldErrors.maximumSalary}><input type="number" min="0" step="1000" value={form.maximumSalary ?? ''} onChange={event => update('maximumSalary', event.target.value === '' ? null : Number(event.target.value))} placeholder="Not specified" /></Field>
        <Field label="Application deadline" hint="Optional" error={fieldErrors.deadline}><input type="datetime-local" value={form.deadline ?? ''} onChange={event => update('deadline', event.target.value || null)} /></Field>
        <div className="field full-width"><label htmlFor="job-description">Job description <span className="required-mark">*</span></label><textarea id="job-description" rows={7} value={form.description} onChange={event => update('description', event.target.value)} placeholder="Describe the role, responsibilities, and what success looks like…" aria-invalid={Boolean(fieldErrors.description)} />{fieldErrors.description && <small className="field-error">{fieldErrors.description}</small>}</div>
      </div></section>
      <section className="form-section requirements-section"><div className="section-title-row"><div><h2>Requirements</h2><p>Add skills, experience, education, or certifications for this role.</p></div><button type="button" className="outline-button" onClick={addRequirement}><PlusIcon /> Add requirement</button></div>
        {form.requirements.length === 0 ? <div className="requirement-empty">No requirements added yet. Requirements are optional.</div> : form.requirements.map((req, index) => <div className="requirement-editor" key={req.id ?? `new-${index}`}><label><span>Type</span><select value={req.type} onChange={event => updateRequirement(index, { type: event.target.value })}>{requirementTypes.map(type => <option key={type}>{type}</option>)}</select></label><label className="requirement-description"><span>Description</span><input maxLength={500} value={req.description} onChange={event => updateRequirement(index, { description: event.target.value })} placeholder="e.g. Strong experience with Java and Spring Boot" />{fieldErrors[`requirements.${index}`] && <small className="field-error">{fieldErrors[`requirements.${index}`]}</small>}</label><label><span>Minimum years</span><input type="number" min="0" value={req.minimumYears ?? ''} onChange={event => updateRequirement(index, { minimumYears: event.target.value === '' ? null : Number(event.target.value) })} placeholder="Optional" /></label><label className="mandatory-check"><input type="checkbox" checked={req.mandatory} onChange={event => updateRequirement(index, { mandatory: event.target.checked })} /> Required</label><button type="button" className="remove-requirement" aria-label="Remove requirement" onClick={() => removeRequirement(index)}>×</button></div>)}
      </section>
      <div className="editor-footer"><span>All new job postings are saved as drafts.</span><div><button type="button" className="outline-button" onClick={onCancel} disabled={saving}>Cancel</button><button type="submit" className="primary-button workspace-primary" disabled={saving}>{saving ? 'Saving…' : <><SaveIcon /> Save draft</>}</button></div></div>
    </form>
  </section>
}

function Field({ label, required, hint, error, children }: { label: string; required?: boolean; hint?: string; error?: string; children: React.ReactNode }) {
  const id = `field-${label.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`
  return <div className="field"><label htmlFor={id}>{label} {required && <span className="required-mark">*</span>}</label>{React.cloneElement(children as React.ReactElement<{ id?: string; 'aria-invalid'?: boolean }>, { id, 'aria-invalid': Boolean(error) })}{error ? <small className="field-error">{error}</small> : hint && <small className="field-hint">{hint}</small>}</div>
}

function Brand() { return <a className="brand" href="/" aria-label="RecruitPro HR workspace home"><span className="brand-mark"><span>R</span><i /></span><span className="brand-name">Recruit<span>Pro</span><small>HR WORKSPACE</small></span></a> }
function BriefcaseIcon() { return <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="3" y="7" width="18" height="13" rx="2.5" /><path d="M8 7V5.5A1.5 1.5 0 0 1 9.5 4h5A1.5 1.5 0 0 1 16 5.5V7M3 12h18m-11 0v2h4v-2" /></svg> }
function LockIcon() { return <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="4.5" y="10" width="15" height="11" rx="2.5" /><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 4v3" /></svg> }
function GridIcon() { return <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="3" y="3" width="8" height="8" rx="2" /><rect x="13" y="3" width="8" height="5" rx="2" /><rect x="13" y="10" width="8" height="11" rx="2" /><rect x="3" y="13" width="8" height="8" rx="2" /></svg> }
function SearchIcon() { return <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="10.8" cy="10.8" r="6.8" /><path d="m16 16 4.5 4.5" /></svg> }
function PlusIcon() { return <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 5v14m-7-7h14" /></svg> }
function ArrowIcon() { return <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 12h14m-6-6 6 6-6 6" /></svg> }
function SaveIcon() { return <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 4h12l3 3v13H4V4h1zm2 0v6h10V4M8 20v-7h8v7" /></svg> }
function UsersNavIcon() { return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M23 21v-2a4 4 0 0 0-3-3.87" /><path d="M16 3.13a4 4 0 0 1 0 7.75" /></svg> }
function UserCheckNavIcon() { return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><circle cx="8.5" cy="7" r="4" /><polyline points="17 11 19 13 23 9" /></svg> }
function ShieldNavIcon() { return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" /></svg> }
function DocumentNavIcon() { return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><polyline points="14 2 14 8 20 8" /><line x1="16" y1="13" x2="8" y2="13" /><line x1="16" y1="17" x2="8" y2="17" /></svg> }
