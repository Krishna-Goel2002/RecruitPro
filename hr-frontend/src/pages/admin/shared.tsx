import { useEffect, useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '../../api/admin'
import { qk } from '../../api/queryKeys'
import { STAFF_ROLES } from '../../api/types'
import type { Invitation, InvitationStatus, StaffRole } from '../../api/types'
import { InlineError } from '../../components/QueryError'
import { Badge, Button, CodeField, Drawer, Field, Input, RadioCard, RadioGroup } from '../../design/components'
import { CircleCheck, Icon, MailPlus } from '../../design/icons'
import { useDebouncedValue } from '../../lib/hooks'
import { roleLabel } from '../../lib/format'
import type { useSearchState } from '../../lib/useSearchState'

export const ROLE_HELP: Record<StaffRole, string> = {
  ADMIN: 'Everything, including staff, organisation settings and the audit log.',
  HR_MANAGER: 'Creates and manages jobs, decides on applicants, schedules interviews.',
  RECRUITER: 'Reads jobs and applicants, comments, verifies claims, scores work samples.',
  INTERVIEWER: 'Sees only interviews assigned to them and submits scorecards.',
}

/** A search box bound to `?q=` with a 300 ms debounce (Back/Forward restore it). */
export function useUrlSearch(state: ReturnType<typeof useSearchState>, key = 'q') {
  const value = state.get(key)
  const [input, setInput] = useState(value)
  const debounced = useDebouncedValue(input.trim(), 300)
  useEffect(() => { setInput(value) }, [value])
  useEffect(() => {
    if (debounced !== value) state.set({ [key]: debounced }, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debounced])
  return { value, input, setInput }
}

export function invitationStatus(i: Invitation): InvitationStatus {
  if (i.status) return i.status
  if (i.revokedAt) return 'REVOKED'
  if (i.acceptedAt) return 'ACCEPTED'
  if (new Date(i.expiresAt).getTime() < Date.now()) return 'EXPIRED'
  return 'PENDING'
}

export function InvitationBadge({ status }: { status: InvitationStatus }) {
  if (status === 'PENDING') return <Badge tone="info" dot>Pending</Badge>
  if (status === 'ACCEPTED') return <Badge tone="success">Accepted</Badge>
  if (status === 'REVOKED') return <Badge tone="neutral" variant="struck">Revoked</Badge>
  return <Badge tone="neutral" variant="outline">Expired</Badge>
}

/**
 * Invite staff (FLOWS-HR-B §7): email + role as radio cards that say what each role can do. On success the drawer
 * shows the result in place (and the one-time link when the server exposes it in development setups).
 */
export function InviteDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<StaffRole>('RECRUITER')
  const [error, setError] = useState<string | null>(null)
  const [sent, setSent] = useState<Invitation | null>(null)
  const invite = useMutation({
    mutationFn: () => adminApi.invite(email.trim(), role),
    onSuccess: created => {
      setSent(created)
      void queryClient.invalidateQueries({ queryKey: qk.adminInvitations })
    },
  })
  useEffect(() => {
    if (open) { setSent(null); invite.reset(); setError(null) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) return setError('Enter a work email address such as name@company.com.')
    setError(null)
    invite.mutate()
  }

  return (
    <Drawer
      open={open}
      onClose={onClose}
      title="Invite staff"
      description="The person gets a link valid for 7 days. It works once."
      busy={invite.isPending}
      footer={sent ? (
        <>
          <Button variant="secondary" onClick={() => { setSent(null); setEmail(''); invite.reset() }}>Invite another</Button>
          <Button variant="primary" onClick={onClose}>Done</Button>
        </>
      ) : (
        <>
          <Button variant="secondary" onClick={onClose} disabled={invite.isPending}>Cancel</Button>
          <Button type="submit" form="invite-form" variant="primary" icon={MailPlus} loading={invite.isPending}>Send invitation</Button>
        </>
      )}
    >
      {sent ? (
        <div className="adm-sent" role="status">
          <Icon icon={CircleCheck} size={20} />
          <div>
            <h3 className="t-h3">Invitation sent to {sent.email}</h3>
            <p>As {roleLabel(sent.role)}. It expires in 7 days and appears under Invitations until it is accepted.</p>
            {sent.inviteUrl && <CodeField label="Invitation link (development setups only)" value={sent.inviteUrl} truncate="middle" />}
          </div>
        </div>
      ) : (
        <form id="invite-form" className="sx-action-form" onSubmit={submit} noValidate>
          <Field label="Work email" error={error}>
            <Input type="email" autoComplete="off" autoFocus value={email} onChange={e => { setEmail(e.target.value); if (error) setError(null) }} placeholder="name@company.com" />
          </Field>
          <RadioGroup legend="Role" value={role} onChange={v => setRole(v as StaffRole)}>
            {STAFF_ROLES.map(r => <RadioCard key={r} value={r} label={roleLabel(r)} description={ROLE_HELP[r]} />)}
          </RadioGroup>
          <InlineError error={invite.error} />
        </form>
      )}
    </Drawer>
  )
}
