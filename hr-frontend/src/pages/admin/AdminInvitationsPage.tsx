import { FormEvent, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '../../api/admin'
import { qk } from '../../api/queryKeys'
import { Invitation, InvitationStatus, STAFF_ROLES, StaffRole } from '../../api/types'
import { StatusBadge } from '../../components/Badges'
import { ConfirmDialog } from '../../components/Dialog'
import { Field } from '../../components/Field'
import { CopyIcon, InviteIcon } from '../../components/Icons'
import { PageHeader } from '../../components/Layout'
import { useNotices } from '../../components/Notices'
import { EmptyPanel, ErrorPanel, FormError, LoadingPanel } from '../../components/States'
import { formatDateTime, roleLabel } from '../../lib/format'
import { usePageTitle } from '../../lib/hooks'

/** Older servers do not send `status`; derive it from the timestamps. */
function statusOf(invitation: Invitation): InvitationStatus {
  if (invitation.status) return invitation.status
  if (invitation.acceptedAt) return 'ACCEPTED'
  if (invitation.revokedAt) return 'REVOKED'
  if (new Date(invitation.expiresAt).getTime() < Date.now()) return 'EXPIRED'
  return 'PENDING'
}

const ROLE_HELP: Record<StaffRole, string> = {
  ADMIN: 'Everything, including staff, organisation settings and the audit log.',
  HR_MANAGER: 'Creates and manages jobs, decides on applicants, schedules interviews.',
  RECRUITER: 'Reads jobs and applicants, comments, verifies claims, scores work samples.',
  INTERVIEWER: 'Sees only interviews assigned to them and submits scorecards.',
}

/** /admin/invitations — invite by email and role (7-day link), list, revoke, copy link when the server exposes it. */
export function AdminInvitationsPage() {
  usePageTitle('Invitations')
  const queryClient = useQueryClient()
  const notices = useNotices()
  const invitations = useQuery({ queryKey: qk.adminInvitations, queryFn: ({ signal }) => adminApi.invitations(signal) })
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<StaffRole>('RECRUITER')
  const [emailError, setEmailError] = useState<string | null>(null)
  // inviteUrl is only returned on creation (dev); keep it for this session so it can be copied.
  const [links, setLinks] = useState<Record<number, string>>({})
  const [revoking, setRevoking] = useState<Invitation | null>(null)

  const invite = useMutation({
    mutationFn: () => adminApi.invite(email.trim(), role),
    onSuccess: created => {
      if (created.inviteUrl) setLinks(current => ({ ...current, [created.id]: created.inviteUrl as string }))
      setEmail('')
      void queryClient.invalidateQueries({ queryKey: qk.adminInvitations })
      notices.success(`Invitation sent to ${created.email}.${created.inviteUrl ? ' The link can also be copied below.' : ''}`)
    },
  })

  const revoke = useMutation({
    mutationFn: (invitation: Invitation) => adminApi.revokeInvitation(invitation.id),
    onSuccess: (_, invitation) => {
      setRevoking(null)
      void queryClient.invalidateQueries({ queryKey: qk.adminInvitations })
      notices.success(`Invitation for ${invitation.email} revoked.`)
    },
  })

  async function copy(url: string) {
    try {
      await navigator.clipboard.writeText(url)
      notices.success('Invitation link copied.')
    } catch {
      window.prompt('Copy the invitation link:', url)
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!/^\S+@\S+\.\S+$/.test(email.trim())) {
      setEmailError('Enter a valid email address.')
      return
    }
    setEmailError(null)
    invite.mutate()
  }

  const sorted = [...(invitations.data ?? [])].sort((a, b) => b.createdAt.localeCompare(a.createdAt))

  return (
    <>
      <PageHeader eyebrow="ADMINISTRATION" title="Invitations" subtitle="Invite colleagues by email. Links are valid for 7 days and can be used once." />

      <section className="detail-card form-card" aria-labelledby="invite-heading">
        <h2 id="invite-heading">Invite a colleague</h2>
        <form className="invite-form" onSubmit={submit} noValidate>
          <Field label="Work email" required error={emailError}>
            <input type="email" autoComplete="off" maxLength={255} value={email} onChange={event => setEmail(event.target.value)} placeholder="colleague@company.com" />
          </Field>
          <Field label="Role" required hint={ROLE_HELP[role]}>
            <select value={role} onChange={event => setRole(event.target.value as StaffRole)}>
              {STAFF_ROLES.map(value => <option key={value} value={value}>{roleLabel(value)}</option>)}
            </select>
          </Field>
          <div className="invite-submit">
            <button type="submit" className="primary-button workspace-primary" disabled={invite.isPending}><InviteIcon /> {invite.isPending ? 'Sending…' : 'Send invitation'}</button>
          </div>
        </form>
        <FormError error={invite.error} />
      </section>

      <section className="jobs-card" aria-labelledby="invitations-heading">
        <div className="jobs-card-head"><div><h2 id="invitations-heading">Sent invitations</h2><p>Newest first.</p></div><span className="record-count">{invitations.data ? invitations.data.length : '—'}</span></div>
        {invitations.isPending ? <LoadingPanel label="Loading invitations…" compact /> : invitations.isError ? (
          <ErrorPanel error={invitations.error} onRetry={() => void invitations.refetch()} compact />
        ) : sorted.length === 0 ? <EmptyPanel title="No invitations yet" compact /> : (
          <div className="job-table-wrap">
            <table className="job-table">
              <thead>
                <tr>
                  <th scope="col">EMAIL</th>
                  <th scope="col">ROLE</th>
                  <th scope="col">STATUS</th>
                  <th scope="col">INVITED</th>
                  <th scope="col">EXPIRES</th>
                  <th scope="col"><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {sorted.map(invitation => {
                  const status = statusOf(invitation)
                  const link = links[invitation.id] ?? invitation.inviteUrl ?? null
                  return (
                    <tr key={invitation.id}>
                      <td><strong className="cell-title">{invitation.email}</strong>{invitation.invitedByName && <span className="job-meta">by {invitation.invitedByName}</span>}</td>
                      <td>{roleLabel(invitation.role)}</td>
                      <td><StatusBadge status={status} /></td>
                      <td>{formatDateTime(invitation.createdAt)}</td>
                      <td>{status === 'ACCEPTED' ? `Accepted ${formatDateTime(invitation.acceptedAt)}` : formatDateTime(invitation.expiresAt)}</td>
                      <td className="cell-actions">
                        {status === 'PENDING' && link && <button type="button" className="outline-button" onClick={() => void copy(link)}><CopyIcon /> Copy link</button>}
                        {status === 'PENDING' && <button type="button" className="outline-button danger-outline" onClick={() => { revoke.reset(); setRevoking(invitation) }}>Revoke</button>}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <ConfirmDialog
        open={revoking != null}
        title="Revoke this invitation?"
        message={revoking ? `The link sent to ${revoking.email} stops working. You can invite them again later.` : ''}
        confirmLabel="Revoke"
        danger
        busy={revoke.isPending}
        error={revoke.error}
        onCancel={() => setRevoking(null)}
        onConfirm={() => { if (revoking) revoke.mutate(revoking) }}
      />
    </>
  )
}
