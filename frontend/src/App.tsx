import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { ResumeReviewSection } from './ResumeReviewSection'

const API = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1').replace(/\/$/, '')
type Envelope<T> = { success: boolean; message?: string; data: T; errors?: { field: string; message: string }[] }
type Profile = { email: string; firstName: string; lastName: string; dateOfBirth?: string; gender?: string; address?: string; city?: string; state?: string; country?: string; zipCode?: string; linkedinUrl?: string; portfolioUrl?: string; yearsOfExperience?: number; currentCompany?: string; currentDesignation?: string; expectedSalary?: number; noticePeriodDays?: number; profileSummary?: string }
type Resume = { id: number; originalFilename: string; contentType: string; fileSize: number; uploadedAt: string }
type ResumeFact = { id: number; type: string; value: string; extractedValue: string; evidence: string; sourceSection: string; sourceReference?: string; reviewStatus: 'UNREVIEWED' | 'CONFIRMED' | 'CORRECTED'; reviewedAt?: string }
type ResumeAnalysis = { resumeId: number; status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'NEEDS_OCR' | 'UNSUPPORTED_FORMAT'; parserVersion?: string; errorCode?: string; attemptCount: number; analyzedAt?: string; facts: ResumeFact[] }
type Job = { id: number; title: string; description: string; department: string; location: string; jobType: string; experienceLevel: string; minimumSalary?: number; maximumSalary?: number; deadline?: string; status: string; requirements?: { id: number; type: string; description: string; mandatory: boolean; minimumYears?: number }[] }
type Application = { id: number; jobId: number; jobTitle: string; resumeId: number; resumeFilename: string; status: 'SUBMITTED' | 'SHORTLISTED' | 'REJECTED' | 'WITHDRAWN'; appliedAt: string; coverLetter?: string }
type Page<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number; first: boolean; last: boolean }

const JOBS_PAGE_SIZE = 10
const APPLICATIONS_PAGE_SIZE = 10

let refreshPromise: Promise<string | null> | null = null
function refreshAccessToken(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      const refreshToken = localStorage.getItem('refreshToken')
      if (!refreshToken) return null
      const response = await fetch(`${API}/auth/refresh`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken }) })
      if (!response.ok) { localStorage.removeItem('accessToken'); localStorage.removeItem('refreshToken'); return null }
      const auth = (await response.json() as Envelope<{ accessToken: string; refreshToken: string }>).data
      localStorage.setItem('accessToken', auth.accessToken); localStorage.setItem('refreshToken', auth.refreshToken)
      return auth.accessToken
    })().finally(() => { refreshPromise = null })
  }
  return refreshPromise
}

async function api<T>(path: string, token: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  const accessToken = localStorage.getItem('accessToken') || token
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  let response = await fetch(`${API}${path}`, { ...init, headers })
  if (response.status === 401 && token && !path.startsWith('/auth/')) {
    const renewed = await refreshAccessToken()
    if (renewed) {
      headers.set('Authorization', `Bearer ${renewed}`)
      response = await fetch(`${API}${path}`, { ...init, headers })
    }
  }
  if (!response.ok) {
    let message = `Request failed (${response.status})`
    try { const body = await response.json(); message = body.message || message } catch { /* retain status message */ }
    throw new Error(message)
  }
  if (response.headers.get('content-type')?.includes('application/json')) {
    const body = await response.json() as Envelope<T>
    return body.data
  }
  return await response.blob() as T
}

const blankProfile: Profile = { email: '', firstName: '', lastName: '' }
const profileFields: [keyof Profile, string, string?][] = [
  ['dateOfBirth', 'Date of birth', 'date'], ['gender', 'Gender'], ['address', 'Address'], ['city', 'City'], ['state', 'State'], ['country', 'Country'], ['zipCode', 'Postal code'], ['linkedinUrl', 'LinkedIn URL'], ['portfolioUrl', 'Portfolio URL'], ['yearsOfExperience', 'Years of experience', 'number'], ['currentCompany', 'Current company'], ['currentDesignation', 'Current designation'], ['expectedSalary', 'Expected salary', 'number'], ['noticePeriodDays', 'Notice period (days)', 'number'], ['profileSummary', 'About you']
]

