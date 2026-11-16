import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { accountApi } from '../../api/account'
import { useAuth, useCurrentUser } from '../../auth/AuthContext'
import { InlineError } from '../../components/QueryError'
import { Button, Field, Input, PageHeader, useToast } from '../../design/components'
import { useLeaveGuard } from '../../lib/leaveGuard'
import './account.css'

/** /account/name: the signed-in person's first and last name (PATCH /me; audited). Shown to colleagues in histories. */
export function NamePage() {
  const user = useCurrentUser()
  const { updateUser } = useAuth()
  const { toast } = useToast()
  const [first, setFirst] = useState(user.firstName ?? '')
  const [last, setLast] = useState(user.lastName ?? '')
  const [errors, setErrors] = useState<{ first?: string; last?: string }>({})
  const dirty = first.trim() !== (user.firstName ?? '') || last.trim() !== (user.lastName ?? '')

  const save = useMutation({
    mutationFn: () => accountApi.updateName(first.trim(), last.trim()),
    onSuccess: updated => {
      updateUser({ firstName: updated.firstName, lastName: updated.lastName })
      setFirst(updated.firstName ?? '')
      setLast(updated.lastName ?? '')
      toast({ tone: 'success', title: 'Name updated', body: 'Colleagues see it in histories and on scorecards.' })
    },
  })
  const leaveGuard = useLeaveGuard(dirty && !save.isPending)

  function submit(event: FormEvent) {
    event.preventDefault()
    const found: { first?: string; last?: string } = {}
    if (!first.trim()) found.first = 'Enter your first name.'
    else if (first.trim().length > 100) found.first = 'Use 100 characters or fewer.'
    if (!last.trim()) found.last = 'Enter your last name.'
    else if (last.trim().length > 100) found.last = 'Use 100 characters or fewer.'
    setErrors(found)
    if (found.first || found.last) return
    save.mutate()
  }

  return (
    <div className="acc" data-width="form">
      <PageHeader title="Your name" meta={[<span key="e">{user.email}</span>]} description="Shown to your colleagues in decision histories, interviews and the audit log." />
      <form className="acc-form" onSubmit={submit} noValidate>
        <Field label="First name" error={errors.first}>
          <Input autoComplete="given-name" maxLength={100} value={first} onChange={e => { setFirst(e.target.value); if (errors.first) setErrors(x => ({ ...x, first: undefined })) }} />
        </Field>
        <Field label="Last name" error={errors.last}>
          <Input autoComplete="family-name" maxLength={100} value={last} onChange={e => { setLast(e.target.value); if (errors.last) setErrors(x => ({ ...x, last: undefined })) }} />
        </Field>
        <InlineError error={save.error} />
        <div className="acc-actions">
          {dirty && <Button variant="ghost" onClick={() => { setFirst(user.firstName ?? ''); setLast(user.lastName ?? ''); setErrors({}); save.reset() }} disabled={save.isPending}>Discard</Button>}
          <Button type="submit" variant="primary" loading={save.isPending} disabled={!dirty}>{dirty ? 'Save name' : 'No changes'}</Button>
        </div>
      </form>
      {leaveGuard}
    </div>
  )
}
