import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { verificationApi } from '../../api/endpoints'
import { queryKeys } from '../../api/queryKeys'
import type { LedgerClaim, OwnershipCheckMethod, SourceCreateRequest, SourceKind, VerificationSource } from '../../api/types'
import { CONSENT_REQUIRED, ConsentCheckbox } from '../../components/Consent'
import { FormError } from '../../components/Feedback'
import { focusFirstInvalid } from '../../components/Form'
import { useNotify } from '../../components/Notices'
import {
  Alert, Button, CodeField, Drawer, Field, FileDropzone, Input, RadioCard, RadioGroup, SegmentedControl, Spinner, Stepper, Textarea, VerdictChip,
} from '../../design/components'
import { ArrowRight, CircleCheck, Github, Icon, KeyRound } from '../../design/icons'
import { VERDICTS } from '../../design/lib/labels'
import { DOCUMENT_ACCEPT, DOCUMENT_MAX_BYTES, validateDocumentFile } from '../../lib/files'
import { isHttpUrl, isHttpsUrl } from '../../lib/url'
import {
  SOURCE_KIND_LABEL, challengeSteps, isOAuthNotConfigured, isRunActive, noOwnershipCheckReason, ownershipMethodsFor, runErrorText,
} from '../../lib/verification'
import { KIND_HINT, KIND_ORDER, kindIcon, verdictCountsFor } from './model'
import { verificationErrorMessage } from './shared'
import type { SourcesState } from './useSources'

export type AddStep = 'choose' | 'connect' | 'prove' | 'check'
const STEPS: AddStep[] = ['choose', 'connect', 'prove', 'check']
const STEP_LABEL = ['Choose', 'Connect', 'Prove', 'Check']

/**
 * The per-kind consent sentence. This wording is versioned server-side (handbook 03 §7.5,
 * `app.verification.consent-version`): do not change it without a consent-version bump.
 */
function consentText(kind: SourceKind): string {
  switch (kind) {
    case 'GITHUB':
    case 'GITLAB':
      return `I agree that RecruitPro may read my public ${SOURCE_KIND_LABEL[kind]} profile and repositories to check the claims on my resume. I can revoke this at any time.`
    case 'WEBSITE':
      return 'I agree that RecruitPro may read the public pages of this website to check the claims on my resume. I can revoke this at any time.'
    case 'DOCUMENT':
      return 'I agree that RecruitPro may store and read this document to check the claims on my resume. Revoking deletes the file.'
    case 'OPEN_BADGE':
      return "I agree that RecruitPro may read this badge and check its issuer's signature. I can revoke this at any time."
  }
}

const METHOD_SHORT: Record<OwnershipCheckMethod, string> = {
  DNS_TXT: 'DNS record',
  WELL_KNOWN_FILE: 'Text file',
  PAGE_CODE: 'Code on page',
  PROFILE_BIO_CODE: 'Profile bio',
}

export interface AddSourceState {
  step: AddStep
  kind: SourceKind | null
  sourceId: number | null
}

interface AddSourceDrawerProps {
  open: boolean
  state: AddSourceState
  onChange: (next: Partial<AddSourceState>) => void
  onClose: () => void
  sources: SourcesState
  ledger: LedgerClaim[] | undefined
  /** Open the review drawer at the first result of this source. */
  onReview: (sourceLabel: string) => void
}

/**
 * Add a source as one calm stepper (FLOWS-CANDIDATE §7): Choose → Connect (consent right above the button) →
 * Prove ownership → Run the first check, with live progress and the verdict reveal. The step lives in the URL, so a
 * reload or the GitHub sign-in round trip resumes where the candidate was.
 */