export default function App() {
  const [token, setToken] = useState(() => localStorage.getItem('accessToken') || '')
  const [authMode, setAuthMode] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState(''); const [password, setPassword] = useState('')
  const [firstName, setFirstName] = useState(''); const [lastName, setLastName] = useState('')
  const [profile, setProfile] = useState<Profile>(blankProfile); const [resumes, setResumes] = useState<Resume[]>([])
  const [resumeAnalyses, setResumeAnalyses] = useState<Record<number, ResumeAnalysis>>({})
  const [factDrafts, setFactDrafts] = useState<Record<number, string>>({})
  const [reviewingFact, setReviewingFact] = useState<number | null>(null)
  const [jobs, setJobs] = useState<Job[]>([]); const [applications, setApplications] = useState<Application[]>([])
  const [jobTotalElements, setJobTotalElements] = useState(0); const [jobTotalPages, setJobTotalPages] = useState(0)
  const [applicationTotalElements, setApplicationTotalElements] = useState(0); const [applicationTotalPages, setApplicationTotalPages] = useState(0)
  const [detailJob, setDetailJob] = useState<Job | null>(null)
  const [detailApplication, setDetailApplication] = useState<Application | null>(null)
  const [loadingApplicationId, setLoadingApplicationId] = useState<number | null>(null)
  const [selectedResume, setSelectedResume] = useState(''); const [search, setSearch] = useState('')
  const [department, setDepartment] = useState(''); const [location, setLocation] = useState(''); const [jobType, setJobType] = useState('')
  const [jobPage, setJobPage] = useState(0); const [applicationPage, setApplicationPage] = useState(0)
  const [tab, setTab] = useState<'jobs' | 'profile' | 'resumes' | 'applications'>('jobs')
  const [loading, setLoading] = useState(false); const [busy, setBusy] = useState(false)
  const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const latestLoad = useRef(0)

  const loadPortal = useCallback(async () => {
    if (!token) return
    const loadId = ++latestLoad.current
    setLoading(true); setError('')
    const jobParams = new URLSearchParams({ page: String(jobPage), size: String(JOBS_PAGE_SIZE) })
    if (search.trim()) jobParams.set('search', search.trim())
    if (department.trim()) jobParams.set('department', department.trim())
    if (location.trim()) jobParams.set('location', location.trim())
    if (jobType) jobParams.set('jobType', jobType)
    try {
      const [p, j, r, a] = await Promise.all([
        api<Profile>('/candidates/me', token),
        api<Page<Job>>(`/jobs?${jobParams.toString()}`, token),
        api<Resume[]>('/candidates/me/resumes', token),
        api<Page<Application>>(`/candidates/me/applications?page=${applicationPage}&size=${APPLICATIONS_PAGE_SIZE}`, token)
      ])
      const analysisEntries = await Promise.all(r.map(async (resume): Promise<[number, ResumeAnalysis | null]> => {
        try { return [resume.id, await api<ResumeAnalysis>(`/candidates/me/resumes/${resume.id}/analysis`, token)] }
        catch { return [resume.id, null] }
      }))
      if (loadId !== latestLoad.current) return
      setProfile(p); setJobs(j.content); setResumes(r); setApplications(a.content)
      setResumeAnalyses(Object.fromEntries(analysisEntries.filter((entry): entry is [number, ResumeAnalysis] => entry[1] !== null)))
      setJobTotalElements(j.totalElements); setJobTotalPages(j.totalPages)
      setApplicationTotalElements(a.totalElements); setApplicationTotalPages(a.totalPages)
      setSelectedResume(current => current || String(r[0]?.id || ''))
    } catch (e) {
      if (loadId === latestLoad.current) setError(e instanceof Error ? e.message : 'Could not load candidate portal')
    } finally {
      if (loadId === latestLoad.current) setLoading(false)
    }
  }, [token, search, department, location, jobType, jobPage, applicationPage])

  useEffect(() => { void loadPortal() }, [loadPortal])
  useEffect(() => {
    if (!Object.values(resumeAnalyses).some(analysis => analysis.status === 'PENDING' || analysis.status === 'PROCESSING')) return
    let active = true
    const timer = window.setTimeout(() => {
      void Promise.all(resumes.map(async (resume): Promise<[number, ResumeAnalysis | null]> => {
        try { return [resume.id, await api<ResumeAnalysis>(`/candidates/me/resumes/${resume.id}/analysis`, token)] }
        catch { return [resume.id, null] }
      })).then(entries => {
        if (active) setResumeAnalyses(Object.fromEntries(entries.filter((entry): entry is [number, ResumeAnalysis] => entry[1] !== null)))
      })
    }, 2500)
    return () => { active = false; window.clearTimeout(timer) }
  }, [resumeAnalyses, resumes, token])

  async function signOut() {
    const refreshToken = localStorage.getItem('refreshToken')
    try {
      if (refreshToken) await api<void>('/auth/logout', token, { method: 'POST', body: JSON.stringify({ refreshToken }) })
      setError('')
    } catch (e) {
      setError(`Signed out locally, but server logout could not be confirmed${e instanceof Error ? `: ${e.message}` : '.'}`)
    } finally {
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
      setToken('')
      setProfile(blankProfile)
      setResumes([])
      setResumeAnalyses({})
      setJobs([])
      setApplications([])
      setJobTotalElements(0); setJobTotalPages(0)
      setApplicationTotalElements(0); setApplicationTotalPages(0)
      setDetailJob(null)
      setDetailApplication(null)
      setSelectedResume('')
    }
  }
  async function authenticate(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('')
    try {
      const path = authMode === 'login' ? '/auth/login' : '/auth/register'
      const body = authMode === 'login' ? { email, password } : { email, password, firstName, lastName }
      const result = await api<{ accessToken: string; refreshToken: string; user: { role: string } }>(path, '', { method: 'POST', body: JSON.stringify(body) })
      if (result.user.role !== 'CANDIDATE') throw new Error('This portal is for candidate accounts.')
      localStorage.setItem('accessToken', result.accessToken); localStorage.setItem('refreshToken', result.refreshToken); setToken(result.accessToken)
    } catch (e) { setError(e instanceof Error ? e.message : 'Authentication failed') }
    finally { setBusy(false) }
  }
  async function saveProfile(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('')
    const values: Record<string, unknown> = Object.fromEntries(profileFields.map(([key]) => [key, profile[key] ?? null]))
    for (const [key] of profileFields) if (values[key] === '') values[key] = null
    for (const key of ['yearsOfExperience', 'expectedSalary', 'noticePeriodDays'] as const) values[key] = values[key] === '' || values[key] == null ? null : Number(String(values[key]))
    try { setProfile(await api<Profile>('/candidates/me', token, { method: 'PUT', body: JSON.stringify(values) })); setNotice('Profile saved.') }
    catch (e) { setError(e instanceof Error ? e.message : 'Profile update failed') }
    finally { setBusy(false) }
  }
  async function uploadResume(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const file = (event.currentTarget.elements.namedItem('resume') as HTMLInputElement).files?.[0]
    if (!file) { setError('Choose a PDF, DOC, or DOCX file.'); return }
    const form = new FormData(); form.append('file', file); setBusy(true); setError(''); setNotice('')
    try { await api<Resume>('/candidates/me/resumes', token, { method: 'POST', body: form }); setNotice('Resume uploaded. Earlier resumes remain attached to existing applications.'); await loadPortal() }
    catch (e) { setError(e instanceof Error ? e.message : 'Upload failed') }
    finally { setBusy(false) }
  }
  async function downloadResume(resume: Resume) {
    setError('')
    try { const blob = await api<Blob>(`/candidates/me/resumes/${resume.id}/download`, token); const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = `resume-${resume.id}`; a.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000) }
    catch (e) { setError(e instanceof Error ? e.message : 'Download failed') }
  }
  async function retryResumeAnalysis(resumeId: number) {
    setError(''); setNotice('')
    try { await api<void>(`/candidates/me/resumes/${resumeId}/analysis/retry`, token, { method: 'PATCH' }); setNotice('Resume analysis retry queued.'); await loadPortal() }
    catch (e) { setError(e instanceof Error ? e.message : 'Could not retry resume analysis') }
  }
  async function reviewResumeFact(resumeId: number, fact: ResumeFact, newValue?: string) {
    const value = (newValue ?? factDrafts[fact.id] ?? fact.value).trim()
    if (!value) { setError('Resume information cannot be blank.'); return }
    setReviewingFact(fact.id); setError(''); setNotice('')
    try {
      await api<ResumeFact>(`/candidates/me/resumes/${resumeId}/analysis/facts/${fact.id}`, token, { method: 'PATCH', body: JSON.stringify({ value }) })
      setNotice('Your review was saved. Downstream requirement matching now uses your verified qualification.');
      setFactDrafts(current => { const next = { ...current }; delete next[fact.id]; return next })
      await loadPortal()
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not save your review') }
    finally { setReviewingFact(null) }
  }
  async function apply(job: Job) {
    if (!selectedResume) { setTab('resumes'); setError('Upload a resume before applying.'); return }
    setBusy(true); setError(''); setNotice('')
    try { await api<Application>(`/jobs/${job.id}/applications`, token, { method: 'POST', body: JSON.stringify({ resumeId: Number(selectedResume) }) }); setNotice(`Application submitted for ${job.title}.`); setApplicationPage(0); if (applicationPage === 0) await loadPortal() }
    catch (e) { setError(e instanceof Error ? e.message : 'Application failed') }
    finally { setBusy(false) }
  }
  async function openJob(jobId: number) {
    setError('')
    try { setDetailJob(await api<Job>(`/jobs/${jobId}`, token)) }
    catch (e) { setError(e instanceof Error ? e.message : 'Could not load job details') }
  }
  async function openApplication(applicationId: number) {
    setLoadingApplicationId(applicationId); setError('')
    try { setDetailApplication(await api<Application>(`/candidates/me/applications/${applicationId}`, token)) }
    catch (e) { setError(e instanceof Error ? e.message : 'Could not load application details') }
    finally { setLoadingApplicationId(null) }
  }
  async function withdraw(application: Application) {
    setBusy(true); setError(''); setNotice('')
    try { await api<Application>(`/candidates/me/applications/${application.id}/withdraw`, token, { method: 'PATCH' }); setNotice('Application withdrawn.'); await loadPortal() }
    catch (e) { setError(e instanceof Error ? e.message : 'Withdrawal failed') }
    finally { setBusy(false) }
  }

  if (!token) return <main className="auth-shell"><section className="auth-card"><div className="brand-mark">R</div><span className="eyebrow">RECRUITPRO · CANDIDATE PORTAL</span><h1>{authMode === 'login' ? 'Welcome back' : 'Create your candidate account'}</h1><p className="muted">Find your next opportunity and keep your applications in one place.</p>
    <form onSubmit={authenticate} className="stack-form">{authMode === 'register' && <><label>First name<input required value={firstName} onChange={e => setFirstName(e.target.value)} /></label><label>Last name<input required value={lastName} onChange={e => setLastName(e.target.value)} /></label></>}<label>Email<input type="email" required value={email} onChange={e => setEmail(e.target.value)} /></label><label>Password<input type="password" minLength={authMode === 'register' ? 12 : 1} required value={password} onChange={e => setPassword(e.target.value)} /></label><button className="primary" disabled={busy}>{busy ? 'Please wait…' : authMode === 'login' ? 'Sign in' : 'Create account'}</button></form>
    <button className="text-button" onClick={() => { setAuthMode(authMode === 'login' ? 'register' : 'login'); setError('') }}>{authMode === 'login' ? 'New here? Create an account' : 'Already registered? Sign in'}</button>{error && <p className="alert error">{error}</p>}</section></main>

  return <div className="app-shell"><aside className="sidebar"><div className="brand"><div className="brand-mark">R</div><span>RecruitPro</span></div><div className="side-label">CANDIDATE SPACE</div><nav>{(['jobs', 'profile', 'resumes', 'applications'] as const).map((item, i) => <button key={item} className={`nav-item ${tab === item ? 'active' : ''}`} onClick={() => { setTab(item); setError(''); setNotice('') }}><span className="nav-icon">{['⌕', '◉', '▤', '▣'][i]}</span>{['Find jobs', 'My profile', 'My resumes', 'Applications'][i]}{item === 'applications' && <span className="nav-count">{applicationTotalElements}</span>}</button>)}</nav><div className="sidebar-bottom"><div className="avatar">{profile.firstName?.[0] || 'C'}</div><div className="account"><strong>{profile.firstName || 'Candidate'} {profile.lastName}</strong><small>{profile.email}</small></div><button className="icon-button" title="Sign out" onClick={signOut}>↗</button></div></aside>
    <main className="main-area"><header className="topbar"><div><span className="eyebrow">CANDIDATE PORTAL</span><h1>{({ jobs: 'Find your next role', profile: 'Your profile', resumes: 'Resume library', applications: 'Your applications' })[tab]}</h1></div><button className="quiet-button" onClick={() => void loadPortal()}>↻ <span>Refresh</span></button></header>
    <div className="content-area">{error && <div className="alert error" role="alert">{error}<button onClick={() => setError('')}>×</button></div>}{notice && <div className="alert success" role="status">{notice}<button onClick={() => setNotice('')}>×</button></div>}{loading ? <div className="loading"><span className="spinner" />Loading your portal…</div> : <>
      {tab === 'jobs' && <section><div className="section-heading"><div><h2>Open opportunities</h2><p className="muted">Explore published roles that match your goals.</p></div><div className="job-tools"><select aria-label="Resume to attach" value={selectedResume} onChange={e => setSelectedResume(e.target.value)}><option value="">Choose resume to apply with</option>{resumes.map(r => <option key={r.id} value={r.id}>{r.originalFilename}</option>)}</select><button className="quiet-button" onClick={() => setTab('resumes')}>Manage resumes</button></div></div><div className="job-filters"><label>Title<input value={search} onChange={e => { setSearch(e.target.value); setJobPage(0) }} placeholder="Search job titles" /></label><label>Department<input value={department} onChange={e => { setDepartment(e.target.value); setJobPage(0) }} placeholder="Any department" /></label><label>Location<input value={location} onChange={e => { setLocation(e.target.value); setJobPage(0) }} placeholder="Any location" /></label><label>Job type<select aria-label="Job type" value={jobType} onChange={e => { setJobType(e.target.value); setJobPage(0) }}><option value="">All job types</option><option value="FULL_TIME">Full time</option><option value="PART_TIME">Part time</option><option value="CONTRACT">Contract</option><option value="INTERNSHIP">Internship</option></select></label></div><div className="job-list">{jobs.length ? jobs.map(job => <article className="job-card" key={job.id}><div className="job-top"><div><span className="tag">{job.department}</span><h3>{job.title}</h3><p className="muted">{job.location} <span className="dot">·</span> {job.jobType.replace('_', ' ')} <span className="dot">·</span> {job.experienceLevel}</p></div><span className="job-symbol">↗</span></div><p className="job-description">{job.description}</p><div className="job-bottom"><span className="salary">{job.minimumSalary || job.maximumSalary ? `${job.minimumSalary ? `₹${job.minimumSalary.toLocaleString()}` : ''}${job.minimumSalary && job.maximumSalary ? ' – ' : ''}${job.maximumSalary ? `₹${job.maximumSalary.toLocaleString()}` : ''}` : 'Salary not listed'}</span><span className="muted">{job.deadline ? `Apply by ${new Date(job.deadline).toLocaleDateString()}` : 'Applications open'}</span><button className="quiet-button" onClick={() => void openJob(job.id)}>View details</button><button className="primary small" disabled={busy} onClick={() => void apply(job)}>Apply now <span>→</span></button></div></article>) : <Empty title="No published jobs found" text="Try a different title or filter, or check back soon." />}</div><Pagination label="Job results" page={jobPage} totalPages={jobTotalPages} totalElements={jobTotalElements} onPageChange={setJobPage} /></section>}
      {tab === 'profile' && <section className="panel"><div className="section-heading"><div><h2>Profile details</h2><p className="muted">Help employers understand your background and interests.</p></div><span className="profile-badge">{profile.firstName?.[0]}{profile.lastName?.[0]}</span></div><div className="identity-row"><div><strong>{profile.firstName} {profile.lastName}</strong><p className="muted">{profile.email}</p></div><span className="tag green">Candidate</span></div><form onSubmit={saveProfile} className="profile-grid">{profileFields.map(([key, label, type]) => <label key={key} className={key === 'address' || key === 'profileSummary' ? 'wide' : ''}>{label}{key === 'address' || key === 'profileSummary' ? <textarea rows={key === 'address' ? 2 : 4} value={String(profile[key] ?? '')} onChange={e => setProfile({ ...profile, [key]: e.target.value } as Profile)} /> : <input type={type || 'text'} min={type === 'number' ? 0 : undefined} value={profile[key] == null ? '' : String(profile[key])} onChange={e => setProfile({ ...profile, [key]: e.target.value } as Profile)} />}</label>)}<div className="wide form-actions"><span className="muted">Your email and account name are managed by your account.</span><button className="primary" disabled={busy}>{busy ? 'Saving…' : 'Save profile'}</button></div></form></section>}
      {tab === 'resumes' && <section><div className="section-heading"><div><h2>Your resumes</h2><p className="muted">Keep a resume ready for each application. Earlier copies stay linked to submitted applications.</p></div></div><form className="upload-panel" onSubmit={uploadResume}><div className="upload-icon">↑</div><div><strong>Upload a resume</strong><p className="muted">PDF, DOC, or DOCX · maximum 10 MB</p><input name="resume" type="file" accept=".pdf,.doc,.docx,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document" /></div><button className="primary" disabled={busy}>{busy ? 'Uploading…' : 'Upload file'}</button></form><div className="resume-list">{resumes.length ? resumes.map(resume => { const analysis = resumeAnalyses[resume.id]; return <article className="resume-row resume-row-expanded" key={resume.id}><div className="file-icon">{resume.originalFilename.split('.').pop()?.toUpperCase() || 'FILE'}</div><div className="resume-info"><strong>{resume.originalFilename}</strong><small>{(resume.fileSize / 1024).toFixed(0)} KB <span className="dot">·</span> Uploaded {new Date(resume.uploadedAt).toLocaleDateString()}</small><div className="analysis-status-line"><span className={`analysis-status analysis-${(analysis?.status || 'PENDING').toLowerCase()}`}>{analysis ? analysisLabel(analysis.status) : 'Analysis unavailable'}</span>{analysis?.status === 'FAILED' && <button className="text-button compact-button" onClick={() => void retryResumeAnalysis(resume.id)}>Retry analysis</button>}</div></div><button className="quiet-button" onClick={() => void downloadResume(resume)}>↓ Download</button><ResumeReviewSection resume={resume} analysis={analysis || null} onRetry={retryResumeAnalysis} onReviewFact={reviewResumeFact} busy={busy || reviewingFact !== null} /></article> }) : <Empty title="No resumes yet" text="Upload a PDF, DOC, or DOCX file before applying to a job." />}</div></section>}
      {tab === 'applications' && <section><div className="section-heading"><div><h2>Application history</h2><p className="muted">Track your submitted applications and their current status.</p></div><span className="stat-pill">{applicationTotalElements} total</span></div><div className="application-list">{applications.length ? applications.map(app => <article className="application-card" key={app.id}><div className="application-main"><div className="job-symbol">▣</div><div><h3>{app.jobTitle}</h3><p className="muted">Applied {new Date(app.appliedAt).toLocaleDateString()} <span className="dot">·</span> Resume: {app.resumeFilename}</p></div></div><div className="application-actions"><span className={`status ${app.status.toLowerCase()}`}>{app.status.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase())}</span><button className="quiet-button" disabled={loadingApplicationId === app.id} onClick={() => void openApplication(app.id)}>{loadingApplicationId === app.id ? 'Loading…' : 'View details'}</button>{app.status === 'SUBMITTED' && <button className="danger-button" disabled={busy} onClick={() => void withdraw(app)}>Withdraw</button>}</div></article>) : <Empty title="No applications yet" text="When you apply to a role, it will appear here." action={() => setTab('jobs')} actionText="Browse jobs" />}</div><Pagination label="Application history" page={applicationPage} totalPages={applicationTotalPages} totalElements={applicationTotalElements} onPageChange={setApplicationPage} /></section>}
    </>}</div></main>{detailJob && <div className="modal-backdrop" role="presentation" onClick={() => setDetailJob(null)}><section className="job-modal" role="dialog" aria-modal="true" aria-labelledby="job-modal-title" onClick={e => e.stopPropagation()}><button className="modal-close" aria-label="Close job details" onClick={() => setDetailJob(null)}>×</button><span className="tag">{detailJob.department}</span><h2 id="job-modal-title">{detailJob.title}</h2><p className="muted">{detailJob.location} · {detailJob.jobType.replace('_', ' ')} · {detailJob.experienceLevel}</p><div className="modal-description">{detailJob.description}</div>{detailJob.requirements?.length ? <><h3>Requirements</h3><ul className="requirements">{detailJob.requirements.map(req => <li key={req.id}><strong>{req.type.replace('_', ' ')}</strong> · {req.description}{req.mandatory && <span className="muted"> · Required</span>}</li>)}</ul></> : <p className="muted">No structured requirements listed.</p>}<button className="primary" disabled={busy} onClick={() => { const job = detailJob; setDetailJob(null); void apply(job) }}>Apply with selected resume</button></section></div>}{detailApplication && <div className="modal-backdrop" role="presentation" onClick={() => setDetailApplication(null)}><section className="job-modal" role="dialog" aria-modal="true" aria-labelledby="application-modal-title" onClick={event => event.stopPropagation()}><button className="modal-close" aria-label="Close application details" onClick={() => setDetailApplication(null)}>×</button><span className="eyebrow">APPLICATION #{detailApplication.id}</span><h2 id="application-modal-title">{detailApplication.jobTitle}</h2><p className="muted">Applied {new Date(detailApplication.appliedAt).toLocaleString()}</p><div className="application-detail-facts"><p><strong>Status</strong><span>{detailApplication.status.replace(/_/g, ' ')}</span></p><p><strong>Resume</strong><span>{detailApplication.resumeFilename}</span></p></div><h3>Cover letter</h3><div className="modal-description">{detailApplication.coverLetter?.trim() || 'No cover letter was included.'}</div></section></div>}</div>
}

