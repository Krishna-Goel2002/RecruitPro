import { useEffect, useId, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { errorMessage } from '../api/client'
import { privacyApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type {
  EqualOpportunityResponse,
  SurveyAgeBand,
  SurveyDisability,
  SurveyGender,
  SurveySocialCategory,
} from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { ConfirmDialog } from '../components/Dialog'
import { ErrorBlock, FormError, LoadingBlock } from '../components/Feedback'
import { Field, PasswordInput } from '../components/Form'
import { useNotify } from '../components/Notices'
import { PageHeader, Section } from '../components/PageHeader'
import { safeFilename, saveBlob } from '../lib/download'
import { formatDate } from '../lib/format'
import { AGE_BAND_OPTIONS, DISABILITY_OPTIONS, GENDER_OPTIONS, SOCIAL_CATEGORY_OPTIONS } from '../lib/labels'

export function PrivacyPage() {
  return (
    <>
      <PageHeader
        eyebrow="CANDIDATE PORTAL"
        title="Privacy and your data"
        description="Choose what you share, download everything we hold about you, or delete your account."
      />
      <SurveySection />
      <ExportSection />
      <DeleteAccountSection />
    </>
  )
}

// ---------------------------------------------------------------------------------------------------------------------
// Voluntary equal-opportunity survey (D14)
// ---------------------------------------------------------------------------------------------------------------------

interface SurveyForm {
  gender: SurveyGender | ''
  ageBand: SurveyAgeBand | ''
  disability: SurveyDisability | ''
  socialCategory: SurveySocialCategory | ''
}

const EMPTY_SURVEY: SurveyForm = { gender: '', ageBand: '', disability: '', socialCategory: '' }

function toSurveyForm(answers: EqualOpportunityResponse | null | undefined): SurveyForm {
  return {
    gender: answers?.gender ?? '',
    ageBand: answers?.ageBand ?? '',
    disability: answers?.disability ?? '',
    socialCategory: answers?.socialCategory ?? '',
  }
}

function SurveySection() {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const consentId = useId()
  const survey = useQuery({ queryKey: queryKeys.survey, queryFn: privacyApi.getSurvey })
  const [form, setForm] = useState<SurveyForm>(EMPTY_SURVEY)
  const [consent, setConsent] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)

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
      notify('success', 'Your answers were saved. Thank you.')
      void queryClient.invalidateQueries({ queryKey: queryKeys.survey })
    },
  })

  const remove = useMutation({
    mutationFn: privacyApi.deleteSurvey,
    onSuccess: () => {
      setConfirmDelete(false)
      setForm(EMPTY_SURVEY)
      queryClient.setQueryData(queryKeys.survey, null)
      notify('success', 'Your survey answers were deleted.')
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!consent) {
      setFormError('Tick the consent box to save your answers. Saving is optional.')
      return
    }
    if (!form.gender && !form.ageBand && !form.disability && !form.socialCategory) {
      setFormError('Answer at least one question, or leave the survey empty.')
      return
    }
    setFormError(null)
    save.mutate()
  }

  const stored = survey.data ?? null
  const set = (key: keyof SurveyForm) => (value: string) => setForm(current => ({ ...current, [key]: value }))

  return (
    <Section title="Equal-opportunity survey (voluntary)" id="survey">
      <div className="panel">
        <div className="purpose">
          <h3 className="subheading">Why we ask</h3>
          <p>
            These questions are optional. We use the answers only to check that hiring on RecruitPro treats all groups
            fairly, for example that one group is not shortlisted less often than another.
          </p>
          <ul>
            <li>Your answers are stored separately from your applications.</li>
            <li>Recruiters, interviewers and employers never see your individual answers.</li>
            <li>Results are reported only as totals, and groups with fewer than 5 people are hidden.</li>
            <li>Answering, not answering or choosing “Prefer not to say” has no effect on any application.</li>
            <li>You can change or delete your answers at any time.</li>
          </ul>
        </div>

        {survey.isPending ? (
          <LoadingBlock label="Loading your answers…" />
        ) : survey.isError ? (
          <ErrorBlock error={survey.error} onRetry={() => void survey.refetch()} />
        ) : (
          <form className="survey-form" onSubmit={submit} noValidate>
            {stored && (
              <p className="muted" role="note">
                You saved answers{stored.consentGivenAt ? ` on ${formatDate(stored.consentGivenAt)}` : ''}. You can update or
                delete them.
              </p>
            )}
            <div className="profile-grid">
              <Field label="Gender">
                {control => (
                  <select {...control} value={form.gender} onChange={event => set('gender')(event.target.value)}>
                    <option value="">Not answered</option>
                    {GENDER_OPTIONS.map(option => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                )}
              </Field>
              <Field label="Age group">
                {control => (
                  <select {...control} value={form.ageBand} onChange={event => set('ageBand')(event.target.value)}>
                    <option value="">Not answered</option>
                    {AGE_BAND_OPTIONS.map(option => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                )}
              </Field>
              <Field label="Do you have a disability?">
                {control => (
                  <select {...control} value={form.disability} onChange={event => set('disability')(event.target.value)}>
                    <option value="">Not answered</option>
                    {DISABILITY_OPTIONS.map(option => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                )}
              </Field>
              <Field label="Social category">
                {control => (
                  <select {...control} value={form.socialCategory} onChange={event => set('socialCategory')(event.target.value)}>
                    <option value="">Not answered</option>
                    {SOCIAL_CATEGORY_OPTIONS.map(option => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                )}
              </Field>
            </div>
            <div className="consent">
              <input id={consentId} type="checkbox" checked={consent} onChange={event => setConsent(event.target.checked)} />
              <label htmlFor={consentId}>
                I agree that RecruitPro stores these answers for fairness monitoring as described above. I can withdraw this
                consent by deleting my answers.
              </label>
            </div>
            <FormError error={formError ?? save.error} />
            <div className="form-actions">
              {stored ? (
                <button type="button" className="danger-button" onClick={() => setConfirmDelete(true)}>
                  Delete my answers
                </button>
              ) : (
                <span className="muted">Nothing is stored until you save.</span>
              )}
              <button type="submit" className="primary" disabled={save.isPending}>
                {save.isPending ? 'Saving…' : stored ? 'Update answers' : 'Save answers'}
              </button>
            </div>
          </form>
        )}
      </div>
      <ConfirmDialog
        open={confirmDelete}
        title="Delete your survey answers?"
        confirmLabel="Delete answers"
        busy={remove.isPending}
        onCancel={() => {
          setConfirmDelete(false)
          remove.reset()
        }}
        onConfirm={() => remove.mutate()}
        error={<FormError error={remove.error} />}
      >
        <p>Your answers and your consent record are removed. This does not affect your applications.</p>
      </ConfirmDialog>
    </Section>
  )
}

// ---------------------------------------------------------------------------------------------------------------------
// Data export
// ---------------------------------------------------------------------------------------------------------------------

function ExportSection() {
  const notify = useNotify()
  const exportData = useMutation({
    mutationFn: privacyApi.exportData,
    onSuccess: file => {
      const fallback = `recruitpro-my-data-${new Date().toISOString().slice(0, 10)}.json`
      saveBlob(file.blob, safeFilename(file.filename ?? fallback, fallback))
      notify('success', 'Your data export was downloaded.')
    },
    onError: error => notify('error', `Export failed: ${errorMessage(error)}`),
  })
  return (
    <Section title="Download your data" id="export">
      <div className="panel split-panel">
        <p>
          A JSON file with everything we hold about you: account, profile, resumes metadata, extracted details, claims,
          applications, work samples, interviews and survey answers.
        </p>
        <button type="button" className="quiet-button" onClick={() => exportData.mutate()} disabled={exportData.isPending}>
          {exportData.isPending ? 'Preparing…' : '↓ Download my data'}
        </button>
      </div>
    </Section>
  )
}

// ---------------------------------------------------------------------------------------------------------------------
// Account deletion
// ---------------------------------------------------------------------------------------------------------------------

function DeleteAccountSection() {
  const { endSession } = useAuth()
  const navigate = useNavigate()
  const [password, setPassword] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
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
      setFormError('Enter your password to confirm it is you.')
      return
    }
    setFormError(null)
    remove.reset()
    setConfirming(true)
  }

  return (
    <Section title="Delete your account" id="delete-account">
      <div className="panel danger-zone">
        <p>Deleting your account:</p>
        <ul>
          <li>removes your profile, resumes, extracted details, claims and survey answers;</li>
          <li>keeps your past applications and employer decisions in anonymised form, as employers must keep hiring records;</li>
          <li>cannot be undone. Download your data first if you want a copy.</li>
        </ul>
        <form className="stack-form narrow-form" onSubmit={submit} noValidate>
          <Field label="Your password" required>
            {control => <PasswordInput control={control} value={password} onChange={setPassword} autoComplete="current-password" />}
          </Field>
          <FormError error={formError} />
          <button type="submit" className="danger-button solid">
            Delete my account
          </button>
        </form>
      </div>
      <ConfirmDialog
        open={confirming}
        title="Delete your account permanently?"
        confirmLabel="Delete my account"
        busy={remove.isPending}
        onCancel={() => setConfirming(false)}
        onConfirm={() => remove.mutate()}
        error={<FormError error={remove.error} />}
      >
        <p>You will be signed out and cannot sign in with this account again.</p>
      </ConfirmDialog>
    </Section>
  )
}