export function AddSourceDrawer({ open, state, onChange, onClose, sources, ledger, onReview }: AddSourceDrawerProps) {
  const list = sources.query.data ?? []
  const source = state.sourceId !== null ? list.find(s => s.id === state.sourceId) ?? null : null
  const kind = state.kind ?? (source?.kind as SourceKind | undefined) ?? null
  const canProve = kind ? ownershipMethodsFor(kind).length > 0 : true
  const visibleSteps = canProve ? STEPS : STEPS.filter(s => s !== 'prove')
  const labels = visibleSteps.map(s => STEP_LABEL[STEPS.indexOf(s)])
  const current = Math.max(0, visibleSteps.indexOf(state.step === 'connect' && !kind ? 'choose' : state.step))

  const title =
    state.step === 'choose' ? 'Add a source' : state.step === 'check' && source ? `Check ${source.displayLabel || source.identifier}` : kind ? `Connect ${SOURCE_KIND_LABEL[kind]}` : 'Add a source'

  return (
    <Drawer
      open={open}
      onClose={onClose}
      title={title}
      description="Checkers read only what you connect, and only when you run a check. Nothing is shown to employers until you share it."
      headerExtra={<Stepper steps={labels} current={current} label="Add a source" className="v-stepper" />}
      className="v-add"
    >
      <div className="v-add-body" key={state.step}>
        {/* A "connect" link without a (valid) kind, e.g. a hand-edited URL, starts at "choose" instead of a blank drawer. */}
        {(state.step === 'choose' || (state.step === 'connect' && !kind)) && (
          <ChooseStep kind={kind} onNext={k => onChange({ step: 'connect', kind: k })} onCancel={onClose} />
        )}
        {state.step === 'connect' && kind && (
          <ConnectStep
            kind={kind}
            onBack={() => onChange({ step: 'choose' })}
            onConnected={created => {
              const needsProof = created.ownershipStatus !== 'PROVEN' && ownershipMethodsFor(created.kind).length > 0
              onChange({ step: needsProof ? 'prove' : 'check', sourceId: created.id, kind: created.kind as SourceKind })
            }}
          />
        )}
        {state.step === 'prove' && (
          source ? (
            <ProveStep source={source} onSkip={() => onChange({ step: 'check' })} onProven={() => onChange({ step: 'check' })} />
          ) : (
            <MissingSource loading={sources.query.isPending} onRestart={() => onChange({ step: 'choose', sourceId: null })} />
          )
        )}
        {state.step === 'check' && (
          source ? (
            <CheckStep source={source} sources={sources} ledger={ledger} onReview={() => onReview(source.displayLabel)} onDone={onClose} />
          ) : (
            <MissingSource loading={sources.query.isPending} onRestart={() => onChange({ step: 'choose', sourceId: null })} />
          )
        )}
      </div>
    </Drawer>
  )
}

function MissingSource({ loading, onRestart }: { loading: boolean; onRestart: () => void }) {
  if (loading) {
    return (
      <p className="v-wait" role="status">
        <Spinner size={14} /> Loading your sources…
      </p>
    )
  }
  return (
    <div className="v-step">
      <Alert tone="neutral" title="This source is no longer connected">
        It may have been revoked. You can connect it again.
      </Alert>
      <div className="v-step-actions">
        <Button variant="primary" onClick={onRestart}>
          Start again
        </Button>
      </div>
    </div>
  )
}

// ------------------------------------------------------------------------------------------------------- 1 Choose

function ChooseStep({ kind, onNext, onCancel }: { kind: SourceKind | null; onNext: (kind: SourceKind) => void; onCancel: () => void }) {
  const [value, setValue] = useState<SourceKind>(kind ?? 'GITHUB')
  return (
    <form
      className="v-step"
      onSubmit={e => {
        e.preventDefault()
        onNext(value)
      }}
    >
      <RadioGroup legend="Where can your work be seen?" value={value} onChange={v => setValue(v as SourceKind)} className="v-kinds">
        {KIND_ORDER.map(k => (
          <RadioCard key={k} value={k} label={SOURCE_KIND_LABEL[k]} description={KIND_HINT[k]} icon={kindIcon(k)} />
        ))}
      </RadioGroup>
      <div className="v-step-actions">
        <Button variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" variant="primary" trailingIcon={ArrowRight}>
          Continue
        </Button>
      </div>
    </form>
  )
}

// ------------------------------------------------------------------------------------------------------- 2 Connect

