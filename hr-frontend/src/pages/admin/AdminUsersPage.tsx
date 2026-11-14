import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { adminApi } from '../../api/admin'
import { qk } from '../../api/queryKeys'
import { AdminUser, AdminUserParams, STAFF_ROLES, StaffRole } from '../../api/types'
import { useCurrentUser } from '../../auth/AuthContext'
import { StatusBadge } from '../../components/Badges'
import { ConfirmDialog } from '../../components/Dialog'
import { InviteIcon, SearchIcon } from '../../components/Icons'
import { PageHeader } from '../../components/Layout'
import { useNotices } from '../../components/Notices'
import { Pagination } from '../../components/Pagination'
import { EmptyPanel, ErrorPanel, LoadingPanel } from '../../components/States'
import { formatDate, fullName, roleLabel } from '../../lib/format'
import { useDebouncedValue, usePageTitle } from '../../lib/hooks'
import { useSearchState } from '../../lib/useSearchState'

type Change = { user: AdminUser; kind: 'role'; role: StaffRole } | { user: AdminUser; kind: 'status'; enabled: boolean }

/** /admin/users — organisation staff, with role and status changes behind a confirmation. */
export function AdminUsersPage() {
  usePageTitle('Staff accounts')
  const me = useCurrentUser()
  const queryClient = useQueryClient()
  const notices = useNotices()
  const state = useSearchState()
  const role = state.get('role') as StaffRole | ''
  const enabled = state.get('enabled')
  const search = state.get('q')
  const page = state.getPage()
  const [searchInput, setSearchInput] = useState(search)
  const debounced = useDebouncedValue(searchInput.trim(), 300)
  useEffect(() => { setSearchInput(search) }, [search])
  useEffect(() => {
    if (debounced !== search) state.set({ q: debounced }, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debounced])

  const params: AdminUserParams = { page, size: 20, search: search || undefined, role: role || undefined, enabled: enabled === '' ? undefined : enabled === 'true' }
  const users = useQuery({ queryKey: qk.adminUsers(params), queryFn: ({ signal }) => adminApi.users(params, signal) })
  const [change, setChange] = useState<Change | null>(null)

  const apply = useMutation({
    mutationFn: (pending: Change) => pending.kind === 'role' ? adminApi.setUserRole(pending.user.id, pending.role) : adminApi.setUserEnabled(pending.user.id, pending.enabled),
    onSuccess: (updated, pending) => {
      setChange(null)
      void queryClient.invalidateQueries({ queryKey: ['admin', 'users'] })
      void queryClient.invalidateQueries({ queryKey: qk.interviewers })
      notices.success(pending.kind === 'role' ? `${fullName(updated)} is now ${roleLabel(updated.role)}.` : `${fullName(updated)} is ${updated.enabled ? 'enabled' : 'disabled'}.`)
    },
  })

  return (
    <>
      <PageHeader
        eyebrow="ADMINISTRATION"
        title="Staff accounts"
        subtitle="People in your organisation. Changes apply immediately and are recorded in the audit log."
        actions={<Link className="primary-button workspace-primary" to="/admin/invitations"><InviteIcon /> Invite someone</Link>}
      />
      <section className="jobs-card" aria-labelledby="users-heading">
        <div className="jobs-card-head">
          <div><h2 id="users-heading">Staff</h2><p>An organisation always keeps at least one enabled administrator.</p></div>
          <span className="record-count" aria-live="polite">{users.data ? `${users.data.totalElements} people` : '—'}</span>
        </div>
        <div className="jobs-toolbar" role="search">
          <label className="search-field">
            <SearchIcon />
            <span className="sr-only">Search by name or email</span>
            <input type="search" placeholder="Search name or email…" value={searchInput} onChange={event => setSearchInput(event.target.value)} />
          </label>
          <label className="status-filter">
            <span>Role</span>
            <select value={role} onChange={event => state.set({ role: event.target.value })}>
              <option value="">All roles</option>
              {STAFF_ROLES.map(value => <option key={value} value={value}>{roleLabel(value)}</option>)}
            </select>
          </label>
          <label className="status-filter">
            <span>Status</span>
            <select value={enabled} onChange={event => state.set({ enabled: event.target.value })}>
              <option value="">Any</option>
              <option value="true">Enabled</option>
              <option value="false">Disabled</option>
            </select>
          </label>
        </div>
        {users.isPending ? <LoadingPanel label="Loading staff…" /> : users.isError ? (
          <ErrorPanel error={users.error} onRetry={() => void users.refetch()} />
        ) : users.data.content.length === 0 ? <EmptyPanel title="No matching people" /> : (
          <>
            <div className="job-table-wrap">
              <table className="job-table">
                <thead>
                  <tr>
                    <th scope="col">NAME</th>
                    <th scope="col">ROLE</th>
                    <th scope="col">STATUS</th>
                    <th scope="col">JOINED</th>
                    <th scope="col"><span className="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {users.data.content.map(user => {
                    const self = user.id === me.id
                    return (
                      <tr key={user.id}>
                        <td><strong className="cell-title">{fullName(user)}{self ? ' (you)' : ''}</strong><span className="job-meta">{user.email}</span></td>
                        <td>
                          {self || user.role === 'CANDIDATE' ? roleLabel(user.role) : (
                            <label>
                              <span className="sr-only">Role of {fullName(user)}</span>
                              <select className="inline-select" value={user.role} onChange={event => { apply.reset(); setChange({ user, kind: 'role', role: event.target.value as StaffRole }) }}>
                                {STAFF_ROLES.map(value => <option key={value} value={value}>{roleLabel(value)}</option>)}
                              </select>
                            </label>
                          )}
                        </td>
                        <td><StatusBadge status={user.enabled ? 'ENABLED' : 'DISABLED'} /></td>
                        <td>{formatDate(user.createdAt)}</td>
                        <td>
                          {!self && (
                            <button type="button" className={user.enabled ? 'outline-button danger-outline' : 'outline-button'} onClick={() => { apply.reset(); setChange({ user, kind: 'status', enabled: !user.enabled }) }}>
                              {user.enabled ? 'Disable' : 'Enable'}
                            </button>
                          )}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
            <Pagination page={users.data} onPage={next => state.set({ page: next })} label="people" />
          </>
        )}
      </section>

      <ConfirmDialog
        open={change != null}
        title={change?.kind === 'role' ? 'Change role?' : change?.kind === 'status' && !change.enabled ? 'Disable this account?' : 'Enable this account?'}
        message={change ? (change.kind === 'role'
          ? `${fullName(change.user)} changes from ${roleLabel(change.user.role)} to ${roleLabel(change.role)}. Their access changes on their next request.`
          : change.enabled ? `${fullName(change.user)} can sign in again.` : `${fullName(change.user)} is signed out and cannot sign in until re-enabled.`) : ''}
        confirmLabel={change?.kind === 'role' ? 'Change role' : change?.kind === 'status' && !change.enabled ? 'Disable' : 'Enable'}
        danger={change?.kind === 'status' && !change.enabled}
        busy={apply.isPending}
        error={apply.error}
        onCancel={() => setChange(null)}
        onConfirm={() => { if (change) apply.mutate(change) }}
      />
    </>
  )
}
