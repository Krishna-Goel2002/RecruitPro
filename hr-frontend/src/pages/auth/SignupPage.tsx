import { FormEvent, useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { authApi } from '../../api/auth'
import { errorMessage, isApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthContext'
import { passwordError } from '../../components/PasswordChecklist'
import brand from '../../design/brand/active'
import { Button, Field, Input } from '../../design/components'
import { AuthLayout, AuthNotice } from './AuthLayout'
import { NewPasswordField } from './PasswordPages'

type Form = { organizationName: string; firstName: string; lastName: string; email: string; password: string }
type Errors = Partial<Record<keyof Form, string | null>>
const empty: Form = { organizationName: '', firstName: '', lastName: '', email: '', password: '' }

/** Creates an organisation and its first administrator (POST /organizations/signup); then the setup checklist. */
export function SignupPage() {
  const { acceptSession } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [form, setForm] = useState<Form>(empty)
  const [errors, setErrors] = useState<Errors>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<{ message: string; signIn?: boolean } | null>(null)

  // First run: once the session exists, PublicOnly sends the new admin to "Get started" instead of an empty overview.
  useEffect(() => {
    if ((location.state as { from?: string } | null)?.from) return
    navigate(location.pathname, { replace: true, state: { from: '/welcome' } })
  }, [location.pathname, location.state, navigate])

  const set = (key: keyof Form) => (value: string) => {
    setForm(current => ({ ...current, [key]: value }))
    if (errors[key]) setErrors(current => ({ ...current, [key]: null }))
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    const next: Errors = {}
    if (!form.organizationName.trim()) next.organizationName = 'Enter your organisation’s name.'
    if (!form.firstName.trim()) next.firstName = 'Enter your first name.'
    if (!form.lastName.trim()) next.lastName = 'Enter your last name.'
    if (!/^\S+@\S+\.\S+$/.test(form.email.trim())) next.email = 'Enter a work email address like name@company.com.'
    next.password = passwordError(form.password)
    setErrors(next)
    const first = (Object.keys(next) as (keyof Form)[]).find(key => next[key])
    if (first) {
      window.setTimeout(() => document.querySelector<HTMLElement>('.hra-auth-form [aria-invalid="true"]')?.focus(), 0)
      return
    }
    setBusy(true)
    try {
      const auth = await authApi.signup({
        organizationName: form.organizationName.trim(),
        firstName: form.firstName.trim(),
        lastName: form.lastName.trim(),
        email: form.email.trim(),
        password: form.password,
      })
      await acceptSession(auth)
    } catch (cause) {
      if (isApiError(cause, 403) || isApiError(cause, 404)) setError({ message: `Self-service sign-up is turned off on this server. Ask your ${brand.name} administrator for an invitation.` })
      else if (isApiError(cause, 409)) setError({ message: 'An account with this email already exists.', signIn: true })
      else setError({ message: errorMessage(cause) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      title="Create your workspace"
      intro="You become the organisation’s administrator. Next, a short checklist helps you invite your team and publish a first job."
      footer={<span>Already have an account? <Link to="/login">Sign in</Link></span>}
    >
      {error && (
        <AuthNotice tone="danger">
          {error.message} {error.signIn && <Link to="/login">Sign in instead</Link>}
        </AuthNotice>
      )}
      <form className="hra-auth-form" onSubmit={event => void submit(event)} noValidate>
        <Field label="Organisation name" error={errors.organizationName} hint="Candidates see this name on your jobs.">
          <Input autoComplete="organization" maxLength={200} controlSize="lg" value={form.organizationName} onChange={e => set('organizationName')(e.target.value)} disabled={busy} autoFocus />
        </Field>
        <div className="hra-auth-row">
          <Field label="First name" error={errors.firstName}>
            <Input autoComplete="given-name" maxLength={100} controlSize="lg" value={form.firstName} onChange={e => set('firstName')(e.target.value)} disabled={busy} />
          </Field>
          <Field label="Last name" error={errors.lastName}>
            <Input autoComplete="family-name" maxLength={100} controlSize="lg" value={form.lastName} onChange={e => set('lastName')(e.target.value)} disabled={busy} />
          </Field>
        </div>
        <Field label="Work email" error={errors.email}>
          <Input type="email" autoComplete="email" inputMode="email" maxLength={255} controlSize="lg" value={form.email} onChange={e => set('email')(e.target.value)} disabled={busy} />
        </Field>
        <NewPasswordField label="Password" value={form.password} onChange={set('password')} error={errors.password} disabled={busy} />
        <Button type="submit" variant="primary" size="lg" block loading={busy}>{busy ? 'Creating workspace…' : 'Create workspace'}</Button>
      </form>
    </AuthLayout>
  )
}