function ConnectStep({ kind, onBack, onConnected }: { kind: SourceKind; onBack: () => void; onConnected: (source: VerificationSource) => void }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const formRef = useRef<HTMLFormElement>(null)
  const [consent, setConsent] = useState(false)
  const [consentError, setConsentError] = useState<string | null>(null)
  const [identifier, setIdentifier] = useState('')
  const [credential, setCredential] = useState('')
  const [badgeMode, setBadgeMode] = useState<'url' | 'paste'>('url')
  const [file, setFile] = useState<File | null>(null)
  const [fieldError, setFieldError] = useState<string | null>(null)
  /** GitHub sign-in is not configured on this server: only the username route is offered. */
  const [oauthUnavailable, setOauthUnavailable] = useState(false)
  const [byUsername, setByUsername] = useState(false)

  const create = useMutation({
    mutationFn: async (): Promise<VerificationSource> => {
      if (kind === 'DOCUMENT') return verificationApi.uploadDocument(file as File)
      const body: SourceCreateRequest = { kind, consent: true }
      if (kind === 'OPEN_BADGE' && badgeMode === 'paste') {
        const text = credential.trim()
        let parsed: string | Record<string, unknown> = text
        if (text.startsWith('{')) {
          try {
            parsed = JSON.parse(text) as Record<string, unknown>
          } catch {
            parsed = text
          }
        }
        body.credential = parsed
      } else body.identifier = identifier.trim()
      return verificationApi.createSource(body)
    },
    onSuccess: created => {
      queryClient.setQueryData<VerificationSource[]>(queryKeys.verificationSources, current =>
        current ? [created, ...current.filter(item => item.id !== created.id)] : [created],
      )
      void queryClient.invalidateQueries({ queryKey: queryKeys.verificationSources })
      notify('success', `${created.displayLabel || SOURCE_KIND_LABEL[kind]} connected`)
      onConnected(created)
    },
  })

  const github = useMutation({
    mutationFn: async () => {
      const started = await verificationApi.startGitHubOAuth()
      // Only ever leave for an https authorisation page (never a javascript: or other scheme from a bad answer).
      if (!isHttpsUrl(started.authorizeUrl)) throw new Error('The GitHub sign-in address from the server was not valid. Please try again.')
      return started
    },
    onSuccess: ({ authorizeUrl }) => window.location.assign(authorizeUrl),
    onError: error => {
      if (error instanceof ApiError && isOAuthNotConfigured(error)) {
        setOauthUnavailable(true)
        setByUsername(true)
      }
    },
  })

  const showOAuth = kind === 'GITHUB' && !oauthUnavailable && !byUsername

  function requireConsent(): boolean {
    if (!consent) {
      setConsentError(CONSENT_REQUIRED)
      return false
    }
    setConsentError(null)
    return true
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (showOAuth) {
      if (requireConsent()) github.mutate()
      else focusFirstInvalid(formRef.current)
      return
    }
    let problem: string | null = null
    if (kind === 'DOCUMENT') problem = validateDocumentFile(file)
    else if (kind === 'GITHUB' || kind === 'GITLAB') {
      if (!identifier.trim()) problem = `Enter your ${SOURCE_KIND_LABEL[kind]} username.`
    } else if (kind === 'WEBSITE') {
      if (!isHttpUrl(identifier.trim())) problem = 'Enter the full address, starting with https://.'
    } else if (kind === 'OPEN_BADGE') {
      if (badgeMode === 'url' && !isHttpUrl(identifier.trim())) problem = 'Enter the badge address, starting with https://.'
      if (badgeMode === 'paste' && !credential.trim()) problem = 'Paste the badge credential (a JSON object or a signed token).'
    }
    setFieldError(problem)
    const consented = requireConsent()
    if (!problem && consented) create.mutate()
    else focusFirstInvalid(formRef.current)
  }

  const createError = create.error ? verificationErrorMessage(create.error, 'connect') : null
  const busy = create.isPending || github.isPending

  return (
    <form ref={formRef} className="v-step" onSubmit={submit} noValidate>
      <div className="v-kind-line">
        <Icon icon={kindIcon(kind)} size={20} />
        <div>
          <p className="t-strong">{SOURCE_KIND_LABEL[kind]}</p>
          <p className="v-muted">{KIND_HINT[kind]}</p>
        </div>
      </div>

      {kind === 'GITHUB' && oauthUnavailable && (
        <Alert tone="info">
          Signing in with GitHub is not available on this server. Connect your username instead and prove it is yours with a code in your profile bio.
        </Alert>
      )}

      {showOAuth && (
        <p className="v-muted">
          You will sign in on GitHub and come straight back here. Signing in proves the account is yours, and we only ask for read access to public
          information.
        </p>
      )}

      {((kind === 'GITHUB' && !showOAuth) || kind === 'GITLAB') && (
        <Field label={`${SOURCE_KIND_LABEL[kind]} username`} error={fieldError} hint="Your username or profile address, for example octocat.">
          <Input autoComplete="username" spellCheck={false} maxLength={200} value={identifier} autoFocus onChange={e => setIdentifier(e.target.value)} />
        </Field>
      )}

      {kind === 'WEBSITE' && (
        <Field label="Website address" error={fieldError} hint="The page that shows your work, for example https://example.com/portfolio.">
          <Input type="url" inputMode="url" placeholder="https://" maxLength={500} value={identifier} autoFocus onChange={e => setIdentifier(e.target.value)} />
        </Field>
      )}

      {kind === 'DOCUMENT' && (
        <div>
          <FileDropzone
            prompt="Drop a PDF, DOCX or PPTX here, or"
            hint="Up to 15 MB. Ownership of a document cannot be proven, so its results are at most Partly verified."
            accept={DOCUMENT_ACCEPT}
            maxSize={DOCUMENT_MAX_BYTES}
            onFiles={files => {
              setFile(files[0])
              setFieldError(validateDocumentFile(files[0]))
            }}
            files={file ? [{ id: 'doc', name: file.name, size: file.size }] : []}
            onRemove={() => setFile(null)}
            error={fieldError}
          />
        </div>
      )}

      {kind === 'OPEN_BADGE' && (
        <>
          <SegmentedControl
            label="How do you have the badge?"
            value={badgeMode}
            onChange={v => {
              setBadgeMode(v)
              setFieldError(null)
            }}
            options={[
              { value: 'url', label: 'A link to the badge' },
              { value: 'paste', label: 'The credential itself' },
            ]}
            block
          />
          {badgeMode === 'url' ? (
            <Field label="Badge address" error={fieldError} hint="The address of the hosted badge assertion.">
              <Input type="url" inputMode="url" placeholder="https://" maxLength={500} value={identifier} onChange={e => setIdentifier(e.target.value)} />
            </Field>
          ) : (
            <Field label="Badge credential" error={fieldError} hint="The JSON object or signed token your issuer gave you.">
              <Textarea rows={5} className="mono" value={credential} onChange={e => setCredential(e.target.value)} />
            </Field>
          )}
        </>
      )}

      <div className="v-consent-block">
        <ConsentCheckbox
          checked={consent}
          error={consentError}
          onChange={checked => {
            setConsent(checked)
            if (checked) setConsentError(null)
          }}
        >
          {consentText(kind)}
        </ConsentCheckbox>
        {github.error && !oauthUnavailable && <FormError error={verificationErrorMessage(github.error, 'connect')} />}
        {createError && <FormError error={createError} />}
        <div className="v-step-actions">
          <Button variant="ghost" onClick={onBack} disabled={busy}>
            Back
          </Button>
          {kind === 'GITHUB' && !oauthUnavailable && (
            <Button variant="link" onClick={() => setByUsername(v => !v)} disabled={busy}>
              {byUsername ? 'Sign in with GitHub instead' : 'Use my username instead'}
            </Button>
          )}
          <Button type="submit" variant="primary" icon={showOAuth ? Github : undefined} loading={busy}>
            {showOAuth ? 'Continue with GitHub' : kind === 'DOCUMENT' ? 'Upload and connect' : 'Connect'}
          </Button>
        </div>
      </div>
    </form>
  )
}

