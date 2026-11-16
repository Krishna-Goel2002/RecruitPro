import { useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { useParams } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { publicReferenceApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { PublicReference, RefereeAnswer } from '../api/types'
import { ConfirmDialog } from '../components/Dialog'
import { ErrorState, FormError } from '../components/Feedback'
import brand from '../design/brand/active'
import { Button, Checkbox, Field, LoadingRegion, SegmentedControl, Skeleton, SkeletonText, Textarea, ThemeSwitch, useDocumentTitle } from '../design/components'
import { CircleAlert, CircleCheck, Clock, Icon, Link2, MailPlus } from '../design/icons'
import { formatDate } from '../design/lib/format'
import { humanize } from '../lib/labels'
import { refereeRelationshipLabel } from '../lib/verification'
import './referee/referee.css'

const COMMENT_MAX = 2000

const ANSWERS: { value: RefereeAnswer; label: string }[] = [
  { value: 'CONFIRM', label: 'Confirm' },
  { value: 'PARTLY', label: 'Partly' },
  { value: 'CANNOT_CONFIRM', label: "Can't confirm" },
  { value: 'DISAGREE', label: 'Disagree' },
]

function Shell({ title, children, footer }: { title: string; children: ReactNode; footer?: ReactNode }) {
  useDocumentTitle(title)
  return (
    <div className="rf">
      <header className="rf-top">
        <span className="rf-brand">
          <brand.Mark size={20} />
          <span className="p-wordmark">{brand.name}</span>
          <span className="rf-brand-label">Reference request</span>
        </span>
        <ThemeSwitch variant="icon" />
      </header>
      <main id="main" className="rf-main">
        {children}
      </main>
      {footer}
    </div>
  )
}

function StatePage({ title, icon, tone, children }: { title: string; icon: typeof CircleCheck; tone?: 'success' | 'neutral'; children: ReactNode }) {
  return (
    <Shell title={title}>
      <div className="rf-state">
        <span className="rf-state-icon" data-tone={tone ?? 'neutral'}>
          <Icon icon={icon} size={20} />
        </span>
        <h1 className="rf-title" tabIndex={-1} data-page-heading="">
          {title}
        </h1>
        <div className="rf-intro">{children}</div>
      </div>
    </Shell>
  )
}

/** The referee's page at /reference/:token (REDESIGN_PLAN A5). No sign-in; the token in the link is the only credential. */
export function RefereePage() {
  const { token = '' } = useParams()
  const reference = useQuery({
    queryKey: queryKeys.publicReference(token),
    queryFn: () => publicReferenceApi.get(token),
    retry: false,
    staleTime: Infinity,
  })
  const [done, setDone] = useState<'answered' | 'declined' | null>(null)

  if (done === 'answered') {
    return (
      <StatePage title="Thank you" icon={CircleCheck} tone="success">
        <p>Your answers were sent. The candidate will see the results, not your comments word for word, and decides whether to share them.</p>
        <p className="rf-muted">You can close this page.</p>
      </StatePage>
    )
  }
  if (done === 'declined') {
    return (
      <StatePage title="Request declined" icon={CircleCheck}>
        <p>The candidate will be told; no answers were recorded. You can close this page.</p>
      </StatePage>
    )
  }
  if (reference.isPending) {
    return (
      <Shell title="Reference request">
        <LoadingRegion label="Loading the request">
          <Skeleton width={320} height={28} />
          <div style={{ marginTop: 16 }}>
            <SkeletonText lines={3} />
          </div>
        </LoadingRegion>
      </Shell>
    )
  }
  if (reference.isError) {
    const status = reference.error instanceof ApiError ? reference.error.status : 0
    if (status === 404) {
      return (
        <StatePage title="This link is not valid" icon={Link2}>
          <p>Check that you copied the whole link from the email. If it still does not work, ask the candidate to send a new request.</p>
        </StatePage>
      )
    }
    if (status === 410) {
      return (
        <StatePage title="This request is closed" icon={Clock}>
          <p>The link has expired, was cancelled, or the request was already answered. Nothing more is needed from you.</p>
        </StatePage>
      )
    }
    return (
      <Shell title="Reference request">
        <ErrorState error={reference.error} onRetry={() => void reference.refetch()} title="The request could not be loaded" />
      </Shell>
    )
  }
  return <RefereeForm token={token} data={reference.data} onDone={setDone} />
}

function RefereeForm({ token, data, onDone }: { token: string; data: PublicReference; onDone: (state: 'answered' | 'declined') => void }) {
  const baseId = useId()
  const formRef = useRef<HTMLFormElement>(null)
  const [answers, setAnswers] = useState<Record<string, RefereeAnswer>>({})
  const [comments, setComments] = useState<Record<string, string>>({})
  const [openComment, setOpenComment] = useState<Record<string, boolean>>({})
  const [refereeComment, setRefereeComment] = useState('')
  const [overallOpen, setOverallOpen] = useState(false)
  const [confirmIdentity, setConfirmIdentity] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [declineOpen, setDeclineOpen] = useState(false)

  const answered = data.claims.filter(c => answers[c.id]).length
  const first = data.candidateFirstName

  const submit = useMutation({
    mutationFn: () =>
      publicReferenceApi.submit(token, {
        answers: data.claims.map(claim => ({ claimId: claim.id, answer: answers[claim.id], comment: comments[claim.id]?.trim() || null })),
        refereeComment: refereeComment.trim() || null,
        confirmIdentity: true,
      }),
    onSuccess: () => onDone('answered'),
  })
  const decline = useMutation({ mutationFn: () => publicReferenceApi.submit(token, { decline: true }), onSuccess: () => onDone('declined') })

  function send(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    for (const claim of data.claims) if (!answers[claim.id]) next[claim.id] = 'Choose an answer.'
    if (!confirmIdentity) next.confirmIdentity = 'Please confirm who you are.'
    setErrors(next)
    if (Object.keys(next).length === 0) return submit.mutate()
    // Take the referee to the first thing missing.
    const firstMissing = data.claims.find(c => !answers[c.id])
    window.setTimeout(() => {
      const target = firstMissing
        ? document.querySelector<HTMLElement>(`[data-claim="${CSS.escape(firstMissing.id)}"] input`)
        : formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')
      target?.scrollIntoView({ block: 'center', behavior: 'smooth' })
      target?.focus({ preventScroll: true })
    }, 0)
  }

  const closedMessage = (error: unknown) => (error instanceof ApiError && error.status === 410 ? 'This request has expired or was already answered.' : error)

  return (
    <Shell
      title={`Can you confirm ${first}'s work?`}
      footer={
        <div className="rf-footer">
          <div className="rf-footer-inner">
            <div className="rf-progress" aria-live="polite">
              <span className="numeric">
                <strong>{answered}</strong> of {data.claims.length} answered
              </span>
              <span className="rf-progress-bar" aria-hidden="true">
                <span style={{ ['--v' as string]: data.claims.length ? answered / data.claims.length : 0 }} />
              </span>
            </div>
            <Button variant="link" onClick={() => setDeclineOpen(true)} disabled={submit.isPending}>
              I would rather not answer
            </Button>
            <Button type="submit" form={`${baseId}-form`} variant="primary" loading={submit.isPending}>
              Send answers
            </Button>
          </div>
        </div>
      }
    >
      <h1 className="rf-title" tabIndex={-1} data-page-heading="">
        Can you confirm {first}&rsquo;s work?
      </h1>
      <p className="rf-intro">
        {first} named you as {refereeRelationshipLabel(data.relationship)}
        {data.employerName ? ` at ${data.employerName}` : ''} and asked you to confirm the claims below. Answer only what you know; &ldquo;Can&rsquo;t confirm&rdquo;
        is a perfectly good answer.
      </p>
      <p className="rf-valid">
        <Icon icon={Clock} size={14} /> This link works until {formatDate(data.expiresAt, { dateStyle: 'medium' })}
      </p>
      <p className="rf-help">
        <strong>Confirm</strong> you know it is true · <strong>Partly</strong> true with limits · <strong>Can&rsquo;t confirm</strong> you don&rsquo;t know enough ·{' '}
        <strong>Disagree</strong> you believe it is inaccurate
      </p>

      <form id={`${baseId}-form`} ref={formRef} onSubmit={send} noValidate>
        <ol className="rf-claims">
          {data.claims.map((claim, index) => {
            const errorId = `${baseId}-c${index}-e`
            return (
              <li key={claim.id} className="rf-claim" data-claim={claim.id} data-answered={answers[claim.id] ? true : undefined}>
                <fieldset aria-describedby={errors[claim.id] ? errorId : undefined}>
                  <legend className="rf-claim-text">
                    <span className="rf-claim-type">{humanize(claim.claimType)}</span>
                    {claim.value}
                  </legend>
                  <SegmentedControl
                    label={`Your answer for ${claim.value}`}
                    options={ANSWERS}
                    value={answers[claim.id] ?? null}
                    onChange={v => {
                      setAnswers(current => ({ ...current, [claim.id]: v }))
                      setErrors(current => ({ ...current, [claim.id]: '' }))
                    }}
                    block
                  />
                  {errors[claim.id] && (
                    <p id={errorId} className="ui-field-error" role="alert">
                      <Icon icon={CircleAlert} size={14} />
                      <span>{errors[claim.id]}</span>
                    </p>
                  )}
                  {openComment[claim.id] ? (
                    <Field label="Comment" optional className="rf-comment">
                      <Textarea
                        rows={2}
                        maxLength={COMMENT_MAX}
                        autoFocus
                        value={comments[claim.id] ?? ''}
                        onChange={e => setComments(current => ({ ...current, [claim.id]: e.target.value }))}
                      />
                    </Field>
                  ) : (
                    <Button variant="link" size="sm" className="rf-add-comment" onClick={() => setOpenComment(c => ({ ...c, [claim.id]: true }))}>
                      Add a comment<span className="sr-only"> about {claim.value}</span>
                    </Button>
                  )}
                </fieldset>
              </li>
            )
          })}
        </ol>

        <div className="rf-end">
          {overallOpen ? (
            <Field label="Anything else you would like to add?" optional>
              <Textarea rows={3} maxLength={COMMENT_MAX} autoFocus value={refereeComment} onChange={e => setRefereeComment(e.target.value)} />
            </Field>
          ) : (
            <Button variant="link" size="sm" icon={MailPlus} onClick={() => setOverallOpen(true)}>
              Add an overall comment
            </Button>
          )}
          <div>
            <Checkbox
              checked={confirmIdentity}
              aria-invalid={errors.confirmIdentity ? true : undefined}
              aria-describedby={errors.confirmIdentity ? `${baseId}-id-e` : undefined}
              onChange={e => {
                setConfirmIdentity(e.target.checked)
                if (e.target.checked) setErrors(current => ({ ...current, confirmIdentity: '' }))
              }}
              label="I confirm that I am the person this request was sent to, and that my answers are honest and based on my own knowledge."
            />
            {errors.confirmIdentity && (
              <p id={`${baseId}-id-e`} className="ui-field-error" role="alert">
                <Icon icon={CircleAlert} size={14} />
                <span>{errors.confirmIdentity}</span>
              </p>
            )}
          </div>
          {submit.error && <FormError error={closedMessage(submit.error)} />}
        </div>
      </form>

      <ConfirmDialog
        open={declineOpen}
        title="Decline this request?"
        confirmLabel="Decline"
        busy={decline.isPending}
        error={decline.error ? closedMessage(decline.error) : null}
        onCancel={() => {
          setDeclineOpen(false)
          decline.reset()
        }}
        onConfirm={() => decline.mutate()}
      >
        <p>{first} will be told that you declined. You will not be asked again through this link.</p>
      </ConfirmDialog>
    </Shell>
  )
}