function analysisLabel(status: ResumeAnalysis['status']) {
  return ({ PENDING: 'Queued', PROCESSING: 'Analyzing', COMPLETED: 'Analysis ready', PARTIAL: 'Partial analysis', FAILED: 'Analysis failed', NEEDS_OCR: 'Needs OCR', UNSUPPORTED_FORMAT: 'Extraction unsupported' })[status]
}

function Empty({ title, text, action, actionText }: { title: string; text: string; action?: () => void; actionText?: string }) {
  return <div className="empty-state"><div className="empty-icon">⌕</div><strong>{title}</strong><p>{text}</p>{action && <button className="quiet-button" onClick={action}>{actionText}</button>}</div>
}

function Pagination({ label, page, totalPages, totalElements, onPageChange }: { label: string; page: number; totalPages: number; totalElements: number; onPageChange: (page: number) => void }) {
  if (totalPages < 1) return null
  return <nav className="pagination" aria-label={label}><span className="muted">{totalElements} total · Page {page + 1} of {totalPages}</span><div><button className="quiet-button" disabled={page <= 0} onClick={() => onPageChange(page - 1)}>Previous</button><button className="quiet-button" disabled={page >= totalPages - 1} onClick={() => onPageChange(page + 1)}>Next</button></div></nav>
}
