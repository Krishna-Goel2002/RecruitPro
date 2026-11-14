import { FormEvent, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { accountApi } from '../../api/account'
import { useAuth, useCurrentUser } from '../../auth/AuthContext'
import { Field } from '../../components/Field'
import { SaveIcon } from '../../components/Icons'
import { PageHeader } from '../../components/Layout'
import { useNotices } from '../../components/Notices'
import { FormError } from '../../components/States'
import { passwordProblem } from '../../lib/format'
import { usePageTitle } from '../../lib/hooks'

/**
 * /account/password — change the signed-in staff member's password (`POST /me/password`). The server signs out every
 * other session and returns a new session for this one, which is adopted so the user stays signed in.
 */
export function ChangePasswordPage() {
  usePageTitle('Change password')
  const user = useCurrentUser()
  const { acceptSession } = useAuth()
  const notices = useNotices()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})

  const save = useMutation({
    mutationFn: () => accountApi.changePassword({ currentPassword: current, newPassword: next }),
    onSuccess: async auth => {
      if (auth?.accessToken) await acceptSession(auth)
      setCurrent(''); setNext(''); setConfirm('')
      notices.success('Password changed. Other sessions were signed out.')
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const found: Record<string, string> = {}
    if (!current) found.current = 'Enter your current password.'
    const problem = passwordProblem(next)
    if (problem) found.next = problem
    else if (next === current) found.next = 'Choose a password that differs from the current one.'
    if (confirm !== next) found.confirm = 'The passwords do not match.'
    setErrors(found)
    if (Object.keys(found).length === 0) save.mutate()
  }

  return (
    <>
      <PageHeader eyebrow="ACCOUNT" title="Change password" subtitle={`Signed in as ${user.email}. Changing the password signs out your other sessions.`} />
      <form className="detail-card form-card settings-form" onSubmit={submit} noValidate>
        <input type="text" name="username" autoComplete="username" value={user.email} readOnly hidden />
        <Field label="Current password" required error={errors.current}>
          <input type="password" autoComplete="current-password" maxLength={72} value={current} onChange={event => setCurrent(event.target.value)} data-autofocus />
        </Field>
        <Field label="New password" required error={errors.next} hint="10–72 characters with at least one letter and one digit.">
          <input type="password" autoComplete="new-password" maxLength={72} value={next} onChange={event => setNext(event.target.value)} />
        </Field>
        <Field label="Confirm new password" required error={errors.confirm}>
          <input type="password" autoComplete="new-password" maxLength={72} value={confirm} onChange={event => setConfirm(event.target.value)} />
        </Field>
        <FormError error={save.error} />
        <div className="editor-actions">
          <span className="item-meta">Your current session stays signed in.</span>
          <button type="submit" className="primary-button workspace-primary" disabled={save.isPending}><SaveIcon /> {save.isPending ? 'Saving…' : 'Change password'}</button>
        </div>
      </form>
    </>
  )
}
