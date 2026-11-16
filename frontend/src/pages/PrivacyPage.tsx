import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { errorMessage } from '../api/client'
import { privacyApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { EqualOpportunityResponse, SurveyAgeBand, SurveyDisability, SurveyGender, SurveySocialCategory } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { ConsentCheckbox } from '../components/Consent'
import { ConfirmDialog } from '../components/Dialog'
import { ErrorState, FormError } from '../components/Feedback'
import { PasswordInput, focusFirstInvalid } from '../components/Form'
import { useNotify } from '../components/Notices'
import { Button, Field, LoadingRegion, PageHeader, Panel, Select, SkeletonText } from '../design/components'
import { Check, Download, EyeOff, Icon, Trash2 } from '../design/icons'
import { formatDate } from '../design/lib/format'
import { safeFilename, saveBlob } from '../lib/download'
import { useBeforeUnload } from '../lib/hooks'
import { AGE_BAND_OPTIONS, DISABILITY_OPTIONS, GENDER_OPTIONS, SOCIAL_CATEGORY_OPTIONS } from '../lib/labels'
import './common.css'
import './settings/settings.css'

function SettingsSection({ title, description, children, id }: { title: string; description?: ReactNode; children: ReactNode; id?: string }) {
  return (
    <section className="st-section" aria-labelledby={id ? `${id}-t` : undefined}>
      <div className="st-section-head">
        <h2 id={id ? `${id}-t` : undefined} className="t-h3">
          {title}
        </h2>
        {description && <div className="c-meta">{description}</div>}
      </div>
      <div className="st-fields">{children}</div>
    </section>
  )
}

/** Privacy and your data (FLOWS-CANDIDATE §9): survey, export, and the danger zone (the only bordered box). */
export function PrivacyPage() {
  return (
    <div className="st-page">
      <PageHeader title="Privacy and your data" description="Choose what you share, download everything we hold about you, or delete your account." />
      <SurveySection />
      <ExportSection />
      <DeleteAccountSection />
    </div>
  )
}

interface SurveyForm {
  gender: SurveyGender | ''
  ageBand: SurveyAgeBand | ''
  disability: SurveyDisability | ''
  socialCategory: SurveySocialCategory | ''
}
const EMPTY_SURVEY: SurveyForm = { gender: '', ageBand: '', disability: '', socialCategory: '' }
const toSurveyForm = (a: EqualOpportunityResponse | null | undefined): SurveyForm => ({
  gender: a?.gender ?? '',
  ageBand: a?.ageBand ?? '',
  disability: a?.disability ?? '',
  socialCategory: a?.socialCategory ?? '',
})

function SurveySection() {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const survey = useQuery({ queryKey: queryKeys.survey, queryFn: privacyApi.getSurvey })
  const [form, setForm] = useState<SurveyForm>(EMPTY_SURVEY)
  const [consent, setConsent] = useState(false)
  const [errors, setErrors] = useState<{ consent?: string; form?: string }>({})
  const [confirmDelete, setConfirmDelete] = useState(false)
  const formRef = useRef<HTMLFormElement>(null)

  useEffect(() => {
    if (survey.data !== undefined) setForm(toSurveyForm(survey.data))
  }, [survey.data])

  const save = useMutation({
    mutationFn: () =>
      privacyApi.saveSurvey({
        gender: form.gender || null,
        ageBand: form.ageBand || null,
        disability: form.disability || null,
        socialCategory: form.socialCategory || null,
        consent: true,
      }),
    onSuccess: () => {
      setConsent(false)
      notify('success', 'Answers saved', { body: 'Thank you. They are stored separately from your applications.' })
      void queryClient.invalidateQueries({ queryKey: queryKeys.survey })
    },
  })

  const remove = useMutation({
    mutationFn: privacyApi.deleteSurvey,
    onSuccess: () => {
      setConfirmDelete(false)
      setForm(EMPTY_SURVEY)
      queryClient.setQueryData(queryKeys.survey, null)
      notify('success', 'Survey answers deleted')
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: typeof errors = {}
    if (!form.gender && !form.ageBand && !form.disability && !form.socialCategory) next.form = 'Answer at least one question, or leave the survey empty.'
    if (!consent) next.consent = 'Tick the consent box to save your answers. Saving is optional.'
    setErrors(next)
    if (next.form || next.consent) return focusFirstInvalid(formRef.current)
    save.mutate()
  }

  const stored = survey.data ?? null
  const baseline = toSurveyForm(stored)
  const changed = (Object.keys(form) as (keyof SurveyForm)[]).some(k => form[k] !== baseline[k])
  useBeforeUnload(survey.isSuccess && changed)
  const set = (key: keyof SurveyForm) => (e: { target: { value: string } }) => setForm(current => ({ ...current, [key]: e.target.value }))
  const why = [
    'Stored separately from your applications.',
    'Recruiters, interviewers and employers never see your individual answers.',
    'Reported only as totals; groups smaller than 5 are hidden.',
    'Answering, not answering or "Prefer not to say" has no effect on any application.',
    'You can change or delete your answers at any time.',
  ]

  return (
    <SettingsSection
      id="survey"
      title="Equal-opportunity survey"
      description={
        <>
          <p style={{ marginBottom: 10 }}>Voluntary. Used only to check that hiring treats all groups fairly.</p>
          <ul className="pv-why">
            {why.map(w => (
              <li key={w}>
                <Icon icon={EyeOff} size={12} />
                <span>{w}</span>
              </li>
            ))}
          </ul>
        </>
      }
    >
      {survey.isPending ? (
        <LoadingRegion label="Loading your answers">
          <SkeletonText lines={4} />
        </LoadingRegion>
      ) : survey.isError ? (
        <ErrorState error={survey.error} onRetry={() => void survey.refetch()} />
      ) : (
        <form ref={formRef} onSubmit={submit} noValidate className="st-fields" style={{ maxWidth: 'none' }}>
          {stored && <p className="c-muted">You saved answers{stored.consentGivenAt ? ` on ${formatDate(stored.consentGivenAt)}` : ''}. You can update or delete them.</p>}
          <div className="st-row">
            <Field label="Gender">
              <Select value={form.gender} onChange={set('gender')}>
                <option value="">Not answered</option>
                {GENDER_OPTIONS.map(o => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Age group">
              <Select value={form.ageBand} onChange={set('ageBand')}>
                <option value="">Not answered</option>
                {AGE_BAND_OPTIONS.map(o => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </Select>
            </Field>
          </div>
          <div className="st-row">
            <Field label="Do you have a disability?">
              <Select value={form.disability} onChange={set('disability')}>
                <option value="">Not answered</option>
                {DISABILITY_OPTIONS.map(o => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Social category">
              <Select value={form.socialCategory} onChange={set('socialCategory')}>
                <option value="">Not answered</option>
                {SOCIAL_CATEGORY_OPTIONS.map(o => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </Select>
            </Field>
          </div>
          <ConsentCheckbox
            checked={consent}
            error={errors.consent}
            onChange={checked => {
              setConsent(checked)
              if (checked) setErrors(current => ({ ...current, consent: undefined }))
            }}
          >
            I agree that RecruitPro stores these answers for fairness monitoring as described above. I can withdraw this consent by deleting my answers.
          </ConsentCheckbox>
          <FormError error={errors.form ?? save.error} />
          <div className="pv-actions">
            {stored && (
              <Button variant="danger-ghost" icon={Trash2} onClick={() => setConfirmDelete(true)}>
                Delete my answers
              </Button>
            )}
            <Button type="submit" variant="primary" icon={Check} loading={save.isPending}>
              {stored ? 'Update answers' : 'Save answers'}
            </Button>
          </div>
        </form>
      )}
      <ConfirmDialog
        open={confirmDelete}
        title="Delete your survey answers?"
        confirmLabel="Delete answers"
        busy={remove.isPending}
        error={remove.error}
        onCancel={() => {
          setConfirmDelete(false)
          remove.reset()
        }}
        onConfirm={() => remove.mutate()}
      >
        <p>Your answers and your consent record are removed. This does not affect your applications.</p>
      </ConfirmDialog>
    </SettingsSection>
  )
}

function ExportSection() {
  const notify = useNotify()
  const exportData = useMutation({
    mutationFn: privacyApi.exportData,
    onSuccess: file => {
      const fallback = `my-data-${new Date().toISOString().slice(0, 10)}.json`
      saveBlob(file.blob, safeFilename(file.filename ?? fallback, fallback))
      notify('success', 'Your data export was downloaded')
    },
    onError: error => notify('error', `Export failed: ${errorMessage(error)}`),
  })
  return (
    <SettingsSection id="export" title="Download your data" description="A JSON file you can keep or move elsewhere.">
      <p className="c-muted">
        Everything we hold about you: account, profile, resume details, extracted facts, claims, applications, work samples, interviews, verification
        results and survey answers.
      </p>
      <div>
        <Button icon={Download} loading={exportData.isPending} onClick={() => exportData.mutate()}>
          Download my data
        </Button>
      </div>
    </SettingsSection>
  )
}

function DeleteAccountSection() {
  const { endSession } = useAuth()
  const navigate = useNavigate()
  const formRef = useRef<HTMLFormElement>(null)
  const [password, setPassword] = useState('')
  const [fieldError, setFieldError] = useState<string>()
  const [confirming, setConfirming] = useState(false)

  const remove = useMutation({
    mutationFn: () => privacyApi.deleteAccount({ password }),
    onSuccess: () => {
      setConfirming(false)
      endSession('account-deleted')
      navigate('/login', { replace: true })
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!password) {
      setFieldError('Enter your password to confirm it is you.')
      return focusFirstInvalid(formRef.current)
    }
    setFieldError(undefined)
    remove.reset()
    setConfirming(true)
  }

  return (
    <Panel tone="danger" className="pv-danger" aria-labelledby="pv-delete-title">
      <h2 id="pv-delete-title" className="t-h3">
        Delete your account
      </h2>
      <ul>
        <li>Removes your profile, resumes, extracted details, claims, verification sources and survey answers.</li>
        <li>Keeps past applications and employer decisions in anonymised form, as employers must keep hiring records.</li>
        <li>Cannot be undone. Download your data first if you want a copy.</li>
      </ul>
      <form ref={formRef} className="pv-danger-form" onSubmit={submit} noValidate>
        <Field label="Your password" error={fieldError}>
          <PasswordInput value={password} onChange={setPassword} autoComplete="current-password" />
        </Field>
        <Button type="submit" variant="danger-ghost" icon={Trash2}>
          Delete my account…
        </Button>
      </form>
      <ConfirmDialog
        open={confirming}
        title="Delete your account permanently?"
        confirmLabel="Delete my account"
        busy={remove.isPending}
        error={remove.error}
        onCancel={() => setConfirming(false)}
        onConfirm={() => remove.mutate()}
      >
        <p>You will be signed out and cannot sign in with this account again.</p>
      </ConfirmDialog>
    </Panel>
  )
}
