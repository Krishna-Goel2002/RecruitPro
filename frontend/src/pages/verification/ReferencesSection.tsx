import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { verificationApi } from '../../api/endpoints'
import { queryKeys } from '../../api/queryKeys'
import type { ReferenceRelationship, ReferenceRequest, SubjectRef } from '../../api/types'
import { CONSENT_REQUIRED, ConsentCheckbox } from '../../components/Consent'
import { ConfirmDialog } from '../../components/Dialog'
import { EmptyState, ErrorBlock, FormError, LoadingBlock } from '../../components/Feedback'
import { Field, fieldErrorMap } from '../../components/Form'
import { useNotify } from '../../components/Notices'
import { Section } from '../../components/PageHeader'
import { formatDate } from '../../lib/format'
import { humanize } from '../../lib/labels'
import { MAX_OPEN_REFERENCES, RELATIONSHIP_OPTIONS, referenceStatusLabel, relationshipLabel } from '../../lib/verification'
import { verificationErrorMessage } from './shared'

const MAX_CLAIMS = 20
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function subjectKey(ref: SubjectRef): string {
  return `${ref.subjectType}:${ref.subjectId}`
}

function isOpen(reference: ReferenceRequest, now: number): boolean {
  return reference.status === 'SENT' && new Date(reference.expiresAt).getTime() > now
}

