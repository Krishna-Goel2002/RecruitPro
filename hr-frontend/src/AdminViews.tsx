import { FormEvent, useCallback, useEffect, useState } from 'react'

export type Role = 'ADMIN' | 'HR_MANAGER' | 'RECRUITER' | 'INTERVIEWER' | 'CANDIDATE'
export type Session = { accessToken: string; refreshToken: string; role: Role; email: string; firstName: string; lastName: string }

export type AdminDashboardMetrics = {
  totalUsers: number
  totalCandidates: number
  totalHrUsers: number
  totalJobs: number
  totalApplications: number
  applicationsByStatus: Record<string, number>
  jobsByStatus: Record<string, number>
  recentApplicationsCount: number
  backendHealth: string
  databaseHealth: string
  aiServiceHealth: string
}

export type AdminUser = {
  id: number
  email: string
  firstName: string
  lastName: string
  role: Role
  enabled: boolean
  createdAt: string
}

export type AdminHrMember = {
  userId: number
  hrProfileId: number
  email: string
  fullName: string
  department: string
  designation: string
  employeeId: string
  role: Role
  enabled: boolean
  jobsCount: number
}

export type AdminJobOverview = {
  id: number
  title: string
  department: string
  location: string
  jobType: string
  experienceLevel: string
  status: string
  vacancies: number
  createdByEmail: string
  createdAt: string
  applicationCount: number
}

export type AdminAuditRecord = {
  id: string
  action: string
  actorEmail: string
  actorUserId: number
  targetResourceType: string
  targetResourceId: string
  details: string
  timestamp: string
}

type Page<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number; first: boolean; last: boolean }
type Envelope<T> = { success: boolean; message?: string; data?: T; errors?: { field: string; message: string }[] }

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1').replace(/\/$/, '')