// ------------------------------------------------------------------------------------------------------- 3 Prove

function ProveStep({ source, onSkip, onProven }: { source: VerificationSource; onSkip: () => void; onProven: () => void }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const steps = challengeSteps(source.kind, source.identifier, source.challengeCode)
  const [method, setMethod] = useState<OwnershipCheckMethod>(steps[0]?.method ?? 'PROFILE_BIO_CODE')
  const [outcome, setOutcome] = useState<VerificationSource | null>(null)
  const selected = steps.find(s => s.method === method) ?? steps[0]
  const proven = (outcome ?? source).ownershipStatus === 'PROVEN'

  const check = useMutation({
    mutationFn: (chosen: OwnershipCheckMethod) => verificationApi.checkOwnership(source.id, chosen),
    onMutate: () => setOutcome(null),
    onSuccess: updated => {
      setOutcome(updated)
      queryClient.setQueryData<VerificationSource[]>(queryKeys.verificationSources, current => current?.map(item => (item.id === updated.id ? updated : item)))
      if (updated.ownershipStatus === 'PROVEN') notify('success', 'Ownership proven', { body: 'Results from this source can now be Verified.' })
    },
  })

  if (proven) {
    return (
      <div className="v-step">
        <div className="v-success">
          <span className="v-success-icon">
            <Icon icon={KeyRound} size={20} />
          </span>
          <div>
            <p className="t-h3">Ownership proven</p>
            <p className="v-muted">Results from {source.displayLabel || source.identifier} can now be Verified. You can remove the code again.</p>
          </div>
        </div>
        <div className="v-step-actions">
          <Button variant="primary" trailingIcon={ArrowRight} autoFocus onClick={onProven}>
            Continue to the first check
          </Button>
        </div>
      </div>
    )
  }

  if (!selected) {
    return (
      <div className="v-step">
        <Alert tone="neutral">{noOwnershipCheckReason(source.kind) ?? 'This kind of source has no ownership check.'}</Alert>
        <div className="v-step-actions">
          <Button variant="primary" onClick={onSkip}>
            Continue
          </Button>
        </div>
      </div>
    )
  }

  return (
    <form
      className="v-step"
      onSubmit={e => {
        e.preventDefault()
        check.mutate(selected.method)
      }}
    >
      <p className="v-muted">
        Prove that <strong className="v-strong">{source.displayLabel || source.identifier}</strong> is yours by publishing a short code. Proven sources
        can give a full "Verified"; unproven ones at most "Partly verified".
      </p>
      {steps.length > 1 && (
        <SegmentedControl
          label="How to prove it"
          value={method}
          onChange={v => {
            setMethod(v)
            setOutcome(null)
            check.reset()
          }}
          options={steps.map(s => ({ value: s.method, label: METHOD_SHORT[s.method] }))}
          block
        />
      )}
      <div className="v-method" key={selected.method}>
        <p className="t-h3">{selected.title}</p>
        <ol className="v-method-steps">
          {selected.steps.map(line => (
            <li key={line}>{line}</li>
          ))}
        </ol>
        <div className="v-codes">
          {selected.copy.map(item => (
            <CodeField key={item.label} label={item.label} value={item.value} />
          ))}
        </div>
      </div>

      {outcome && outcome.ownershipStatus !== 'PROVEN' && (
        <Alert tone="warning" live>
          {outcome.ownershipStatus === 'PENDING'
            ? 'The check is still in progress. Try again in a minute.'
            : `We could not find the code yet${outcome.ownershipDetail ? `: ${outcome.ownershipDetail}` : '.'} Changes can take a few minutes to appear.`}
        </Alert>
      )}
      {check.error && <FormError error={verificationErrorMessage(check.error, 'ownership')} />}

      <div className="v-step-actions">
        <Button variant="ghost" onClick={onSkip} disabled={check.isPending}>
          Skip for now
        </Button>
        <Button type="submit" variant="primary" loading={check.isPending}>
          Check now
        </Button>
      </div>
    </form>
  )
}