/** Ask a former manager, colleague or client to confirm claims; list and cancel requests. */
export function ReferencesSection() {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const references = useQuery({ queryKey: queryKeys.references, queryFn: verificationApi.references })
  const ledger = useQuery({ queryKey: queryKeys.verificationLedger, queryFn: verificationApi.ledger })

  const [refereeName, setRefereeName] = useState('')
  const [refereeEmail, setRefereeEmail] = useState('')
  const [relationship, setRelationship] = useState<ReferenceRelationship>('MANAGER')
  const [employerName, setEmployerName] = useState('')
  const [employerDomain, setEmployerDomain] = useState('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [consent, setConsent] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [toCancel, setToCancel] = useState<ReferenceRequest | null>(null)

  const now = Date.now()
  const openCount = references.data?.filter(reference => isOpen(reference, now)).length ?? 0
  const atLimit = openCount >= MAX_OPEN_REFERENCES

  const create = useMutation({
    mutationFn: () => {
      const claims: SubjectRef[] = (ledger.data ?? [])
        .filter(claim => selected.has(subjectKey(claim)))
        .map(claim => ({ subjectType: claim.subjectType, subjectId: claim.subjectId }))
      return verificationApi.createReference({
        refereeName: refereeName.trim(),
        refereeEmail: refereeEmail.trim(),
        relationship,
        employerName: employerName.trim() || null,
        employerDomain: employerDomain.trim() || null,
        claims,
        consent: true,
      })
    },
    onSuccess: reference => {
      setRefereeName('')
      setRefereeEmail('')
      setEmployerName('')
      setEmployerDomain('')
      setSelected(new Set())
      setConsent(false)
      setErrors({})
      queryClient.setQueryData<ReferenceRequest[]>(queryKeys.references, current => [reference, ...(current ?? [])])
      notify('success', `Request sent to ${reference.refereeName}. The link is valid for 14 days.`)
    },
    onError: error => {
      if (error instanceof ApiError && error.fieldErrors.length > 0) setErrors(fieldErrorMap(error.fieldErrors))
    },
  })

  const cancel = useMutation({
    mutationFn: (id: number) => verificationApi.cancelReference(id),
    onSuccess: updated => {
      setToCancel(null)
      queryClient.setQueryData<ReferenceRequest[]>(queryKeys.references, current => current?.map(item => (item.id === updated.id ? updated : item)))
      notify('success', 'Request cancelled. The link no longer works.')
    },
  })

  function toggle(key: string, checked: boolean) {
    setSelected(current => {
      const next = new Set(current)
      if (checked) next.add(key)
      else next.delete(key)
      return next
    })
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (!refereeName.trim()) next.refereeName = 'Enter the name of the person you are asking.'
    if (!EMAIL_PATTERN.test(refereeEmail.trim())) next.refereeEmail = 'Enter a valid email address.'
    if (selected.size === 0) next.claims = 'Choose at least one claim for them to confirm.'
    else if (selected.size > MAX_CLAIMS) next.claims = `Choose at most ${MAX_CLAIMS} claims.`
    if (!consent) next.consent = CONSENT_REQUIRED
    setErrors(next)
    if (Object.keys(next).length === 0) create.mutate()
  }

  return (
    <Section
      title="References"
      id="references"
      description="Ask someone who knows your work to confirm specific claims. They answer on a private page without signing in."
    >
      <div className="detail-grid evidence-grid">
        <div className="panel">
          <h3 className="panel-title">Ask for a reference</h3>
          {atLimit ? (
            <p className="alert info" role="status">
              You have {openCount} open requests, the most allowed at once ({MAX_OPEN_REFERENCES}). Wait for an answer or cancel one to ask
              someone else.
            </p>
          ) : (
            <form className="stack-form" onSubmit={submit} noValidate>
              <Field label="Their name" required error={errors.refereeName}>
                {control => <input {...control} type="text" autoComplete="off" maxLength={200} value={refereeName} onChange={event => setRefereeName(event.target.value)} />}
              </Field>
              <Field label="Their email" required error={errors.refereeEmail} hint="A work address helps: its domain is compared with the employer.">
                {control => (
                  <input {...control} type="email" autoComplete="off" maxLength={320} value={refereeEmail} onChange={event => setRefereeEmail(event.target.value)} />
                )}
              </Field>
              <Field label="How do they know your work?" required error={errors.relationship}>
                {control => (
                  <select {...control} value={relationship} onChange={event => setRelationship(event.target.value as ReferenceRelationship)}>
                    {RELATIONSHIP_OPTIONS.map(option => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                )}
              </Field>
              <Field label="Employer or organisation" optional error={errors.employerName}>
                {control => <input {...control} type="text" maxLength={200} value={employerName} onChange={event => setEmployerName(event.target.value)} />}
              </Field>
              <Field label="Employer website domain" optional error={errors.employerDomain} hint="For example acme.com.">
                {control => <input {...control} type="text" maxLength={253} value={employerDomain} onChange={event => setEmployerDomain(event.target.value)} />}
              </Field>

              <fieldset className="plain-fieldset" aria-describedby={errors.claims ? 'reference-claims-error' : undefined}>
                <legend>Claims to confirm</legend>
                {ledger.isPending ? (
                  <LoadingBlock label="Loading your claims…" />
                ) : ledger.isError ? (
                  <ErrorBlock error={ledger.error} onRetry={() => void ledger.refetch()} />
                ) : ledger.data.length === 0 ? (
                  <p className="muted">You have no claims yet. Upload a resume or add a claim on the Evidence page first.</p>
                ) : (
                  <div className="choice-list reference-claims">
                    {ledger.data.map(claim => {
                      const key = subjectKey(claim)
                      return (
                        <label key={key} className={`choice${selected.has(key) ? ' selected' : ''}`}>
                          <input type="checkbox" checked={selected.has(key)} onChange={event => toggle(key, event.target.checked)} />
                          <span>
                            <strong>{claim.value}</strong>
                            <small>{humanize(claim.claimType)}</small>
                          </span>
                        </label>
                      )
                    })}
                  </div>
                )}
                {errors.claims && (
                  <p id="reference-claims-error" className="field-error" role="alert">
                    {errors.claims}
                  </p>
                )}
              </fieldset>

              <ConsentCheckbox
                checked={consent}
                error={errors.consent}
                onChange={checked => {
                  setConsent(checked)
                  if (checked) setErrors(current => ({ ...current, consent: '' }))
                }}
              >
                I agree that RecruitPro may email this person with my first name and the claims I chose, and store their answers.
              </ConsentCheckbox>

              {create.error && <FormError error={verificationErrorMessage(create.error, 'reference')} />}
              <div className="form-actions">
                <button type="submit" className="primary" disabled={create.isPending}>
                  {create.isPending ? 'Sending…' : 'Send request'}
                </button>
              </div>
            </form>
          )}
        </div>

        <div>
          {references.isPending ? (
            <LoadingBlock label="Loading your requests…" />
          ) : references.isError ? (
            <ErrorBlock error={references.error} onRetry={() => void references.refetch()} />
          ) : references.data.length === 0 ? (
            <EmptyState icon="✉" title="No reference requests yet" />
          ) : (
            <ul className="plain-list card-list">
              {references.data.map(reference => (
                <li key={reference.id} className="claim-card">
                  <div className="claim-head">
                    <strong>{reference.refereeName}</strong>
                    <span className={`tag ${reference.status === 'ANSWERED' ? 'green' : reference.status === 'SENT' ? '' : 'amber'}`}>
                      {referenceStatusLabel(reference.status)}
                    </span>
                  </div>
                  <p className="muted break-anywhere">
                    {reference.refereeEmail} · {relationshipLabel(reference.relationship)}
                    {reference.employerName ? ` · ${reference.employerName}` : ''}
                  </p>
                  <p className="muted">
                    {reference.claims.length} claim{reference.claims.length === 1 ? '' : 's'} · sent {formatDate(reference.createdAt)}
                    {reference.status === 'SENT' ? ` · link expires ${formatDate(reference.expiresAt)}` : ''}
                    {reference.answeredAt ? ` · answered ${formatDate(reference.answeredAt)}` : ''}
                    {reference.verdictCount > 0 ? ` · ${reference.verdictCount} result${reference.verdictCount === 1 ? '' : 's'} in “Your results”` : ''}
                  </p>
                  {reference.status === 'SENT' && (
                    <div className="claim-actions">
                      <button type="button" className="danger-button" onClick={() => setToCancel(reference)} aria-label={`Cancel request to ${reference.refereeName}`}>
                        Cancel request
                      </button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      <ConfirmDialog
        open={toCancel !== null}
        title="Cancel this request?"
        confirmLabel="Cancel request"
        busy={cancel.isPending}
        onCancel={() => {
          setToCancel(null)
          cancel.reset()
        }}
        onConfirm={() => toCancel && cancel.mutate(toCancel.id)}
        error={<FormError error={cancel.error} />}
      >
        <p>The link sent to {toCancel?.refereeName} will stop working. You can send a new request later.</p>
      </ConfirmDialog>
    </Section>
  )
}
