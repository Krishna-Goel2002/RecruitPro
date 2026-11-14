import { useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { claimsApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { CandidateClaim, ClaimType } from '../api/types'
import { CLAIM_LABEL, CLAIM_TYPES, CLAIM_TYPE_LABEL } from '../lib/labels'
import { isHttpUrl } from '../lib/url'
import { Field, fieldErrorMap } from './Form'
import { FormError } from './Feedback'

const VALUE_MAX = 1000
const URL_MAX = 500

interface ClaimFormProps {
  initialType?: ClaimType
  initialValue?: string
  submitLabel?: string
  onCreated?: (claim: CandidateClaim) => void
  onCancel?: () => void
}

/** Creates a SELF_CLAIMED claim with an optional evidence link (POST /candidates/me/claims). */
export function ClaimForm({ initialType = 'SKILL', initialValue = '', submitLabel = 'Add claim', onCreated, onCancel }: ClaimFormProps) {
  const queryClient = useQueryClient()
  const [claimType, setClaimType] = useState<ClaimType>(initialType)
  const [value, setValue] = useState(initialValue)
  const [evidenceUrl, setEvidenceUrl] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})

  const create = useMutation({
    mutationFn: () => claimsApi.create({ claimType, value: value.trim(), evidenceUrl: evidenceUrl.trim() || null }),
    onSuccess: claim => {
      setValue('')
      setEvidenceUrl('')
      void queryClient.invalidateQueries({ queryKey: queryKeys.claims })
      // Matching re-runs on the server; refresh application views that show verification.
      void queryClient.invalidateQueries({ queryKey: ['application'] })
      onCreated?.(claim)
    },
    onError: error => {
      if (error instanceof ApiError && error.fieldErrors.length > 0) setErrors(fieldErrorMap(error.fieldErrors))
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (!value.trim()) next.value = 'Describe what you are claiming.'
    else if (value.trim().length > VALUE_MAX) next.value = `Use at most ${VALUE_MAX} characters.`
    if (evidenceUrl.trim() && !isHttpUrl(evidenceUrl.trim())) next.evidenceUrl = 'Enter a full link starting with https:// or http://.'
    setErrors(next)
    if (Object.keys(next).length === 0) create.mutate()
  }

  return (
    <form className="stack-form claim-form" onSubmit={submit} noValidate>
      <p className="claim-note">{CLAIM_LABEL}.</p>
      <Field label="Type" required>
        {control => (
          <select {...control} value={claimType} onChange={event => setClaimType(event.target.value as ClaimType)}>
            {CLAIM_TYPES.map(type => (
              <option key={type} value={type}>
                {CLAIM_TYPE_LABEL[type]}
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field
        label="What you are claiming"
        required
        error={errors.value}
        hint="For example: “Tally ERP, 3 years in daily use” or “CA Intermediate, ICAI, 2021”."
      >
        {control => <textarea {...control} rows={3} maxLength={VALUE_MAX} value={value} onChange={event => setValue(event.target.value)} />}
      </Field>
      <Field
        label="Evidence link"
        optional
        error={errors.evidenceUrl}
        hint="A certificate, portfolio, repository or publication a recruiter can open."
      >
        {control => (
          <input
            {...control}
            type="url"
            inputMode="url"
            maxLength={URL_MAX}
            placeholder="https://"
            value={evidenceUrl}
            onChange={event => setEvidenceUrl(event.target.value)}
          />
        )}
      </Field>
      <FormError error={create.error} />
      <div className="dialog-actions">
        {onCancel && (
          <button type="button" className="quiet-button" onClick={onCancel} disabled={create.isPending}>
            Cancel
          </button>
        )}
        <button type="submit" className="primary" disabled={create.isPending}>
          {create.isPending ? 'Saving…' : submitLabel}
        </button>
      </div>
    </form>
  )
}
