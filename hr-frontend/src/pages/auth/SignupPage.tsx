import { FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { authApi } from '../../api/auth'
import { errorMessage, isApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthContext'
import { ArrowIcon, BuildingIcon, LockIcon, MailIcon, UserIcon } from '../../components/Icons'
import { passwordProblem } from '../../lib/format'
import { usePageTitle } from '../../lib/hooks'
import { AuthError, AuthInput, AuthLayout } from './AuthLayout'

type Form = { organizationName: string; firstName: string; lastName: string; email: string; password: string; confirm: string }
const empty: Form = { organizationName: '', firstName: '', lastName: '', email: '', password: '', confirm: '' }

/** Creates an organisation and its first administrator (POST /organizations/signup). */
export function SignupPage() {
  usePageTitle('Create your workspace')
  const { acceptSession } = useAuth()
  const [form, setForm] = useState<Form>(empty)
  const [errors, setErrors] = useState<Partial<Record<keyof Form, string>>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const set = (key: keyof Form) => (event: { target: { value: string } }) => setForm(current => ({ ...current, [key]: event.target.value }))

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    const next: Partial<Record<keyof Form, string>> = {}
    if (!form.organizationName.trim()) next.organizationName = 'Enter your organisation’s name.'
    if (!form.firstName.trim()) next.firstName = 'Enter your first name.'
    if (!form.lastName.trim()) next.lastName = 'Enter your last name.'
    if (!/^\S+@\S+\.\S+$/.test(form.email.trim())) next.email = 'Enter a valid work email address.'
    const problem = passwordProblem(form.password)
    if (problem) next.password = problem
    if (form.confirm !== form.password) next.confirm = 'The passwords do not match.'
    setErrors(next)
    if (Object.keys(next).length) return
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
      setError(isApiError(cause, 403) || isApiError(cause, 404)
        ? 'Self-service sign-up is turned off on this server. Ask your RecruitPro administrator for an invitation.'
        : isApiError(cause, 409) ? 'An account with this email already exists. Sign in instead.' : errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      eyebrow="NEW ORGANISATION"
      title="Create your workspace"
      intro="You become the organisation’s administrator. Invite your HR team once you are in."
      footer={<span>Already have an account? <Link to="/login">Sign in</Link></span>}
    >
      <form className="sign-in-form" onSubmit={event => void submit(event)} noValidate>
        <AuthInput label="Organisation name" icon={<BuildingIcon className="input-icon" />} autoComplete="organization" maxLength={200} value={form.organizationName} onChange={set('organizationName')} error={errors.organizationName} disabled={busy} required />
        <div className="auth-two-col">
          <AuthInput label="First name" icon={<UserIcon className="input-icon" />} autoComplete="given-name" maxLength={100} value={form.firstName} onChange={set('firstName')} error={errors.firstName} disabled={busy} required />
          <AuthInput label="Last name" autoComplete="family-name" maxLength={100} value={form.lastName} onChange={set('lastName')} error={errors.lastName} disabled={busy} required />
        </div>
        <AuthInput label="Work email" icon={<MailIcon className="input-icon" />} type="email" autoComplete="email" maxLength={255} value={form.email} onChange={set('email')} error={errors.email} disabled={busy} required />
        <AuthInput label="Password" icon={<LockIcon className="input-icon" />} type="password" revealable autoComplete="new-password" maxLength={72} value={form.password} onChange={set('password')} error={errors.password} hint="10–72 characters with at least one letter and one digit." disabled={busy} required />
        <AuthInput label="Confirm password" icon={<LockIcon className="input-icon" />} type="password" autoComplete="new-password" maxLength={72} value={form.confirm} onChange={set('confirm')} error={errors.confirm} disabled={busy} required />
        <AuthError message={error} />
        <button className="primary-button" type="submit" disabled={busy}>
          {busy ? <><span className="spinner" aria-hidden="true" />Creating workspace…</> : <>Create workspace <ArrowIcon /></>}
        </button>
      </form>
    </AuthLayout>
  )
}