// ------------------------------------------------------------------------------------------------------- 4 Check

function CheckStep({
  source, sources, ledger, onReview, onDone,
}: { source: VerificationSource; sources: SourcesState; ledger: LedgerClaim[] | undefined; onReview: () => void; onDone: () => void }) {
  const running = isRunActive(source.lastRun?.status)
  const finished = source.lastRun?.status === 'COMPLETED'
  const failed = source.lastRun?.status === 'FAILED'
  const counts = verdictCountsFor(ledger, source.displayLabel)
  const total = Object.values(counts).reduce((a, b) => a + (b ?? 0), 0)
  const runError = sources.startRun.error && sources.startRun.variables === source.id ? verificationErrorMessage(sources.startRun.error, 'run') : null
  const proven = source.ownershipStatus === 'PROVEN'

  // idle → starting (button pressed) → running (server says so) → done (it stopped while this step was open).
  const [phase, setPhase] = useState<'idle' | 'starting' | 'running' | 'done'>(running ? 'running' : 'idle')
  useEffect(() => {
    if (running && phase !== 'running') setPhase('running')
    else if (!running && phase === 'running') setPhase('done')
  }, [running, phase])

  if (phase === 'starting' || phase === 'running') {
    return (
      <div className="v-step" aria-live="polite">
        <div className="v-progress-card">
          <div className="v-progress" role="progressbar" aria-label={`Checking ${source.displayLabel}`} aria-valuetext="In progress">
            <span />
          </div>
          <p className="t-strong">Reading {source.displayLabel || source.identifier}</p>
          <p className="v-muted">
            This usually takes under a minute. You can close this panel; the page keeps checking and tells you when results are ready.
          </p>
          <div className="v-reveal-row">
            <VerdictChip state="checking" />
          </div>
        </div>
        {sources.stalled && (
          <Alert tone="info" title="Still checking" action={<Button size="sm" onClick={sources.checkAgain}>Check again</Button>}>
            This is taking longer than usual. We stopped refreshing automatically.
          </Alert>
        )}
        <div className="v-step-actions">
          <Button variant="secondary" onClick={onDone}>
            Close and keep checking
          </Button>
        </div>
      </div>
    )
  }

  if (finished && phase === 'done') {
    return (
      <div className="v-step" aria-live="polite">
        <div className="v-success">
          <span className="v-success-icon">
            <Icon icon={CircleCheck} size={20} />
          </span>
          <div>
            <p className="t-h3">{total > 0 ? `${total} ${total === 1 ? 'result is' : 'results are'} ready` : 'The check finished'}</p>
            <p className="v-muted">Only you can see them until you choose to share.</p>
          </div>
        </div>
        {total > 0 && (
          <ul className="v-reveal" aria-label="Results by verdict">
            {VERDICTS.filter(v => counts[v]).map((v, i) => (
              <li key={v} style={{ ['--i' as string]: i }}>
                <VerdictChip verdict={v} />
                <span className="numeric v-reveal-count">× {counts[v]}</span>
              </li>
            ))}
          </ul>
        )}
        <div className="v-step-actions">
          <Button variant="ghost" onClick={onDone}>
            Later
          </Button>
          {total > 0 && (
            <Button variant="primary" trailingIcon={ArrowRight} autoFocus onClick={onReview}>
              Review results
            </Button>
          )}
        </div>
      </div>
    )
  }

  return (
    <div className="v-step">
      <div className="v-ready">
        <Icon icon={kindIcon(source.kind)} size={20} />
        <div>
          <p className="t-strong">{source.displayLabel || source.identifier}</p>
          <p className="v-muted">
            {proven ? 'Ownership proven.' : 'Ownership not proven yet, so results are at most "Partly verified".'}{' '}
            {source.kind === 'GITHUB' || source.kind === 'GITLAB' ? 'Code hosts are checked against your skills.' : 'Checked against every claim you have.'}
          </p>
        </div>
      </div>
      {failed && <Alert tone="warning">{runErrorText(source.lastRun?.errorCode)}</Alert>}
      {finished && total > 0 && (
        <p className="v-muted">
          This source was last checked with {total} {total === 1 ? 'result' : 'results'}. Running again replaces them with fresh ones.
        </p>
      )}
      {runError && <FormError error={runError} />}
      <div className="v-step-actions">
        <Button variant="ghost" onClick={onDone}>
          Not now
        </Button>
        {finished && total > 0 && (
          <Button variant="secondary" onClick={onReview}>
            Review results
          </Button>
        )}
        <Button
          variant="primary"
          autoFocus
          onClick={() => {
            setPhase('starting')
            sources.startRun.mutate(source.id, {
              onError: () => setPhase('idle'),
              // A run can be refused or finish at once (status COMPLETED/FAILED in the answer): never spin forever.
              onSuccess: run => {
                if (!isRunActive(run.status)) setPhase('done')
              },
            })
          }}
        >
          {finished ? 'Run the check again' : 'Run the first check'}
        </Button>
      </div>
    </div>
  )
}
