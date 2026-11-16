import { useRef, useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { claimsApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { CandidateClaim, ClaimType } from '../api/types'
import { Button, Field, Input, Select, Textarea } from '../design/components'
import { CLAIM_TYPES, CLAIM_TYPE_LABEL } from '../lib/labels'
import { isHttpUrl } from '../lib/url'
import { FormError } from './Feedback'
import { fieldErrorMap, focusFirstInvalid } from './Form'
import './form.css'

const VALUE_MAX = 1000
const URL_MAX = 500

interface ClaimFormProps {
  initialType?: ClaimType
  initialValue?: string
  submitLabel?: string
  onCreated?: (claim: CandidateClaim) => void
  onCancel?: () => void
  /** Class for the actions row (drawers make it sticky). */
  actionsClassName?: string
}

/**
 * Creates a SELF_CLAIMED claim with an optional evidence link (POST /candidates/me/claims). The new claim is put at
 * the top of the claims list straight away (optimistic) and the list is refetched; matching re-runs on the server.
 */
export function ClaimForm({ initialType = 'SKILL', initialValue = '', submitLabel = 'Add claim', onCreated, onCancel, actionsClassName }: ClaimFormProps) {
  const queryClient = useQueryClient()
  const formRef = useRef<HTMLFormElement>(null)
  const [claimType, setClaimType] = useState<ClaimType>(initialType)
  const [value, setValue] = useState(initialValue)
  const [evidenceUrl, setEvidenceUrl] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})

  const create = useMutation({
    mutationFn: () => claimsApi.create({ claimType, value: value.trim(), evidenceUrl: evidenceUrl.trim() || null }),
    onSuccess: claim => {
      queryClient.setQueryData<CandidateClaim[]>(queryKeys.claims, current => (current ? [claim, ...current.filter(c => c.id !== claim.id)] : [claim]))
      void queryClient.invalidateQueries({ queryKey: queryKeys.claims })
      void queryClient.invalidateQueries({ queryKey: queryKeys.verificationLedger })
      // Matching re-runs on the server; refresh application views that show verification.
      void queryClient.invalidateQueries({ queryKey: ['application'] })
      setValue('')
      setEvidenceUrl('')
      onCreated?.(claim)
    },
    onError: error => {
      if (error instanceof ApiError && error.fieldErrors.length > 0) {
        setErrors(fieldErrorMap(error.fieldErrors))
        focusFirstInvalid(formRef.current)
      }
    },
  })

  function validateUrl(): string | undefined {
    return evidenceUrl.trim() && !isHttpUrl(evidenceUrl.trim()) ? 'Enter a full link starting with https:// or http://.' : undefined
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (!value.trim()) next.value = 'Describe what you are claiming.'
    else if (value.trim().length > VALUE_MAX) next.value = `Use at most ${VALUE_MAX} characters.`
    const urlProblem = validateUrl()
    if (urlProblem) next.evidenceUrl = urlProblem
    setErrors(next)
    if (Object.keys(next).length === 0) create.mutate()
    else focusFirstInvalid(formRef.current)
  }

  const fieldError = create.error instanceof ApiError && create.error.fieldErrors.length > 0
  return (
    <form ref={formRef} className="c-claim-form" onSubmit={submit} noValidate>
      <Field label="Type">
        <Select value={claimType} onChange={event => setClaimType(event.target.value as ClaimType)}>
          {CLAIM_TYPES.map(type => (
            <option key={type} value={type}>
              {CLAIM_TYPE_LABEL[type]}
            </option>
          ))}
        </Select>
      </Field>
      <Field label="What you are claiming" error={errors.value} hint="Be specific: “Tally ERP, 3 years in daily use” or “CA Intermediate, ICAI, 2021”.">
        <Textarea rows={3} maxLength={VALUE_MAX} showCount value={value} autoFocus onChange={event => setValue(event.target.value)} />
      </Field>
      <Field label="Evidence link" optional error={errors.evidenceUrl} hint="A certificate, portfolio, repository or publication a recruiter can open.">
        <Input
          type="url"
          inputMode="url"
          maxLength={URL_MAX}
          placeholder="https://"
          value={evidenceUrl}
          onChange={event => setEvidenceUrl(event.target.value)}
          onBlur={() => setErrors(current => ({ ...current, evidenceUrl: validateUrl() ?? '' }))}
        />
      </Field>
      <p className="c-claim-note">Recruiters see a claim as “Your claim” (unverified) until they or a source check it.</p>
      {!fieldError && <FormError error={create.error} />}
      <div className={['c-claim-actions', actionsClassName].filter(Boolean).join(' ')}>
        {onCancel && (
          <Button variant="ghost" onClick={onCancel} disabled={create.isPending}>
            Cancel
          </Button>
        )}
        <Button type="submit" variant="primary" loading={create.isPending}>
          {submitLabel}
        </Button>
      </div>
    </form>
  )
}