async function apiRequest<T>(session: Session, path: string, onExpired: () => void, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${session.accessToken}`,
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...init.headers,
    },
  })
  let envelope: Envelope<T> | null = null
  try {
    envelope = await response.json() as Envelope<T>
  } catch { /* empty */ }

  if (response.status === 401) {
    onExpired()
    throw new Error('Your session has expired. Sign in again to continue.')
  }
  if (!response.ok || envelope?.success !== true) {
    const details = envelope?.errors?.map(err => `${err.field}: ${err.message}`).join(' · ')
    throw new Error(details || envelope?.message || `Request failed (${response.status}).`)
  }
  return envelope.data as T
}

function formatDate(iso?: string) {
  if (!iso) return '—'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

function formatRole(role: string) {
  return role.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase())
}

/* ========================================================================= */
/* 1. ADMIN DASHBOARD VIEW                                                   */
/* ========================================================================= */

export function AdminDashboardView({ session, onSessionExpired }: { session: Session; onSessionExpired: () => void }) {
  const [metrics, setMetrics] = useState<AdminDashboardMetrics | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const loadMetrics = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const data = await apiRequest<AdminDashboardMetrics>(session, '/admin/dashboard', onSessionExpired)
      setMetrics(data)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load dashboard metrics.')
    } finally {
      setLoading(false)
    }
  }, [session, onSessionExpired])

  useEffect(() => {
    void loadMetrics()
  }, [loadMetrics])

  if (loading && !metrics) {
    return <div className="state-panel"><span className="spinner dark-spinner" /><p>Loading platform overview…</p></div>
  }

  if (error && !metrics) {
    return (
      <div className="state-panel error-state">
        <div className="state-icon">!</div>
        <h3>Failed to load platform overview</h3>
        <p>{error}</p>
        <button className="outline-button" onClick={() => void loadMetrics()}>Try again</button>
      </div>
    )
  }

  return (
    <div className="admin-dashboard-container">
      <div className="page-title-row">
        <div>
          <p className="eyebrow">PLATFORM ADMINISTRATION</p>
          <h1>Platform Overview</h1>
          <p className="page-subtitle">Real-time system health, aggregate counts, and recruitment pipeline funnel.</p>
        </div>
        <button className="outline-button" onClick={() => void loadMetrics()}>Refresh metrics</button>
      </div>

      {error && <div className="inline-notice applicant-error">{error}</div>}

      {/* KPI Stat Grid */}
      <section className="stat-grid" aria-label="Platform counts">
        <article className="stat-card">
          <div className="stat-icon"><UsersIcon /></div>
          <div>
            <span className="stat-label">Total Users</span>
            <strong className="stat-value">{metrics?.totalUsers ?? 0}</strong>
          </div>
        </article>
        <article className="stat-card">
          <div className="stat-icon published"><UserCheckIcon /></div>
          <div>
            <span className="stat-label">Candidates</span>
            <strong className="stat-value">{metrics?.totalCandidates ?? 0}</strong>
          </div>
        </article>
        <article className="stat-card">
          <div className="stat-icon draft"><BriefcaseIcon /></div>
          <div>
            <span className="stat-label">HR & Recruiters</span>
            <strong className="stat-value">{metrics?.totalHrUsers ?? 0}</strong>
          </div>
        </article>
        <article className="stat-card">
          <div className="stat-icon closed"><BriefcaseIcon /></div>
          <div>
            <span className="stat-label">Total Jobs</span>
            <strong className="stat-value">{metrics?.totalJobs ?? 0}</strong>
          </div>
        </article>
        <article className="stat-card">
          <div className="stat-icon"><DocumentIcon /></div>
          <div>
            <span className="stat-label">Applications</span>
            <strong className="stat-value">{metrics?.totalApplications ?? 0}</strong>
          </div>
        </article>
      </section>

      {/* System Health Indicators */}
      <section className="jobs-card" style={{ marginBottom: '24px' }}>
        <div className="jobs-card-head">
          <div>
            <h2>Infrastructure & Service Health</h2>
            <p>Component availability and connectivity status.</p>
          </div>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '16px', padding: '20px 22px' }}>
          <HealthCard title="Backend API" status={metrics?.backendHealth ?? 'UNKNOWN'} />
          <HealthCard title="Database" status={metrics?.databaseHealth ?? 'UNKNOWN'} />
          <HealthCard title="AI Intelligence Service" status={metrics?.aiServiceHealth ?? 'UNKNOWN'} />
        </div>
      </section>

      {/* Funnel Breakdown */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '20px' }}>
        <section className="jobs-card">
          <div className="jobs-card-head">
            <div>
              <h2>Application Status Funnel</h2>
              <p>Breakdown across recruitment lifecycle stages.</p>
            </div>
            <span className="record-count">{metrics?.totalApplications ?? 0} total</span>
          </div>
          <div style={{ padding: '16px 22px' }}>
            {metrics?.applicationsByStatus && Object.keys(metrics.applicationsByStatus).length > 0 ? (
              Object.entries(metrics.applicationsByStatus).map(([status, count]) => (
                <div key={status} style={{ display: 'flex', justifyContent: 'space-between', padding: '10px 0', borderBottom: '1px solid #f0f3f4' }}>
                  <span className={`status-badge status-${status.toLowerCase()}`}>{formatRole(status)}</span>
                  <strong>{count}</strong>
                </div>
              ))
            ) : (
              <p className="applicant-muted">No applications recorded yet.</p>
            )}
          </div>
        </section>

        <section className="jobs-card">
          <div className="jobs-card-head">
            <div>
              <h2>Job Opportunities by Status</h2>
              <p>Distribution of open, draft, and closed positions.</p>
            </div>
            <span className="record-count">{metrics?.totalJobs ?? 0} total</span>
          </div>
          <div style={{ padding: '16px 22px' }}>
            {metrics?.jobsByStatus && Object.keys(metrics.jobsByStatus).length > 0 ? (
              Object.entries(metrics.jobsByStatus).map(([status, count]) => (
                <div key={status} style={{ display: 'flex', justifyContent: 'space-between', padding: '10px 0', borderBottom: '1px solid #f0f3f4' }}>
                  <span className={`status-badge status-${status.toLowerCase()}`}>{formatRole(status)}</span>
                  <strong>{count}</strong>
                </div>
              ))
            ) : (
              <p className="applicant-muted">No jobs recorded yet.</p>
            )}
          </div>
        </section>
      </div>
    </div>
  )
}

function HealthCard({ title, status }: { title: string; status: string }) {
  const isUp = status.toUpperCase() === 'UP'
  return (
    <div style={{ padding: '16px', borderRadius: '8px', border: '1px solid #e1e9e9', background: '#fafcfc', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
      <div>
        <strong style={{ display: 'block', fontSize: '13px', color: '#2d454a' }}>{title}</strong>
        <small style={{ color: '#88999a' }}>System Check</small>
      </div>
      <span className={`status-badge ${isUp ? 'status-published' : 'status-cancelled'}`}>
        <i /> {status}
      </span>
    </div>
  )
}

/* ========================================================================= */
/* 2. ADMIN USER MANAGEMENT VIEW                                             */
/* ========================================================================= */

const ALL_ROLES: Role[] = ['ADMIN', 'HR_MANAGER', 'RECRUITER', 'INTERVIEWER', 'CANDIDATE']

export function AdminUsersView({ session, onSessionExpired }: { session: Session; onSessionExpired: () => void }) {
  const [usersPage, setUsersPage] = useState<Page<AdminUser> | null>(null)
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState('')
  const [searchInput, setSearchInput] = useState('')
  const [roleFilter, setRoleFilter] = useState('ALL')
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [updatingUserId, setUpdatingUserId] = useState<number | null>(null)

  const loadUsers = useCallback(async () => {
    setLoading(true)
    setError('')
    const params = new URLSearchParams({ page: String(page), size: '20' })
    if (search.trim()) params.set('search', search.trim())
    if (roleFilter !== 'ALL') params.set('role', roleFilter)
    if (statusFilter !== 'ALL') params.set('enabled', String(statusFilter === 'ENABLED'))

    try {
      const data = await apiRequest<Page<AdminUser>>(session, `/admin/users?${params}`, onSessionExpired)
      setUsersPage(data)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load users.')
    } finally {
      setLoading(false)
    }
  }, [session, page, search, roleFilter, statusFilter, onSessionExpired])

  useEffect(() => {
    void loadUsers()
  }, [loadUsers])

  async function handleToggleStatus(user: AdminUser) {
    const action = user.enabled ? 'deactivate' : 'activate'
    if (!window.confirm(`Are you sure you want to ${action} user ${user.email}?`)) return

    setUpdatingUserId(user.id)
    setError('')
    setNotice('')
    try {
      const updated = await apiRequest<AdminUser>(session, `/admin/users/${user.id}/status`, onSessionExpired, {
        method: 'PATCH',
        body: JSON.stringify({ enabled: !user.enabled }),
      })
      setNotice(`User ${updated.email} is now ${updated.enabled ? 'active' : 'deactivated'}.`)
      await loadUsers()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Status update failed.')
    } finally {
      setUpdatingUserId(null)
    }
  }

  async function handleChangeRole(user: AdminUser, newRole: Role) {
    if (user.role === newRole) return
    if (!window.confirm(`Change role for ${user.email} from ${user.role} to ${newRole}?`)) return

    setUpdatingUserId(user.id)
    setError('')
    setNotice('')
    try {
      const updated = await apiRequest<AdminUser>(session, `/admin/users/${user.id}/role`, onSessionExpired, {
        method: 'PATCH',
        body: JSON.stringify({ role: newRole }),
      })
      setNotice(`Role for ${updated.email} updated to ${updated.role}.`)
      await loadUsers()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Role change failed.')
    } finally {
      setUpdatingUserId(null)
    }
  }

  const users = usersPage?.content ?? []
  const startIndex = usersPage && usersPage.totalElements ? usersPage.page * usersPage.size + 1 : 0
  const endIndex = usersPage ? Math.min((usersPage.page + 1) * usersPage.size, usersPage.totalElements) : 0

  return (
    <div className="admin-users-container">
      <div className="page-title-row">
        <div>
          <p className="eyebrow">ACCESS & ACCOUNTS</p>
          <h1>User Management</h1>
          <p className="page-subtitle">Search, inspect account roles, activate/deactivate accounts, and adjust permissions.</p>
        </div>
      </div>

      {notice && <div className="inline-notice" role="status"><span>{notice}</span><button onClick={() => setNotice('')}>×</button></div>}
      {error && <div className="inline-notice applicant-error" role="alert"><span>{error}</span><button onClick={() => setError('')}>×</button></div>}

      <section className="jobs-card">
        <div className="jobs-card-head">
          <div>
            <h2>User Accounts</h2>
            <p>Direct platform registry with strict authorization protection.</p>
          </div>
          <span className="record-count">{usersPage ? `${usersPage.totalElements} accounts` : '—'}</span>
        </div>

        <form className="jobs-toolbar" onSubmit={e => { e.preventDefault(); setPage(0); setSearch(searchInput.trim()) }}>
          <label className="search-field">
            <SearchIcon />
            <input
              placeholder="Search by name or email…"
              value={searchInput}
              onChange={e => setSearchInput(e.target.value)}
            />
            <button type="submit">Search</button>
          </label>
          <label className="status-filter">
            <span>Role</span>
            <select value={roleFilter} onChange={e => { setRoleFilter(e.target.value); setPage(0) }}>
              <option value="ALL">All roles</option>
              {ALL_ROLES.map(r => <option key={r} value={r}>{formatRole(r)}</option>)}
            </select>
          </label>
          <label className="status-filter">
            <span>Status</span>
            <select value={statusFilter} onChange={e => { setStatusFilter(e.target.value); setPage(0) }}>
              <option value="ALL">All states</option>
              <option value="ENABLED">Active</option>
              <option value="DISABLED">Deactivated</option>
            </select>
          </label>
        </form>

        {loading && !usersPage ? (
          <div className="state-panel"><span className="spinner dark-spinner" /><p>Loading accounts…</p></div>
        ) : users.length === 0 ? (
          <div className="state-panel empty-state">
            <span className="empty-icon"><UsersIcon /></span>
            <h3>No users found</h3>
            <p>No account matches the current filter criteria.</p>
          </div>
        ) : (
          <>
            <div className="job-table-wrap">
              <table className="job-table">
                <thead>
                  <tr>
                    <th>NAME & EMAIL</th>
                    <th>CURRENT ROLE</th>
                    <th>STATUS</th>
                    <th>REGISTERED</th>
                    <th>CHANGE ROLE</th>
                    <th>ACTION</th>
                  </tr>
                </thead>
                <tbody>
                  {users.map(u => (
                    <tr key={u.id}>
                      <td>
                        <strong>{u.firstName} {u.lastName}</strong>
                        <span className="job-meta">{u.email}</span>
                      </td>
                      <td>
                        <span className="status-badge" style={{ background: '#eaf3f8', color: '#2b5f75' }}>
                          {formatRole(u.role)}
                        </span>
                      </td>
                      <td>
                        <span className={`status-badge ${u.enabled ? 'status-published' : 'status-cancelled'}`}>
                          <i /> {u.enabled ? 'Active' : 'Disabled'}
                        </span>
                      </td>
                      <td>{formatDate(u.createdAt)}</td>
                      <td>
                        <select
                          disabled={updatingUserId === u.id}
                          value={u.role}
                          onChange={e => void handleChangeRole(u, e.target.value as Role)}
                          style={{ fontSize: '11px', padding: '4px 8px', borderRadius: '6px', border: '1px solid #dfe7e9' }}
                        >
                          {ALL_ROLES.map(r => <option key={r} value={r}>{formatRole(r)}</option>)}
                        </select>
                      </td>
                      <td>
                        <button
                          className="outline-button"
                          disabled={updatingUserId === u.id || (u.email.toLowerCase() === session.email.toLowerCase() && u.enabled)}
                          onClick={() => void handleToggleStatus(u)}
                          style={{ color: u.enabled ? '#b04a44' : '#287355' }}
                        >
                          {updatingUserId === u.id ? 'Saving…' : u.enabled ? 'Deactivate' : 'Activate'}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="table-footer">
              <span>Showing {startIndex}–{endIndex} of {usersPage?.totalElements ?? 0}</span>
              <div className="pagination">
                <button
                  className="outline-button"
                  disabled={usersPage?.first || loading}
                  onClick={() => setPage(p => Math.max(0, p - 1))}
                >
                  Previous
                </button>
                <span>Page {(usersPage?.page ?? 0) + 1} of {Math.max(usersPage?.totalPages ?? 1, 1)}</span>
                <button
                  className="outline-button"
                  disabled={usersPage?.last || loading}
                  onClick={() => setPage(p => p + 1)}
                >
                  Next
                </button>
              </div>
            </div>
          </>
        )}
      </section>
    </div>
  )
}

/* ========================================================================= */
/* 3. ADMIN HR & RECRUITER DIRECTORY                                         */
/* ========================================================================= */

export function AdminHrTeamView({ session, onSessionExpired }: { session: Session; onSessionExpired: () => void }) {
  const [team, setTeam] = useState<AdminHrMember[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const loadTeam = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const data = await apiRequest<AdminHrMember[]>(session, '/admin/hr-team', onSessionExpired)
      setTeam(data)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load HR directory.')
    } finally {
      setLoading(false)
    }
  }, [session, onSessionExpired])

  useEffect(() => {
    void loadTeam()
  }, [loadTeam])

  return (
    <div className="admin-hr-team-container">
      <div className="page-title-row">
        <div>
          <p className="eyebrow">STAFF & OPERATIONS</p>
          <h1>HR & Recruiter Team</h1>
          <p className="page-subtitle">Directory of internal talent personnel, assigned departments, and active managed openings.</p>
        </div>
        <button className="outline-button" onClick={() => void loadTeam()}>Refresh</button>
      </div>

      {error && <div className="inline-notice applicant-error">{error}</div>}

      <section className="jobs-card">
        <div className="jobs-card-head">
          <div>
            <h2>Talent Acquisition Personnel</h2>
            <p>All active HR Managers and Recruiters.</p>
          </div>
          <span className="record-count">{team.length} members</span>
        </div>

        {loading ? (
          <div className="state-panel"><span className="spinner dark-spinner" /><p>Loading team members…</p></div>
        ) : team.length === 0 ? (
          <div className="state-panel empty-state">
            <span className="empty-icon"><UsersIcon /></span>
            <h3>No HR personnel registered</h3>
            <p>Promote or register HR managers and recruiters to view them here.</p>
          </div>
        ) : (
          <div className="job-table-wrap">
            <table className="job-table">
              <thead>
                <tr>
                  <th>NAME & EMAIL</th>
                  <th>EMPLOYEE ID</th>
                  <th>DEPARTMENT</th>
                  <th>DESIGNATION</th>
                  <th>ROLE</th>
                  <th>JOBS MANAGED</th>
                  <th>STATUS</th>
                </tr>
              </thead>
              <tbody>
                {team.map(m => (
                  <tr key={m.userId}>
                    <td>
                      <strong>{m.fullName}</strong>
                      <span className="job-meta">{m.email}</span>
                    </td>
                    <td><code>{m.employeeId || '—'}</code></td>
                    <td>{m.department || '—'}</td>
                    <td>{m.designation || '—'}</td>
                    <td><span className="status-badge" style={{ background: '#f0f3f6' }}>{formatRole(m.role)}</span></td>
                    <td><strong>{m.jobsCount}</strong></td>
                    <td>
                      <span className={`status-badge ${m.enabled ? 'status-published' : 'status-cancelled'}`}>
                        <i /> {m.enabled ? 'Active' : 'Disabled'}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  )
}

/* ========================================================================= */
/* 4. ADMIN JOB OVERSIGHT VIEW                                               */
/* ========================================================================= */

export function AdminJobOversightView({ session, onSessionExpired }: { session: Session; onSessionExpired: () => void }) {
  const [jobsPage, setJobsPage] = useState<Page<AdminJobOverview> | null>(null)
  const [page, setPage] = useState(0)
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [search, setSearch] = useState('')
  const [searchInput, setSearchInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [overrideTarget, setOverrideTarget] = useState<AdminJobOverview | null>(null)
  const [overrideStatus, setOverrideStatus] = useState<'ON_HOLD' | 'CLOSED' | 'CANCELLED'>('ON_HOLD')
  const [overrideReason, setOverrideReason] = useState('')
  const [overrideBusy, setOverrideBusy] = useState(false)

  const loadJobs = useCallback(async () => {
    setLoading(true)
    setError('')
    const params = new URLSearchParams({ page: String(page), size: '20' })
    if (statusFilter !== 'ALL') params.set('status', statusFilter)
    if (search.trim()) params.set('search', search.trim())

    try {
      const data = await apiRequest<Page<AdminJobOverview>>(session, `/admin/jobs?${params}`, onSessionExpired)
      setJobsPage(data)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load job oversight data.')
    } finally {
      setLoading(false)
    }
  }, [session, page, statusFilter, search, onSessionExpired])

  useEffect(() => {
    void loadJobs()
  }, [loadJobs])

  async function handleOverrideSubmit(e: FormEvent) {
    e.preventDefault()
    if (!overrideTarget) return
    if (!overrideReason.trim()) {
      setError('An audit reason is required for administrative status override.')
      return
    }

    setOverrideBusy(true)
    setError('')
    setNotice('')
    try {
      const updated = await apiRequest<AdminJobOverview>(session, `/admin/jobs/${overrideTarget.id}/status`, onSessionExpired, {
        method: 'PATCH',
        body: JSON.stringify({
          status: overrideStatus,
          reason: overrideReason.trim(),
        }),
      })
      setNotice(`Job #${updated.id} (${updated.title}) status changed to ${updated.status}.`)
      setOverrideTarget(null)
      setOverrideReason('')
      await loadJobs()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Failed to override job status.')
    } finally {
      setOverrideBusy(false)
    }
  }

  const jobs = jobsPage?.content ?? []
  const startIndex = jobsPage && jobsPage.totalElements ? jobsPage.page * jobsPage.size + 1 : 0
  const endIndex = jobsPage ? Math.min((jobsPage.page + 1) * jobsPage.size, jobsPage.totalElements) : 0

  return (
    <div className="admin-job-oversight-container">
      <div className="page-title-row">
        <div>
          <p className="eyebrow">GLOBAL SUPERVISION</p>
          <h1>Platform-Wide Job Oversight</h1>
          <p className="page-subtitle">Monitor job postings across all HR managers and exercise administrative status overrides.</p>
        </div>
      </div>

      {notice && <div className="inline-notice" role="status"><span>{notice}</span><button onClick={() => setNotice('')}>×</button></div>}
      {error && <div className="inline-notice applicant-error" role="alert"><span>{error}</span><button onClick={() => setError('')}>×</button></div>}

      <section className="jobs-card">
        <div className="jobs-card-head">
          <div>
            <h2>All System Openings</h2>
            <p>Unified view of all recruitment campaigns on RecruitPro.</p>
          </div>
          <span className="record-count">{jobsPage ? `${jobsPage.totalElements} postings` : '—'}</span>
        </div>

        <form className="jobs-toolbar" onSubmit={e => { e.preventDefault(); setPage(0); setSearch(searchInput.trim()) }}>
          <label className="search-field">
            <SearchIcon />
            <input
              placeholder="Search by title or department…"
              value={searchInput}
              onChange={e => setSearchInput(e.target.value)}
            />
            <button type="submit">Search</button>
          </label>
          <label className="status-filter">
            <span>Status</span>
            <select value={statusFilter} onChange={e => { setStatusFilter(e.target.value); setPage(0) }}>
              <option value="ALL">All statuses</option>
              <option value="PUBLISHED">Published</option>
              <option value="ON_HOLD">On Hold</option>
              <option value="DRAFT">Draft</option>
              <option value="CLOSED">Closed</option>
              <option value="CANCELLED">Cancelled</option>
            </select>
          </label>
        </form>

        {loading && !jobsPage ? (
          <div className="state-panel"><span className="spinner dark-spinner" /><p>Loading jobs…</p></div>
        ) : jobs.length === 0 ? (
          <div className="state-panel empty-state">
            <span className="empty-icon"><BriefcaseIcon /></span>
            <h3>No jobs found</h3>
            <p>No job records match the specified filters.</p>
          </div>
        ) : (
          <>
            <div className="job-table-wrap">
              <table className="job-table">
                <thead>
                  <tr>
                    <th>JOB TITLE</th>
                    <th>DEPARTMENT & LOCATION</th>
                    <th>CREATED BY</th>
                    <th>STATUS</th>
                    <th>APPLICANTS</th>
                    <th>CREATED</th>
                    <th>ADMIN OVERRIDE</th>
                  </tr>
                </thead>
                <tbody>
                  {jobs.map(j => (
                    <tr key={j.id}>
                      <td>
                        <strong>{j.title}</strong>
                        <span className="job-meta">{formatRole(j.jobType)} · {formatRole(j.experienceLevel)}</span>
                      </td>
                      <td>
                        <span>{j.department}</span>
                        <span className="job-meta">{j.location}</span>
                      </td>
                      <td>
                        <span style={{ fontSize: '11px' }}>{j.createdByEmail}</span>
                      </td>
                      <td>
                        <span className={`status-badge status-${j.status.toLowerCase()}`}>
                          <i /> {formatRole(j.status)}
                        </span>
                      </td>
                      <td><strong>{j.applicationCount}</strong></td>
                      <td>{formatDate(j.createdAt)}</td>
                      <td>
                        <button
                          className="outline-button"
                          onClick={() => {
                            setOverrideTarget(j)
                            setOverrideStatus(j.status === 'ON_HOLD' ? 'CLOSED' : 'ON_HOLD')
                            setOverrideReason('')
                          }}
                        >
                          Override…
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="table-footer">
              <span>Showing {startIndex}–{endIndex} of {jobsPage?.totalElements ?? 0}</span>
              <div className="pagination">
                <button
                  className="outline-button"
                  disabled={jobsPage?.first || loading}
                  onClick={() => setPage(p => Math.max(0, p - 1))}
                >
                  Previous
                </button>
                <span>Page {(jobsPage?.page ?? 0) + 1} of {Math.max(jobsPage?.totalPages ?? 1, 1)}</span>
                <button
                  className="outline-button"
                  disabled={jobsPage?.last || loading}
                  onClick={() => setPage(p => p + 1)}
                >
                  Next
                </button>
              </div>
            </div>
          </>
        )}
      </section>

      {/* Override Modal */}
      {overrideTarget && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(16,42,51,.6)', display: 'grid', placeItems: 'center', zIndex: 100, padding: '20px' }}>
          <div style={{ background: '#fff', borderRadius: '12px', padding: '28px', maxWidth: '500px', width: '100%', boxShadow: '0 20px 60px rgba(0,0,0,.2)' }}>
            <h3 style={{ margin: '0 0 8px', font: '700 18px Manrope, sans-serif', color: '#172a32' }}>Administrative Status Override</h3>
            <p style={{ margin: '0 0 16px', color: '#6d7f84', fontSize: '12px' }}>
              Overriding <strong>{overrideTarget.title}</strong> (Current: {overrideTarget.status}). This action is logged for security audits.
            </p>

            <form onSubmit={e => void handleOverrideSubmit(e)}>
              <div style={{ marginBottom: '14px' }}>
                <label style={{ display: 'block', fontSize: '11px', fontWeight: 600, color: '#31474f', marginBottom: '6px' }}>Target Status</label>
                <select
                  value={overrideStatus}
                  onChange={e => setOverrideStatus(e.target.value as 'ON_HOLD' | 'CLOSED' | 'CANCELLED')}
                  style={{ width: '100%', height: '38px', borderRadius: '8px', border: '1px solid #dfe7e9', padding: '0 10px' }}
                >
                  <option value="ON_HOLD">Put On Hold</option>
                  <option value="CLOSED">Close Job</option>
                  <option value="CANCELLED">Cancel Job</option>
                </select>
              </div>

              <div style={{ marginBottom: '20px' }}>
                <label style={{ display: 'block', fontSize: '11px', fontWeight: 600, color: '#31474f', marginBottom: '6px' }}>
                  Audit Reason <span style={{ color: '#d45656' }}>*</span>
                </label>
                <textarea
                  required
                  rows={3}
                  maxLength={500}
                  placeholder="Explain why this status is being changed by an administrator…"
                  value={overrideReason}
                  onChange={e => setOverrideReason(e.target.value)}
                  style={{ width: '100%', borderRadius: '8px', border: '1px solid #dfe7e9', padding: '8px', font: 'inherit', fontSize: '12px', boxSizing: 'border-box' }}
                />
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px' }}>
                <button
                  type="button"
                  className="outline-button"
                  disabled={overrideBusy}
                  onClick={() => setOverrideTarget(null)}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="primary-button workspace-primary"
                  disabled={overrideBusy}
                >
                  {overrideBusy ? 'Applying…' : 'Confirm Override'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}

/* ========================================================================= */
/* 5. ADMIN AUDIT TRAIL VIEW                                                 */
/* ========================================================================= */

export function AdminAuditLogsView({ session, onSessionExpired }: { session: Session; onSessionExpired: () => void }) {
  const [logsPage, setLogsPage] = useState<Page<AdminAuditRecord> | null>(null)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const loadLogs = useCallback(async () => {
    setLoading(true)
    setError('')
    const params = new URLSearchParams({ page: String(page), size: '20' })
    try {
      const data = await apiRequest<Page<AdminAuditRecord>>(session, `/admin/audit-logs?${params}`, onSessionExpired)
      setLogsPage(data)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load audit trail.')
    } finally {
      setLoading(false)
    }
  }, [session, page, onSessionExpired])

  useEffect(() => {
    void loadLogs()
  }, [loadLogs])

  const logs = logsPage?.content ?? []
  const startIndex = logsPage && logsPage.totalElements ? logsPage.page * logsPage.size + 1 : 0
  const endIndex = logsPage ? Math.min((logsPage.page + 1) * logsPage.size, logsPage.totalElements) : 0

  return (
    <div className="admin-audit-logs-container">
      <div className="page-title-row">
        <div>
          <p className="eyebrow">COMPLIANCE & GOVERNANCE</p>
          <h1>Platform Audit Trail</h1>
          <p className="page-subtitle">Immutable chronological log of privileged administrative actions and security events.</p>
        </div>
        <button className="outline-button" onClick={() => void loadLogs()}>Refresh</button>
      </div>

      {error && <div className="inline-notice applicant-error">{error}</div>}

      <section className="jobs-card">
        <div className="jobs-card-head">
          <div>
            <h2>Administrative Operations Log</h2>
            <p>Actor, target resource, description, and timestamp.</p>
          </div>
          <span className="record-count">{logsPage ? `${logsPage.totalElements} events` : '—'}</span>
        </div>

        {loading && !logsPage ? (
          <div className="state-panel"><span className="spinner dark-spinner" /><p>Loading audit trail…</p></div>
        ) : logs.length === 0 ? (
          <div className="state-panel empty-state">
            <span className="empty-icon"><ShieldCheckIcon /></span>
            <h3>No audit records yet</h3>
            <p>Privileged administrative actions will be recorded here automatically.</p>
          </div>
        ) : (
          <>
            <div className="job-table-wrap">
              <table className="job-table">
                <thead>
                  <tr>
                    <th>TIMESTAMP</th>
                    <th>ACTION</th>
                    <th>ACTOR</th>
                    <th>TARGET</th>
                    <th>DETAILS</th>
                  </tr>
                </thead>
                <tbody>
                  {logs.map(log => (
                    <tr key={log.id}>
                      <td><code style={{ fontSize: '10px' }}>{formatDate(log.timestamp)}</code></td>
                      <td>
                        <span className="status-badge" style={{ background: '#f5f0fa', color: '#664082', fontWeight: 700 }}>
                          {log.action}
                        </span>
                      </td>
                      <td>
                        <strong>{log.actorEmail}</strong>
                        {log.actorUserId && <span className="job-meta">ID #{log.actorUserId}</span>}
                      </td>
                      <td>
                        <span className="status-badge" style={{ background: '#f0f4f4' }}>
                          {log.targetResourceType} #{log.targetResourceId}
                        </span>
                      </td>
                      <td style={{ maxWidth: '400px', whiteSpace: 'normal', fontSize: '11px', lineHeight: '1.4' }}>
                        {log.details}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="table-footer">
              <span>Showing {startIndex}–{endIndex} of {logsPage?.totalElements ?? 0}</span>
              <div className="pagination">
                <button
                  className="outline-button"
                  disabled={logsPage?.first || loading}
                  onClick={() => setPage(p => Math.max(0, p - 1))}
                >
                  Previous
                </button>
                <span>Page {(logsPage?.page ?? 0) + 1} of {Math.max(logsPage?.totalPages ?? 1, 1)}</span>
                <button
                  className="outline-button"
                  disabled={logsPage?.last || loading}
                  onClick={() => setPage(p => p + 1)}
                >
                  Next
                </button>
              </div>
            </div>
          </>
        )}
      </section>
    </div>
  )
}

/* ========================================================================= */
/* ICONS                                                                     */
/* ========================================================================= */

function UsersIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" style={{ width: 18, height: 18 }}>
      <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
      <circle cx="9" cy="7" r="4" />
      <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
      <path d="M16 3.13a4 4 0 0 1 0 7.75" />
    </svg>
  )
}

function UserCheckIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" style={{ width: 18, height: 18 }}>
      <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
      <circle cx="8.5" cy="7" r="4" />
      <polyline points="17 11 19 13 23 9" />
    </svg>
  )
}

function BriefcaseIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" style={{ width: 18, height: 18 }}>
      <rect x="2" y="7" width="20" height="14" rx="2" ry="2" />
      <path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16" />
    </svg>
  )
}

function DocumentIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" style={{ width: 18, height: 18 }}>
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
      <polyline points="14 2 14 8 20 8" />
      <line x1="16" y1="13" x2="8" y2="13" />
      <line x1="16" y1="17" x2="8" y2="17" />
      <polyline points="10 9 9 9 8 9" />
    </svg>
  )
}

function SearchIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" style={{ width: 15, height: 15, marginLeft: 11, color: '#93a2a2' }}>
      <circle cx="10.8" cy="10.8" r="6.8" />
      <path d="m16 16 4.5 4.5" />
    </svg>
  )
}

function ShieldCheckIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" style={{ width: 22, height: 22 }}>
      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
      <polyline points="9 12 11 14 15 10" />
    </svg>
  )
}
