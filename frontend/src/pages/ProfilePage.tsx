import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { meApi, profileApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { CandidateProfile, CandidateProfileUpdate } from '../api/types'
import { useAuth, useCurrentUser } from '../auth/AuthProvider'
import { ErrorState, FormError } from '../components/Feedback'
import { PasswordChecklist, PasswordInput, fieldErrorMap, focusFirstInvalid } from '../components/Form'
import { useNotify } from '../components/Notices'
import { Avatar, Badge, Button, Field, Input, LoadingRegion, PageHeader, Skeleton, SkeletonText, Textarea } from '../design/components'
import { useBeforeUnload } from '../lib/hooks'
import { PASSWORD_POLICY_TEXT, isPasswordValid } from '../lib/password'
import { isHttpUrl } from '../lib/url'
import './common.css'
import './settings/settings.css'

type TextKey = 'address' | 'city' | 'state' | 'country' | 'zipCode' | 'linkedinUrl' | 'portfolioUrl' | 'currentCompany' | 'currentDesignation' | 'profileSummary'
type NumberKey = 'yearsOfExperience' | 'expectedSalary' | 'noticePeriodDays'
type Key = TextKey | NumberKey
type FormValues = Record<Key, string>

const TEXT_KEYS: TextKey[] = ['address', 'city', 'state', 'country', 'zipCode', 'linkedinUrl', 'portfolioUrl', 'currentCompany', 'currentDesignation', 'profileSummary']
const NUMBER_RULES: Record<NumberKey, { min: number; max: number; integer: boolean }> = {
  yearsOfExperience: { min: 0, max: 100, integer: true },
  noticePeriodDays: { min: 0, max: 3650, integer: true },
  expectedSalary: { min: 0, max: 9_999_999_999, integer: false },
}

function toForm(profile: CandidateProfile): FormValues {
  const values = {} as FormValues
  for (const key of TEXT_KEYS) values[key] = profile[key] ?? ''
  for (const key of Object.keys(NUMBER_RULES) as NumberKey[]) values[key] = profile[key] == null ? '' : String(profile[key])
  return values
}

function toUpdate(values: FormValues): CandidateProfileUpdate {
  const text = (key: TextKey) => values[key].trim() || null
  const number = (key: NumberKey) => (values[key].trim() === '' ? null : Number(values[key].replace(/,/g, '')))
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

function validate(key: Key, value: string): string | undefined {
  const v = value.trim()
  if (!v) return undefined
  if (key === 'linkedinUrl' || key === 'portfolioUrl') return isHttpUrl(v) ? undefined : 'Enter a full link starting with https:// or http://.'
  if (key in NUMBER_RULES) {
    const rule = NUMBER_RULES[key as NumberKey]
    const n = Number(v.replace(/,/g, ''))
    if (!Number.isFinite(n) || n < rule.min || n > rule.max) return `Enter a number from ${rule.min} to ${rule.max.toLocaleString('en-IN')}.`
    if (rule.integer && !Number.isInteger(n)) return 'Enter a whole number.'
  }
  return undefined
}

/**
 * Profile (FLOWS-CANDIDATE §2): a settings layout (section label left, fields right from 900 px), inline validation
 * on blur, and a sticky save bar that appears only when something changed (Ctrl/⌘ + S saves).
 */
export function ProfilePage() {
  const user = useCurrentUser()
  const profile = useQuery({ queryKey: queryKeys.profile, queryFn: profileApi.get })
  const name = `${user.firstName} ${user.lastName}`.trim()

  return (
    <div className="st-page">
      <PageHeader title="Profile" description="Recruiters see these details only after they shortlist you. We never ask for your date of birth or gender." />
      <div className="st-identity">
        <Avatar name={name} size={32} />
        <div className="st-identity-text">
          <p className="t-strong">{name}</p>
          <p className="c-meta">{user.email}</p>
        </div>
        <Badge tone={user.emailVerified ? 'success' : 'warning'} dot>
          {user.emailVerified ? 'Email confirmed' : 'Email not confirmed'}
        </Badge>
      </div>
      {profile.isPending ? (
        <LoadingRegion label="Loading your profile">
          <div className="st-section">
            <div>
              <Skeleton width={120} height={14} />
            </div>
            <SkeletonText lines={4} />
          </div>
        </LoadingRegion>
      ) : profile.isError ? (
        <ErrorState error={profile.error} onRetry={() => void profile.refetch()} title="Your profile could not be loaded" />
      ) : (
        <ProfileForm profile={profile.data} />
      )}
      <ChangePassword />
    </div>
  )
}

function SettingsSection({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <section className="st-section" aria-label={title}>
      <div className="st-section-head">
        <h2 className="t-h3">{title}</h2>
        {description && <p className="c-meta">{description}</p>}
      </div>
      <div className="st-fields">{children}</div>
    </section>
  )
}

function ProfileForm({ profile }: { profile: CandidateProfile }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const formRef = useRef<HTMLFormElement>(null)
  const [initial, setInitial] = useState<FormValues>(() => toForm(profile))
  const [values, setValues] = useState<FormValues>(initial)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const dirty = (Object.keys(values) as Key[]).some(k => values[k] !== initial[k])

  useEffect(() => {
    const next = toForm(profile)
    setInitial(next)
    setValues(next)
  }, [profile])
  useBeforeUnload(dirty)

  const save = useMutation({
    mutationFn: () => profileApi.update(toUpdate(values)),
    onSuccess: updated => {
      queryClient.setQueryData(queryKeys.profile, updated)
      notify('success', 'Profile saved')
    },
    onError: error => {
      if (error instanceof ApiError && error.fieldErrors.length > 0) {
        setErrors(fieldErrorMap(error.fieldErrors))
        focusFirstInvalid(formRef.current)
      }
    },
  })

  function submit(event?: FormEvent) {
    event?.preventDefault()
    const next: Record<string, string> = {}
    for (const key of Object.keys(values) as Key[]) {
      const problem = validate(key, values[key])
      if (problem) next[key] = problem
    }
    setErrors(next)
    if (Object.keys(next).length === 0) save.mutate()
    else focusFirstInvalid(formRef.current)
  }

  // Ctrl/⌘ + S saves while there are changes.
  const submitRef = useRef(submit)
  submitRef.current = submit
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault()
        submitRef.current()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  const field = (key: Key) => ({
    value: values[key],
    onChange: (e: { target: { value: string } }) => {
      const value = e.target.value
      setValues(current => ({ ...current, [key]: value }))
      if (errors[key] && !validate(key, value)) setErrors(current => ({ ...current, [key]: '' }))
    },
    onBlur: () => setErrors(current => ({ ...current, [key]: validate(key, values[key]) ?? '' })),
  })

  return (
    <form ref={formRef} onSubmit={submit} noValidate>
      <SettingsSection title="Work" description="Your current role helps recruiters read your evidence in context.">
        <div className="st-row">
          <Field label="Current job title" optional error={errors.currentDesignation}>
            <Input maxLength={255} autoComplete="organization-title" {...field('currentDesignation')} />
          </Field>
          <Field label="Current employer" optional error={errors.currentCompany}>
            <Input maxLength={255} autoComplete="organization" {...field('currentCompany')} />
          </Field>
        </div>
        <div className="st-row">
          <Field label="Years of experience" optional error={errors.yearsOfExperience}>
            <Input inputMode="numeric" {...field('yearsOfExperience')} />
          </Field>
          <Field label="Notice period" optional error={errors.noticePeriodDays}>
            <Input inputMode="numeric" trailing="days" {...field('noticePeriodDays')} />
          </Field>
        </div>
      </SettingsSection>
      <SettingsSection title="Expectations" description="Never shown before shortlisting.">
        <Field label="Expected annual salary" optional error={errors.expectedSalary} className="st-half">
          <Input inputMode="numeric" leading="₹" {...field('expectedSalary')} />
        </Field>
      </SettingsSection>
      <SettingsSection title="Location">
        <div className="st-row">
          <Field label="City" optional error={errors.city}>
            <Input maxLength={100} autoComplete="address-level2" {...field('city')} />
          </Field>
          <Field label="State or region" optional error={errors.state}>
            <Input maxLength={100} autoComplete="address-level1" {...field('state')} />
          </Field>
        </div>
        <div className="st-row">
          <Field label="Country" optional error={errors.country}>
            <Input maxLength={100} autoComplete="country-name" {...field('country')} />
          </Field>
          <Field label="Postal code" optional error={errors.zipCode}>
            <Input maxLength={20} autoComplete="postal-code" {...field('zipCode')} />
          </Field>
        </div>
        <Field label="Address" optional error={errors.address}>
          <Textarea rows={2} maxLength={5000} autoComplete="street-address" {...field('address')} />
        </Field>
      </SettingsSection>
      <SettingsSection title="Links" description="Linking a portfolio is not the same as proving it is yours. Use Strengthen for that.">
        <Field label="LinkedIn" optional error={errors.linkedinUrl}>
          <Input type="url" inputMode="url" maxLength={500} placeholder="https://" {...field('linkedinUrl')} />
        </Field>
        <Field label="Portfolio or website" optional error={errors.portfolioUrl}>
          <Input type="url" inputMode="url" maxLength={500} placeholder="https://" {...field('portfolioUrl')} />
        </Field>
      </SettingsSection>
      <SettingsSection title="About you">
        <Field label="Summary" optional error={errors.profileSummary} hideLabel>
          <Textarea rows={4} maxRows={12} maxLength={5000} showCount {...field('profileSummary')} />
        </Field>
      </SettingsSection>

      <div className="st-savebar-slot">
      <div className="st-savebar" data-visible={dirty || save.isPending || undefined} aria-hidden={!dirty && !save.isPending}>
        <span className="t-strong">You have unsaved changes</span>
        <span className="c-meta st-kbd">Ctrl + S saves</span>
        <span className="grow" />
        <FormError error={save.error instanceof ApiError && save.error.fieldErrors.length ? null : save.error} />
        <Button
          variant="ghost"
          tabIndex={dirty ? undefined : -1}
          onClick={() => {
            setValues(initial)
            setErrors({})
          }}
        >
          Discard
        </Button>
        <Button type="submit" variant="primary" loading={save.isPending} tabIndex={dirty ? undefined : -1}>
          Save changes
        </Button>
      </div>
      </div>
    </form>
  )
}

function ChangePassword() {
  const { acceptSession } = useAuth()
  const notify = useNotify()
  const formRef = useRef<HTMLFormElement>(null)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})

  const change = useMutation({
    mutationFn: () => meApi.changePassword({ currentPassword, newPassword }),
    onSuccess: session => {
      if (session) acceptSession(session)
      setCurrentPassword('')
      setNewPassword('')
      setConfirm('')
      notify('success', 'Password changed', { body: 'You were signed out on your other devices.' })
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (!currentPassword) next.current = 'Enter your current password.'
    if (!isPasswordValid(newPassword)) next.next = PASSWORD_POLICY_TEXT
    if (newPassword !== confirm) next.confirm = 'The new passwords do not match.'
    setErrors(next)
    if (Object.keys(next).length === 0) change.mutate()
    else focusFirstInvalid(formRef.current)
  }

  return (
    <form ref={formRef} onSubmit={submit} noValidate>
      <SettingsSection title="Password" description="Changing it signs you out on every other device.">
        <Field label="Current password" error={errors.current} className="st-half">
          <PasswordInput value={currentPassword} onChange={setCurrentPassword} autoComplete="current-password" />
        </Field>
        <div className="st-row">
          <div>
            <Field label="New password" error={errors.next}>
              <PasswordInput value={newPassword} onChange={setNewPassword} autoComplete="new-password" aria-describedby="pw-checks" />
            </Field>
            <PasswordChecklist password={newPassword} id="pw-checks" />
          </div>
          <Field label="Confirm new password" error={errors.confirm}>
            <PasswordInput value={confirm} onChange={setConfirm} autoComplete="new-password" />
          </Field>
        </div>
        <FormError error={change.error} />
        <div>
          <Button type="submit" loading={change.isPending}>
            Change password
          </Button>
        </div>
      </SettingsSection>
    </form>
  )
}
