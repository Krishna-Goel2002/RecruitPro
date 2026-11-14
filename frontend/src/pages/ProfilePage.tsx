import { useEffect, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { meApi, profileApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { CandidateProfile, CandidateProfileUpdate } from '../api/types'
import { useAuth, useCurrentUser } from '../auth/AuthProvider'
import { ErrorBlock, FormError, LoadingBlock } from '../components/Feedback'
import { Field, PasswordChecklist, PasswordInput, fieldErrorMap } from '../components/Form'
import { useNotify } from '../components/Notices'
import { PageHeader, Section } from '../components/PageHeader'
import { initials } from '../lib/format'
import { PASSWORD_POLICY_TEXT, isPasswordValid } from '../lib/password'
import { isHttpUrl } from '../lib/url'

type TextKey = 'address' | 'city' | 'state' | 'country' | 'zipCode' | 'linkedinUrl' | 'portfolioUrl' | 'currentCompany' | 'currentDesignation' | 'profileSummary'
type NumberKey = 'yearsOfExperience' | 'expectedSalary' | 'noticePeriodDays'
type FormValues = Record<TextKey | NumberKey, string>

const TEXT_FIELDS: { key: TextKey; label: string; max: number; type?: 'url'; wide?: boolean; multiline?: number; autoComplete?: string }[] = [
  { key: 'currentDesignation', label: 'Current job title', max: 255, autoComplete: 'organization-title' },
  { key: 'currentCompany', label: 'Current employer', max: 255, autoComplete: 'organization' },
  { key: 'city', label: 'City', max: 100, autoComplete: 'address-level2' },
  { key: 'state', label: 'State or region', max: 100, autoComplete: 'address-level1' },
  { key: 'country', label: 'Country', max: 100, autoComplete: 'country-name' },
  { key: 'zipCode', label: 'Postal code', max: 20, autoComplete: 'postal-code' },
  { key: 'address', label: 'Address', max: 5000, wide: true, multiline: 2, autoComplete: 'street-address' },
  { key: 'linkedinUrl', label: 'LinkedIn URL', max: 500, type: 'url', autoComplete: 'url' },
  { key: 'portfolioUrl', label: 'Portfolio or website', max: 500, type: 'url', autoComplete: 'url' },
  { key: 'profileSummary', label: 'About you', max: 5000, wide: true, multiline: 5 },
]

const NUMBER_FIELDS: { key: NumberKey; label: string; min: number; max: number; step?: string }[] = [
  { key: 'yearsOfExperience', label: 'Years of experience', min: 0, max: 100 },
  { key: 'noticePeriodDays', label: 'Notice period (days)', min: 0, max: 3650 },
  { key: 'expectedSalary', label: 'Expected annual salary (₹)', min: 0, max: 9_999_999_999, step: '1' },
]

function toForm(profile: CandidateProfile): FormValues {
  const values = {} as FormValues
  for (const field of TEXT_FIELDS) values[field.key] = profile[field.key] ?? ''
  for (const field of NUMBER_FIELDS) values[field.key] = profile[field.key] == null ? '' : String(profile[field.key])
  return values
}

function toUpdate(values: FormValues): CandidateProfileUpdate {
  const text = (key: TextKey) => values[key].trim() || null
  const number = (key: NumberKey) => (values[key].trim() === '' ? null : Number(values[key]))
  return {
    address: text('address'),
    city: text('city'),
    state: text('state'),
    country: text('country'),
    zipCode: text('zipCode'),
    linkedinUrl: text('linkedinUrl'),
    portfolioUrl: text('portfolioUrl'),
    currentCompany: text('currentCompany'),
    currentDesignation: text('currentDesignation'),
    profileSummary: text('profileSummary'),
    yearsOfExperience: number('yearsOfExperience'),
    expectedSalary: number('expectedSalary'),
    noticePeriodDays: number('noticePeriodDays'),
  }
}

export function ProfilePage() {
  const user = useCurrentUser()
  const profile = useQuery({ queryKey: queryKeys.profile, queryFn: profileApi.get })

  return (
    <>
      <PageHeader
        eyebrow="CANDIDATE PORTAL"
        title="Your profile"
        description="Recruiters see these details only after your application is shortlisted."
      />
      <div className="identity-row panel">
        <span className="profile-badge" aria-hidden="true">
          {initials(user.firstName, user.lastName)}
        </span>
        <div>
          <strong>
            {user.firstName} {user.lastName}
          </strong>
          <p className="muted">{user.email}</p>
        </div>
        <span className={`tag ${user.emailVerified ? 'green' : 'amber'}`}>{user.emailVerified ? 'Email verified' : 'Email not verified'}</span>
      </div>
      {profile.isPending ? (
        <LoadingBlock label="Loading profile…" />
      ) : profile.isError ? (
        <ErrorBlock error={profile.error} onRetry={() => void profile.refetch()} />
      ) : (
        <ProfileForm profile={profile.data} />
      )}
      <ChangePassword />
    </>
  )
}

function ProfileForm({ profile }: { profile: CandidateProfile }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const [values, setValues] = useState<FormValues>(() => toForm(profile))
  const [errors, setErrors] = useState<Record<string, string>>({})

  useEffect(() => setValues(toForm(profile)), [profile])

  const save = useMutation({
    mutationFn: () => profileApi.update(toUpdate(values)),
    onSuccess: updated => {
      queryClient.setQueryData(queryKeys.profile, updated)
      notify('success', 'Profile saved.')
    },
    onError: error => {
      if (error instanceof ApiError && error.fieldErrors.length > 0) setErrors(fieldErrorMap(error.fieldErrors))
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    for (const key of ['linkedinUrl', 'portfolioUrl'] as const) {
      const value = values[key].trim()
      if (value && !isHttpUrl(value)) next[key] = 'Enter a full link starting with https:// or http://.'
    }
    for (const field of NUMBER_FIELDS) {
      const raw = values[field.key].trim()
      if (!raw) continue
      const value = Number(raw)
      if (!Number.isFinite(value) || value < field.min || value > field.max) next[field.key] = `Enter a number from ${field.min} to ${field.max.toLocaleString()}.`
      else if (field.key !== 'expectedSalary' && !Number.isInteger(value)) next[field.key] = 'Enter a whole number.'
    }
    setErrors(next)
    if (Object.keys(next).length === 0) save.mutate()
  }

  const set = (key: TextKey | NumberKey) => (value: string) => setValues(current => ({ ...current, [key]: value }))

  return (
    <Section title="Professional details" id="profile-details">
      <form className="panel profile-grid" onSubmit={submit} noValidate>
        {TEXT_FIELDS.map(field => (
          <Field key={field.key} label={field.label} optional error={errors[field.key]} className={field.wide ? 'wide' : undefined}>
            {control =>
              field.multiline ? (
                <textarea
                  {...control}
                  rows={field.multiline}
                  maxLength={field.max}
                  autoComplete={field.autoComplete}
                  value={values[field.key]}
                  onChange={event => set(field.key)(event.target.value)}
                />
              ) : (
                <input
                  {...control}
                  type={field.type ?? 'text'}
                  inputMode={field.type === 'url' ? 'url' : undefined}
                  maxLength={field.max}
                  autoComplete={field.autoComplete}
                  value={values[field.key]}
                  onChange={event => set(field.key)(event.target.value)}
                />
              )
            }
          </Field>
        ))}
        {NUMBER_FIELDS.map(field => (
          <Field key={field.key} label={field.label} optional error={errors[field.key]}>
            {control => (
              <input
                {...control}
                type="number"
                inputMode="numeric"
                min={field.min}
                max={field.max}
                step={field.step ?? '1'}
                value={values[field.key]}
                onChange={event => set(field.key)(event.target.value)}
              />
            )}
          </Field>
        ))}
        <div className="wide form-actions">
          <span className="muted">We do not ask for your date of birth or gender. Your name and email are part of your account.</span>
          <button className="primary" type="submit" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save profile'}
          </button>
        </div>
        <div className="wide">
          <FormError error={save.error} />
        </div>
      </form>
    </Section>
  )
}

function ChangePassword() {
  const { acceptSession } = useAuth()
  const notify = useNotify()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [formError, setFormError] = useState<string | null>(null)

  const change = useMutation({
    mutationFn: () => meApi.changePassword({ currentPassword, newPassword }),
    onSuccess: session => {
      // WP-BE-A returns the new session; older access tokens are rejected from now on.
      if (session) acceptSession(session)
      setCurrentPassword('')
      setNewPassword('')
      setConfirm('')
      notify('success', 'Password changed. You were signed out on your other devices.')
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!currentPassword) return setFormError('Enter your current password.')
    if (!isPasswordValid(newPassword)) return setFormError(PASSWORD_POLICY_TEXT)
    if (newPassword !== confirm) return setFormError('The new passwords do not match.')
    setFormError(null)
    change.mutate()
  }

  return (
    <Section title="Change password" id="password" description="Changing your password signs you out on every other device.">
      <form className="panel stack-form narrow-form" onSubmit={submit} noValidate>
        <Field label="Current password" required>
          {control => <PasswordInput control={control} value={currentPassword} onChange={setCurrentPassword} autoComplete="current-password" />}
        </Field>
        <Field label="New password" required hint={<PasswordChecklist password={newPassword} />}>
          {control => <PasswordInput control={control} value={newPassword} onChange={setNewPassword} autoComplete="new-password" />}
        </Field>
        <Field label="Confirm new password" required>
          {control => <PasswordInput control={control} value={confirm} onChange={setConfirm} autoComplete="new-password" />}
        </Field>
        <FormError error={formError ?? change.error} />
        <button className="primary" type="submit" disabled={change.isPending}>
          {change.isPending ? 'Changing…' : 'Change password'}
        </button>
      </form>
    </Section>
  )
}
