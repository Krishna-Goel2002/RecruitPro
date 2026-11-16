import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { accountApi } from '../../api/account'
import { isApiError } from '../../api/client'
import { useAuth, useCurrentUser } from '../../auth/AuthContext'
import { PasswordChecklist, passwordError } from '../../components/PasswordChecklist'
import { InlineError } from '../../components/QueryError'
import { Alert, Button, Field, IconButton, Input, PageHeader } from '../../design/components'
import { Eye, EyeOff, KeyRound } from '../../design/icons'
import './account.css'

function PasswordInput({ value, onChange, autoComplete, autoFocus, describedBy }: { value: string; onChange: (v: string) => void; autoComplete: string; autoFocus?: boolean; describedBy?: string }) {
  const [show, setShow] = useState(false)
  return (
    <Input
      type={show ? 'text' : 'password'}
      autoComplete={autoComplete}
      maxLength={128}
      value={value}
      autoFocus={autoFocus}
      aria-describedby={describedBy}
      onChange={e => onChange(e.target.value)}
      trailing={<IconButton size="sm" label={show ? 'Hide password' : 'Show password'} icon={show ? EyeOff : Eye} onClick={() => setShow(s => !s)} noTooltip />}
    />
  )
}

/**
 * /account/password: current, new (live checklist with the server's rules: any language's letters count), confirm.
 * The server signs out every other session and returns a new one for this tab, which is adopted.
 */
export function ChangePasswordPage() {
  const user = useCurrentUser()
  const { acceptSession } = useAuth()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [done, setDone] = useState(false)

  const save = useMutation({
    mutationFn: () => accountApi.changePassword({ currentPassword: current, newPassword: next }),
    onSuccess: async auth => {
      if (auth?.accessToken) await acceptSession(auth)
      setCurrent(''); setNext(''); setConfirm('')
      setDone(true)
    },
    onError: cause => {
      if (isApiError(cause, 400) || isApiError(cause, 401)) setErrors({ current: 'That is not your current password.' })
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    setDone(false)
    const found: Record<string, string> = {}
    if (!current) found.current = 'Enter your current password.'
    const problem = passwordError(next)
    if (problem) found.next = problem
    else if (next === current) found.next = 'Choose a password that differs from the current one.'
    if (!found.next && confirm !== next) found.confirm = 'The two new passwords do not match.'
    setErrors(found)
    const first = Object.keys(found)[0]
    if (first) {
      document.querySelector<HTMLInputElement>(`[data-pw="${first}"] input`)?.focus()
      return
    }
    save.mutate()
  }

  return (
    <div className="acc" data-width="form">
      <PageHeader title="Change password" meta={[<span key="e">{user.email}</span>, <span key="s">Other sessions are signed out</span>]} />
      {done && <Alert tone="success" live title="Password changed">Other sessions were signed out. This one stays signed in.</Alert>}
      <form className="acc-form" onSubmit={submit} noValidate>
        <input type="text" name="username" autoComplete="username" value={user.email} readOnly hidden />
        <div data-pw="current">
          <Field label="Current password" error={errors.current}>
            <PasswordInput value={current} onChange={v => { setCurrent(v); setErrors(e => ({ ...e, current: '' })) }} autoComplete="current-password" autoFocus />
          </Field>
        </div>
        <div data-pw="next">
          <Field label="New password" error={errors.next}>
            <PasswordInput value={next} onChange={setNext} autoComplete="new-password" describedBy="pw-rules" />
          </Field>
          <PasswordChecklist password={next} id="pw-rules" />
        </div>
        <div data-pw="confirm">
          <Field label="Confirm new password" error={errors.confirm}>
            <PasswordInput value={confirm} onChange={setConfirm} autoComplete="new-password" />
          </Field>
        </div>
        {save.error && !errors.current && <InlineError error={save.error} />}
        <div className="acc-actions">
          <Button type="submit" variant="primary" icon={KeyRound} loading={save.isPending}>Change password</Button>
        </div>
      </form>
    </div>
  )
}
