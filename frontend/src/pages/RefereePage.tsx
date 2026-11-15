import { useId, useState, type FormEvent, type ReactNode } from 'react'
import { useParams } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { publicReferenceApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { PublicReference, RefereeAnswer } from '../api/types'
import { ConfirmDialog } from '../components/Dialog'
import { ErrorBlock, FormError, LoadingBlock } from '../components/Feedback'
import { Field } from '../components/Form'
import { formatDate } from '../lib/format'
import { useDocumentTitle } from '../lib/hooks'
import { humanize } from '../lib/labels'
import { REFEREE_ANSWER_OPTIONS, refereeRelationshipLabel } from '../lib/verification'

const COMMENT_MAX = 2000

function Shell({ title, children }: { title: string; children: ReactNode }) {
  useDocumentTitle(title)
  return (
    <main className="auth-shell" id="main">
      <div className="auth-card referee-card">
        <div className="brand-mark" aria-hidden="true">
          R
        </div>
        <span className="eyebrow">RECRUITPRO · REFERENCE REQUEST</span>
        <h1 tabIndex={-1} data-page-heading>
          {title}
        </h1>
        {children}
      </div>
    </main>
  )
}

/** The referee's page at /reference/:token. No sign-in; the token in the link is the only credential. */
export function RefereePage() {
  const { token = '' } = useParams()
  const reference = useQuery({
    queryKey: queryKeys.publicReference(token),
    queryFn: () => publicReferenceApi.get(token),
    retry: false,
    staleTime: Infinity,
  })
  const [done, setDone] = useState<'answered' | 'declined' | null>(null)

  if (done) {
    return (
      <Shell title={done === 'answered' ? 'Thank you' : 'Request declined'}>
        <p className="alert success" role="status">
          {done === 'answered'
            ? 'Your answers were sent. The candidate will see the results, not your comments word for word, and decides whether to share them.'
            : 'You declined this request. The candidate will be told; no answers were recorded.'}
        </p>
        <p className="muted">You can close this page.</p>
      </Shell>
    )
  }
  if (reference.isPending) {
    return (
      <Shell title="Reference request">
        <LoadingBlock label="Loading the request…" />
      </Shell>
    )
  }
  if (reference.isError) {
    const status = reference.error instanceof ApiError ? reference.error.status : 0
    if (status === 404) {
      return (
        <Shell title="This link is not valid">
          <p className="muted">Check that you copied the whole link from the email. If it still does not work, ask the candidate to send a new request.</p>
        </Shell>
      )
    }
    if (status === 410) {
      return (
        <Shell title="This request is closed">
          <p className="muted">The link has expired, was cancelled, or the request was already answered. Nothing more is needed from you.</p>
        </Shell>
      )
    }
    return (
      <Shell title="Reference request">
        <ErrorBlock error={reference.error} onRetry={() => void reference.refetch()} />
      </Shell>
    )
  }
  return <RefereeForm token={token} data={reference.data} onDone={setDone} />
}

function RefereeForm({ token, data, onDone }: { token: string; data: PublicReference; onDone: (state: 'answered' | 'declined') => void }) {
  const baseId = useId()
  const [answers, setAnswers] = useState<Record<string, RefereeAnswer>>({})
  const [comments, setComments] = useState<Record<string, string>>({})
  const [refereeComment, setRefereeComment] = useState('')
  const [confirmIdentity, setConfirmIdentity] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [declineOpen, setDeclineOpen] = useState(false)

  const submit = useMutation({
    mutationFn: () =>
      publicReferenceApi.submit(token, {
        answers: data.claims.map(claim => ({
          claimId: claim.id,
          answer: answers[claim.id],
          comment: comments[claim.id]?.trim() || null,
        })),
        refereeComment: refereeComment.trim() || null,
        confirmIdentity: true,
      }),
    onSuccess: () => onDone('answered'),
  })

  const decline = useMutation({
    mutationFn: () => publicReferenceApi.submit(token, { decline: true }),
    onSuccess: () => onDone('declined'),
  })

  function send(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    for (const claim of data.claims) if (!answers[claim.id]) next[claim.id] = 'Choose an answer.'
    if (!confirmIdentity) next.confirmIdentity = 'Please confirm who you are.'
    setErrors(next)
    if (Object.keys(next).length === 0) submit.mutate()
  }

  const closedMessage = (error: unknown) =>
    error instanceof ApiError && error.status === 410 ? 'This request has expired or was already answered.' : error

  return (
    <Shell title={`Can you confirm ${data.candidateFirstName}'s work?`}>
      <p className="muted auth-intro">
        {data.candidateFirstName} named you as {refereeRelationshipLabel(data.relationship)}
        {data.employerName ? ` at ${data.employerName}` : ''} and asked you to confirm the claims below. Answer only what you know; “Cannot
        confirm” is a perfectly good answer. This link works until {formatDate(data.expiresAt)}.
      </p>
      <form className="stack-form" onSubmit={send} noValidate>
        {data.claims.map((claim, index) => {
          const groupId = `${baseId}-claim-${index}`
          const errorId = `${groupId}-error`
          return (
            <fieldset key={claim.id} className="plain-fieldset referee-claim" aria-describedby={errors[claim.id] ? errorId : undefined}>
              <legend>
                <span className="muted">{humanize(claim.claimType)}:</span> {claim.value}
              </legend>
              <div className="choice-list">
                {REFEREE_ANSWER_OPTIONS.map(option => (
                  <label key={option.value} className={`choice${answers[claim.id] === option.value ? ' selected' : ''}`}>
                    <input
                      type="radio"
                      name={groupId}
                      value={option.value}
                      checked={answers[claim.id] === option.value}
                      onChange={() => setAnswers(current => ({ ...current, [claim.id]: option.value }))}
                    />
                    <span>
                      <strong>{option.label}</strong>
                      <small>{option.description}</small>
                    </span>
                  </label>
                ))}
              </div>
              {errors[claim.id] && (
                <p id={errorId} className="field-error" role="alert">
                  {errors[claim.id]}
                </p>
              )}
              <Field label="Comment" optional>
                {control => (
                  <textarea
                    {...control}
                    rows={2}
                    maxLength={COMMENT_MAX}
                    value={comments[claim.id] ?? ''}
                    onChange={event => setComments(current => ({ ...current, [claim.id]: event.target.value }))}
                  />
                )}
              </Field>
            </fieldset>
          )
        })}

        <Field label="Anything else you would like to add?" optional>
          {control => <textarea {...control} rows={3} maxLength={COMMENT_MAX} value={refereeComment} onChange={event => setRefereeComment(event.target.value)} />}
        </Field>

        <div>
          <div className="consent">
            <input
              id={`${baseId}-identity`}
              type="checkbox"
              checked={confirmIdentity}
              aria-invalid={errors.confirmIdentity ? true : undefined}
              aria-describedby={errors.confirmIdentity ? `${baseId}-identity-error` : undefined}
              onChange={event => setConfirmIdentity(event.target.checked)}
            />
            <label htmlFor={`${baseId}-identity`}>
              I confirm that I am the person this request was sent to, and that my answers are honest and based on my own knowledge.
            </label>
          </div>
          {errors.confirmIdentity && (
            <p id={`${baseId}-identity-error`} className="field-error" role="alert">
              {errors.confirmIdentity}
            </p>
          )}
        </div>

        {submit.error && <FormError error={closedMessage(submit.error)} />}
        <div className="form-actions">
          <button type="submit" className="primary" disabled={submit.isPending}>
            {submit.isPending ? 'Sending…' : 'Send answers'}
          </button>
          <button type="button" className="text-button" onClick={() => setDeclineOpen(true)} disabled={submit.isPending}>
            I would rather not answer
          </button>
        </div>
      </form>

      <ConfirmDialog
        open={declineOpen}
        title="Decline this request?"
        confirmLabel="Decline"
        busy={decline.isPending}
        onCancel={() => {
          setDeclineOpen(false)
          decline.reset()
        }}
        onConfirm={() => decline.mutate()}
        error={<FormError error={decline.error ? closedMessage(decline.error) : null} />}
      >
        <p>{data.candidateFirstName} will be told that you declined. You will not be asked again through this link.</p>
      </ConfirmDialog>
    </Shell>
  )
}
