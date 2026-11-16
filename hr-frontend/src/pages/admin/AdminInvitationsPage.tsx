import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '../../api/admin'
import { qk } from '../../api/queryKeys'
import type { Invitation } from '../../api/types'
import { ActionDialog } from '../../components/ActionDialog'
import { QueryError } from '../../components/QueryError'
import { Button, DataTable, EmptyState, PageHeader, SegmentedControl, useToast, type Column } from '../../design/components'
import { MailPlus } from '../../design/icons'
import { plural, relativeTime } from '../../design/lib/format'
import { roleLabel } from '../../lib/format'
import { useSearchState } from '../../lib/useSearchState'
import { InvitationBadge, InviteDrawer, invitationStatus } from './shared'
import './admin.css'

/** /admin/invitations: pending first; invite in a drawer, revoke with a confirmation. */
export function AdminInvitationsPage() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const state = useSearchState()
  const show = state.get('show', 'pending') === 'all' ? 'all' : 'pending'
  const list = useQuery({ queryKey: qk.adminInvitations, queryFn: ({ signal }) => adminApi.invitations(signal) })
  const [inviteOpen, setInviteOpen] = useState(false)
  const [revoking, setRevoking] = useState<Invitation | null>(null)
  const revoke = useMutation({
    mutationFn: (id: number) => adminApi.revokeInvitation(id),
    onSuccess: () => {
      setRevoking(null)
      toast({ tone: 'success', title: 'Invitation revoked', body: 'The link no longer works.' })
      void queryClient.invalidateQueries({ queryKey: qk.adminInvitations })
    },
  })

  const all = [...(list.data ?? [])].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  const pending = all.filter(i => invitationStatus(i) === 'PENDING')
  const rows = show === 'pending' ? pending : all

  const columns: Column<Invitation>[] = [
    { key: 'email', header: 'Email', primary: true, sortValue: i => i.email, cell: i => <span className="adm-strong">{i.email}</span> },
    { key: 'role', header: 'Role', width: '150px', sortValue: i => i.role, cell: i => roleLabel(i.role) },
    { key: 'status', header: 'Status', width: '120px', cell: i => <InvitationBadge status={invitationStatus(i)} /> },
    { key: 'by', header: 'Invited', hideWhenStacked: true, sortValue: i => i.createdAt, cell: i => <span className="adm-faint">{i.invitedByName ?? 'Someone'} · {relativeTime(i.createdAt)}</span> },
    {
      key: 'exp', header: 'Expires', width: '130px', hideWhenStacked: true, sortValue: i => i.expiresAt,
      cell: i => { const s = invitationStatus(i); return s === 'PENDING' ? <time dateTime={i.expiresAt}>{relativeTime(i.expiresAt)}</time> : <span className="adm-faint">—</span> },
    },
    { key: 'act', header: <span className="sr-only">Actions</span>, align: 'end', width: '100px', cell: i => (invitationStatus(i) === 'PENDING' ? <Button size="sm" variant="danger-ghost" onClick={() => { revoke.reset(); setRevoking(i) }}>Revoke</Button> : null) },
  ]

  return (
    <div className="adm" data-width="wide">
      <PageHeader
        title="Invitations"
        meta={[list.data ? `${plural(pending.length, 'pending invitation')}` : '…', 'Links are valid for 7 days and work once']}
        actions={<Button variant="primary" icon={MailPlus} onClick={() => setInviteOpen(true)}>Invite staff</Button>}
      />
      {list.isError ? <QueryError error={list.error} title="We couldn’t load invitations" onRetry={() => void list.refetch()} retrying={list.isFetching} /> : (
        <DataTable
          caption="Invitations"
          columns={columns}
          rows={rows}
          rowKey={i => i.id}
          loading={list.isPending}
          toolbar={<div className="adm-toolbar"><SegmentedControl label="Show" size="sm" value={show} onChange={v => state.set({ show: v === 'pending' ? null : v }, { replace: true })} options={[{ value: 'pending', label: `Pending · ${pending.length}` }, { value: 'all', label: `All · ${all.length}` }]} /></div>}
          empty={<EmptyState compact icon={MailPlus} title={show === 'pending' ? 'No pending invitations' : 'No invitations yet'} description="Invite recruiters, managers and interviewers by email." action={<Button size="sm" variant="primary" onClick={() => setInviteOpen(true)}>Invite staff</Button>} />}
        />
      )}
      <ActionDialog
        open={revoking != null}
        title={`Revoke the invitation for ${revoking?.email ?? ''}?`}
        description="The link stops working at once. You can invite the same address again later."
        confirmLabel="Revoke invitation"
        tone="danger"
        busy={revoke.isPending}
        error={revoke.error}
        onCancel={() => setRevoking(null)}
        onConfirm={() => { if (revoking) revoke.mutate(revoking.id) }}
      />
      <InviteDrawer open={inviteOpen} onClose={() => setInviteOpen(false)} />
    </div>
  )
}
