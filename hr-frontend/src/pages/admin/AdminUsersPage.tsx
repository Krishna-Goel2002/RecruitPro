import { useState } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '../../api/admin'
import { qk } from '../../api/queryKeys'
import { STAFF_ROLES } from '../../api/types'
import type { AdminUser, AdminUserParams, StaffRole } from '../../api/types'
import { useCurrentUser } from '../../auth/AuthContext'
import { ActionDialog } from '../../components/ActionDialog'
import { QueryError } from '../../components/QueryError'
import { Avatar, Badge, Button, DataTable, EmptyState, Input, PageHeader, Pagination, Select, Tooltip, useToast, type Column } from '../../design/components'
import { Icon, MailPlus, Search, Users } from '../../design/icons'
import { plural, relativeTime } from '../../design/lib/format'
import { fullName, roleLabel } from '../../lib/format'
import { oneOf, useClampPage, useSearchState } from '../../lib/useSearchState'
import { InviteDrawer, ROLE_HELP, useUrlSearch } from './shared'
import './admin.css'

type Change = { user: AdminUser; kind: 'role'; role: StaffRole } | { user: AdminUser; kind: 'status'; enabled: boolean }

/** /admin/users: role changes in place (confirmed), enable/disable, invite. The last admin is protected server-side. */
export function AdminUsersPage() {
  const me = useCurrentUser()
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const state = useSearchState()
  const search = useUrlSearch(state)
  const role = oneOf<StaffRole | ''>(state.get('role'), ['', ...STAFF_ROLES], '')
  const enabled = oneOf(state.get('enabled'), ['', 'true', 'false'], '')
  const page = state.getPage()
  const params: AdminUserParams = { page, size: 20, search: search.value || undefined, role: role || undefined, enabled: enabled === '' ? undefined : enabled === 'true' }
  const users = useQuery({ queryKey: qk.adminUsers(params), queryFn: ({ signal }) => adminApi.users(params, signal), placeholderData: keepPreviousData })
  useClampPage(state, page, users.isPlaceholderData ? undefined : users.data)
  const [change, setChange] = useState<Change | null>(null)
  const [inviteOpen, setInviteOpen] = useState(false)
  const filtered = Boolean(search.value || role || enabled)

  const apply = useMutation({
    mutationFn: (c: Change) => (c.kind === 'role' ? adminApi.setUserRole(c.user.id, c.role) : adminApi.setUserEnabled(c.user.id, c.enabled)),
    onSuccess: (updated, c) => {
      setChange(null)
      void queryClient.invalidateQueries({ queryKey: ['admin', 'users'] })
      void queryClient.invalidateQueries({ queryKey: qk.interviewers })
      toast({ tone: 'success', title: c.kind === 'role' ? `${fullName(updated)} is now ${roleLabel(updated.role)}` : `${fullName(updated)} is ${updated.enabled ? 'enabled' : 'disabled'}`, body: 'Recorded in the audit log.' })
    },
  })

  const columns: Column<AdminUser>[] = [
    {
      key: 'name', header: 'Name', primary: true, sortValue: u => fullName(u),
      cell: u => (
        <span className="adm-person">
          <Avatar name={fullName(u)} size={28} />
          <span className="adm-person-text"><span className="adm-strong">{fullName(u)}{u.id === me.id && <span className="adm-you"> (you)</span>}</span><span className="adm-faint">{u.email}</span></span>
        </span>
      ),
    },
    {
      key: 'role', header: 'Role', width: '200px', sortValue: u => u.role,
      cell: u => u.id === me.id ? <Tooltip content="You cannot change your own role." labelOnly><span className="adm-faint" tabIndex={0}>{roleLabel(u.role)}</span></Tooltip> : (
        <Select controlSize="sm" aria-label={`Role of ${fullName(u)}`} value={u.role} onChange={e => setChange({ user: u, kind: 'role', role: e.target.value as StaffRole })}>
          {STAFF_ROLES.map(r => <option key={r} value={r}>{roleLabel(r)}</option>)}
        </Select>
      ),
    },
    { key: 'status', header: 'Status', width: '120px', sortValue: u => (u.enabled ? 0 : 1), cell: u => (u.enabled ? <Badge tone="success" dot>Enabled</Badge> : <Badge tone="neutral" variant="outline">Disabled</Badge>) },
    { key: 'joined', header: 'Joined', width: '120px', hideWhenStacked: true, sortValue: u => u.createdAt, cell: u => <time dateTime={u.createdAt} className="adm-faint">{relativeTime(u.createdAt)}</time> },
    {
      key: 'act', header: <span className="sr-only">Actions</span>, align: 'end', width: '110px',
      cell: u => u.id === me.id ? null : (
        <Button size="sm" variant={u.enabled ? 'danger-ghost' : 'secondary'} onClick={() => setChange({ user: u, kind: 'status', enabled: !u.enabled })}>{u.enabled ? 'Disable' : 'Enable'}</Button>
      ),
    },
  ]

  const c = change
  return (
    <div className="adm" data-width="wide">
      <PageHeader
        title="Staff accounts"
        meta={[users.data ? plural(users.data.totalElements, 'person', 'people') : '…', 'An organisation always keeps at least one enabled administrator']}
        actions={<Button variant="primary" icon={MailPlus} onClick={() => setInviteOpen(true)}>Invite staff</Button>}
      />
      {users.isError && !users.data ? (
        <QueryError error={users.error} title="We couldn’t load staff accounts" onRetry={() => void users.refetch()} retrying={users.isFetching} />
      ) : (
        <DataTable
          caption="Staff accounts"
          columns={columns}
          rows={users.data?.content ?? []}
          rowKey={u => u.id}
          loading={users.isPending}
          manualSort={false}
          toolbar={
            <div className="adm-toolbar" role="search">
              <Input leading={<Icon icon={Search} size={14} />} type="search" aria-label="Search by name or email" placeholder="Search name or email" value={search.input} onChange={e => search.setInput(e.target.value)} />
              <Select aria-label="Role" value={role} onChange={e => state.set({ role: e.target.value })}>
                <option value="">All roles</option>
                {STAFF_ROLES.map(r => <option key={r} value={r}>{roleLabel(r)}</option>)}
              </Select>
              <Select aria-label="Status" value={enabled} onChange={e => state.set({ enabled: e.target.value })}>
                <option value="">Any status</option>
                <option value="true">Enabled</option>
                <option value="false">Disabled</option>
              </Select>
            </div>
          }
          empty={<EmptyState compact icon={Users} title={filtered ? 'No one matches these filters' : 'No staff yet'} description={filtered ? 'Try another name, role or status.' : 'Invite your team to start reviewing applicants.'} action={filtered ? <Button size="sm" variant="secondary" onClick={() => state.set({ q: null, role: null, enabled: null })}>Clear filters</Button> : <Button size="sm" variant="primary" onClick={() => setInviteOpen(true)}>Invite staff</Button>} />}
        />
      )}
      {users.data && users.data.totalElements > 20 && <Pagination page={page + 1} pageSize={20} total={users.data.totalElements} onPageChange={p => state.set({ page: p - 1 })} />}

      <ActionDialog
        open={c != null}
        title={c?.kind === 'role' ? `Make ${fullName(c.user)} ${roleLabel(c.role)}?` : c?.enabled ? `Enable ${c ? fullName(c.user) : ''}?` : `Disable ${c ? fullName(c.user) : ''}?`}
        description={c?.kind === 'role' ? `${ROLE_HELP[c.role]} Their access changes on their next request.` : c?.enabled ? 'They can sign in again with their existing password.' : 'They are signed out and cannot sign in until re-enabled. Their decisions and notes stay in the history.'}
        confirmLabel={c?.kind === 'role' ? 'Change role' : c?.enabled ? 'Enable' : 'Disable'}
        tone={c?.kind === 'status' && !c.enabled ? 'danger' : 'primary'}
        busy={apply.isPending}
        error={apply.error}
        onCancel={() => setChange(null)}
        onConfirm={() => { if (c) apply.mutate(c) }}
      />
      <InviteDrawer open={inviteOpen} onClose={() => setInviteOpen(false)} />
    </div>
  )
}
