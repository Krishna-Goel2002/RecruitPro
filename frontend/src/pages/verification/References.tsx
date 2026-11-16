import { useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { verificationApi } from '../../api/endpoints'
import { queryKeys } from '../../api/queryKeys'
import type { LedgerClaim, ReferenceRelationship, ReferenceRequest, SubjectRef } from '../../api/types'
import { CONSENT_REQUIRED, ConsentCheckbox } from '../../components/Consent'
import { ConfirmDialog } from '../../components/Dialog'
import { ErrorState, FormError } from '../../components/Feedback'
import { fieldErrorMap, focusFirstInvalid } from '../../components/Form'
import { useNotify } from '../../components/Notices'
import {
  Alert, Badge, Button, Checkbox, Drawer, EmptyState, EvidenceLadder, Field, IconButton, Input, Menu, Section, Select, Skeleton, Stepper,
} from '../../design/components'
import { ArrowRight, Ellipsis, MailPlus, UserRoundCheck, X } from '../../design/icons'
import { formatDate, plural } from '../../design/lib/format'
import { useBeforeUnload } from '../../lib/hooks'
import { humanize } from '../../lib/labels'
import { referencesQuery } from '../../lib/queries'
import { MAX_OPEN_REFERENCES, RELATIONSHIP_OPTIONS, referenceStatusLabel, relationshipLabel } from '../../lib/verification'
import { subjectKey } from './model'
import { verificationErrorMessage } from './shared'

const MAX_CLAIMS = 20
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function isOpen(reference: ReferenceRequest, now: number): boolean {
  return reference.status === 'SENT' && new Date(reference.expiresAt).getTime() > now
}

export function useOpenReferences() {
  const references = useQuery(referencesQuery)
  const now = Date.now()
  const open = references.data?.filter(r => isOpen(r, now)).length ?? 0
  return { references, open, atLimit: open >= MAX_OPEN_REFERENCES }
}

const REF_TONE: Record<string, { tone: 'info' | 'success' | 'neutral'; variant: 'solid' | 'outline' | 'struck' }> = {
  SENT: { tone: 'info', variant: 'solid' },
  ANSWERED: { tone: 'success', variant: 'solid' },
  DECLINED: { tone: 'neutral', variant: 'solid' },
  EXPIRED: { tone: 'neutral', variant: 'outline' },
  CANCELLED: { tone: 'neutral', variant: 'struck' },
}

/** References list: status, who, what, when; Cancel in a row menu (confirm kept). */
export function ReferencesSection({ onAsk }: { onAsk: () => void }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const { references, open, atLimit } = useOpenReferences()
  const [toCancel, setToCancel] = useState<ReferenceRequest | null>(null)

  const cancel = useMutation({
    mutationFn: (id: number) => verificationApi.cancelReference(id),
    onSuccess: updated => {
      setToCancel(null)
      queryClient.setQueryData<ReferenceRequest[]>(queryKeys.references, current => current?.map(item => (item.id === updated.id ? updated : item)))
      notify('success', 'Request cancelled', { body: 'The link no longer works.' })
    },
  })

  return (
    <Section
      id="references"
      title="References"
      description="Ask someone who knows your work to confirm specific claims. They answer on a private page without signing in."
      actions={
        references.data && references.data.length > 0 ? (
          <>
            <span className="v-count numeric">
              {open} of {MAX_OPEN_REFERENCES} open
            </span>
            <Button icon={MailPlus} onClick={onAsk} disabled={atLimit}>
              Ask for a reference
            </Button>
          </>
        ) : undefined
      }
    >
      {references.isPending ? (
        <div className="v-rows" aria-hidden="true">
          {[0, 1].map(i => (
            <div key={i} className="v-row v-row-skeleton">
              <Skeleton width={160} height={12} />
              <Skeleton width={240} height={10} />
            </div>
          ))}
        </div>
      ) : references.isError ? (
        <ErrorState error={references.error} onRetry={() => void references.refetch()} />
      ) : references.data.length === 0 ? (
        <EmptyState
          icon={UserRoundCheck}
          title="No reference requests yet"
          description="A manager or colleague confirming a claim turns it into outside evidence, and they never need an account."
          action={
            <Button icon={MailPlus} onClick={onAsk}>
              Ask for a reference
            </Button>
          }
        />
      ) : (
        <>
          {atLimit && (
            <p className="v-muted v-limit">
              You have {open} open requests, the most allowed at once. Wait for an answer or cancel one to ask someone else.
            </p>
          )}
          <ul className="v-rows" role="list">
            {references.data.map(reference => {
              const tone = REF_TONE[reference.status] ?? REF_TONE.EXPIRED
              return (
                <li key={reference.id} className="v-row v-ref-row">
                  <div className="v-ref-main">
                    <p className="v-ref-name">
                      <span className="t-strong">{reference.refereeName}</span>
                      <Badge tone={tone.tone} variant={tone.variant}>
                        {referenceStatusLabel(reference.status)}
                      </Badge>
                    </p>
                    <p className="v-muted v-break">
                      {relationshipLabel(reference.relationship)}
                      {reference.employerName ? ` at ${reference.employerName}` : ''} · {reference.refereeEmail}
                    </p>
                    <p className="v-meta">
                      {plural(reference.claims.length, 'claim')} · sent {formatDate(reference.createdAt)}
                      {reference.status === 'SENT' ? ` · link works until ${formatDate(reference.expiresAt)}` : ''}
                      {reference.answeredAt ? ` · answered ${formatDate(reference.answeredAt)}` : ''}
                      {reference.verdictCount > 0 ? ` · ${plural(reference.verdictCount, 'result')} to review` : ''}
                    </p>
                  </div>
                  {reference.status === 'SENT' && (
                    <Menu
                      label={`Actions for the request to ${reference.refereeName}`}
                      items={[{ label: 'Cancel request', icon: X, danger: true, onSelect: () => setToCancel(reference) }]}
                      trigger={props => <IconButton {...props} label={`More actions for ${reference.refereeName}`} icon={Ellipsis} size="sm" />}
                    />
                  )}
                </li>
              )
            })}
          </ul>
        </>
      )}
      <ConfirmDialog
        open={toCancel !== null}
        title={`Cancel the request to ${toCancel?.refereeName ?? ''}?`}
        confirmLabel="Cancel request"
        busy={cancel.isPending}
        error={cancel.error}
        onCancel={() => {
          setToCancel(null)
          cancel.reset()
        }}
        onConfirm={() => toCancel && cancel.mutate(toCancel.id)}
      >
        <p>The link sent to {toCancel?.refereeName} stops working. You can send a new request later.</p>
      </ConfirmDialog>
    </Section>
  )
}

/** Ask for a reference: a two-step drawer, Who → What to confirm (+ the consent sentence, unchanged). */
export function ReferenceDrawer({ open, onClose, ledger }: { open: boolean; onClose: () => void; ledger: LedgerClaim[] | undefined }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const { atLimit } = useOpenReferences()
  const formRef = useRef<HTMLFormElement>(null)
  const [step, setStep] = useState(0)
  const [refereeName, setRefereeName] = useState('')
  const [refereeEmail, setRefereeEmail] = useState('')
  const [relationship, setRelationship] = useState<ReferenceRelationship>('MANAGER')
  const [employerName, setEmployerName] = useState('')
  const [employerDomain, setEmployerDomain] = useState('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [consent, setConsent] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  useBeforeUnload(open && (refereeName.trim() !== '' || refereeEmail.trim() !== '' || employerName.trim() !== '' || selected.size > 0))

  function reset() {
    setStep(0)
    setRefereeName('')
    setRefereeEmail('')
    setEmployerName('')
    setEmployerDomain('')
    setSelected(new Set())
    setConsent(false)
    setErrors({})
  }

  const create = useMutation({
    mutationFn: () => {
      const claims: SubjectRef[] = (ledger ?? []).filter(c => selected.has(subjectKey(c))).map(c => ({ subjectType: c.subjectType, subjectId: c.subjectId }))
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
      queryClient.setQueryData<ReferenceRequest[]>(queryKeys.references, current => [reference, ...(current ?? [])])
      notify('success', `Request sent to ${reference.refereeName}`, { body: 'The link is valid for 14 days.' })
      reset()
      onClose()
    },
    onError: error => {
      if (error instanceof ApiError && error.fieldErrors.length > 0) {
        const map = fieldErrorMap(error.fieldErrors)
        setErrors(map)
        if (map.refereeName || map.refereeEmail || map.relationship || map.employerName || map.employerDomain) setStep(0)
      }
    },
  })

  function next(event: FormEvent) {
    event.preventDefault()
    const found: Record<string, string> = {}
    if (step === 0) {
      if (!refereeName.trim()) found.refereeName = 'Enter the name of the person you are asking.'
      if (!EMAIL_PATTERN.test(refereeEmail.trim())) found.refereeEmail = 'Enter a valid email address.'
      setErrors(found)
      if (Object.keys(found).length) return focusFirstInvalid(formRef.current)
      setStep(1)
      return
    }
    if (selected.size === 0) found.claims = 'Choose at least one claim for them to confirm.'
    else if (selected.size > MAX_CLAIMS) found.claims = `Choose at most ${MAX_CLAIMS} claims.`
    if (!consent) found.consent = CONSENT_REQUIRED
    setErrors(found)
    if (Object.keys(found).length) return focusFirstInvalid(formRef.current)
    create.mutate()
  }

  const toggle = (key: string, checked: boolean) =>
    setSelected(current => {
      const nextSet = new Set(current)
      if (checked) nextSet.add(key)
      else nextSet.delete(key)
      return nextSet
    })

  return (
    <Drawer
      open={open}
      onClose={onClose}
      busy={create.isPending}
      title="Ask for a reference"
      description="They get a private link by email, valid for 14 days, and answer without signing in."
      headerExtra={<Stepper steps={['Who', 'What to confirm']} current={step} label="Ask for a reference" className="v-stepper" />}
    >
      <form ref={formRef} className="v-step" onSubmit={next} noValidate key={step}>
        {atLimit && (
          <Alert tone="warning" title="You already have 5 open requests">
            Wait for an answer or cancel a request before asking someone else. The server refuses a sixth open request.
          </Alert>
        )}
        {step === 0 ? (
          <>
            <Field label="Their name" error={errors.refereeName}>
              <Input autoComplete="off" maxLength={200} value={refereeName} autoFocus onChange={e => setRefereeName(e.target.value)} />
            </Field>
            <Field label="Their email" error={errors.refereeEmail} hint="A work address helps: its domain is compared with the employer.">
              <Input type="email" autoComplete="off" maxLength={255} value={refereeEmail} onChange={e => setRefereeEmail(e.target.value)} />
            </Field>
            <Field label="How do they know your work?" error={errors.relationship}>
              <Select value={relationship} onChange={e => setRelationship(e.target.value as ReferenceRelationship)}>
                {RELATIONSHIP_OPTIONS.map(option => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </Select>
            </Field>
            <div className="v-row-2">
              <Field label="Employer or organisation" optional error={errors.employerName}>
                <Input maxLength={200} value={employerName} onChange={e => setEmployerName(e.target.value)} />
              </Field>
              <Field label="Employer website domain" optional error={errors.employerDomain} hint="For example acme.com.">
                <Input maxLength={253} spellCheck={false} value={employerDomain} onChange={e => setEmployerDomain(e.target.value)} />
              </Field>
            </div>
            <div className="v-step-actions">
              <Button variant="ghost" onClick={onClose}>
                Cancel
              </Button>
              <Button type="submit" variant="primary" trailingIcon={ArrowRight}>
                Choose claims
              </Button>
            </div>
          </>
        ) : (
          <>
            <fieldset className="v-claim-pick" aria-invalid={errors.claims ? true : undefined} aria-describedby={errors.claims ? 'ref-claims-error' : undefined}>
              <legend className="ui-field-label">
                Claims for {refereeName.trim().split(/\s+/)[0] || 'them'} to confirm{' '}
                <span className="v-muted numeric">
                  · {selected.size} of {(ledger ?? []).length} chosen
                </span>
              </legend>
              {(ledger ?? []).length === 0 ? (
                <p className="v-muted">You have no claims yet. Upload a resume or add a claim first.</p>
              ) : (
                <ul className="v-pick-list" role="list">
                  {(ledger ?? []).map(claim => {
                    const key = subjectKey(claim)
                    return (
                      <li key={key}>
                        <Checkbox
                          checked={selected.has(key)}
                          onChange={e => toggle(key, e.target.checked)}
                          label={claim.value}
                          description={humanize(claim.claimType)}
                        />
                        <EvidenceLadder tier={claim.tier} audience="candidate" animate={false} />
                      </li>
                    )
                  })}
                </ul>
              )}
              {errors.claims && (
                <p id="ref-claims-error" className="ui-field-error" role="alert">
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
            <div className="v-step-actions">
              <Button variant="ghost" onClick={() => setStep(0)} disabled={create.isPending}>
                Back
              </Button>
              <Button type="submit" variant="primary" icon={MailPlus} loading={create.isPending}>
                Send request
              </Button>
            </div>
          </>
        )}
      </form>
    </Drawer>
  )
}
