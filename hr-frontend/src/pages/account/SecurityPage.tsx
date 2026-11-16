import { lazy, Suspense, useEffect, useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { accountApi } from '../../api/account'
import { errorMessage, isApiError } from '../../api/client'
import { qk } from '../../api/queryKeys'
import type { MfaEnrollment, MfaStatus } from '../../api/types'
import { useCurrentUser } from '../../auth/AuthContext'
import { ActionDialog } from '../../components/ActionDialog'
import { OtpInput } from '../../components/OtpInput'
import { InlineError, QueryError } from '../../components/QueryError'
import { RecoveryCodes } from '../../components/RecoveryCodes'
import {
  Alert, Button, CodeField, Field, Input, LoadingRegion, Meter, PageHeader, Panel, Skeleton, SkeletonText, Stepper, useToast,
} from '../../design/components'
import { Icon, KeyRound, RefreshCw, ShieldCheck } from '../../design/icons'
import { formatDate, relativeTime } from '../../design/lib/format'
import './account.css'

// The QR encoder only loads on this page, and only once enrolment starts.
const QrCode = lazy(() => import('../../components/QrCode'))

const RECOVERY_TOTAL = 10

/** /account/security: two-step verification (FLOWS-HR-B §6): Scan → Confirm → Save codes, then status and controls. */
export function SecurityPage() {
  const user = useCurrentUser()
  const status = useQuery({ queryKey: qk.mfaStatus, queryFn: ({ signal }) => accountApi.mfaStatus(signal) })
  const [freshCodes, setFreshCodes] = useState<{ codes: string[]; reason: 'enrol' | 'regen' } | null>(null)

  return (
    <div className="acc" data-width="form">
      <PageHeader title="Two-step verification" meta={[<span key="e">{user.email}</span>]} description="After your password, you also enter a 6-digit code from an authenticator app on your phone." />
      {freshCodes ? (
        <section className="acc-section" aria-labelledby="codes-h">
          {freshCodes.reason === 'enrol' && <Stepper steps={['Scan', 'Confirm', 'Save codes']} current={2} label="Set-up progress" />}
          <h2 id="codes-h" className="t-h2">{freshCodes.reason === 'enrol' ? 'Two-step verification is on. Save your recovery codes.' : 'Your new recovery codes'}</h2>
          <p className="acc-lead">Each code signs you in once if you lose your phone. They are shown only now; earlier codes no longer work.</p>
          <RecoveryCodes codes={freshCodes.codes} email={user.email} onDone={() => setFreshCodes(null)} />
        </section>
      ) : status.isPending ? (
        <LoadingRegion label="Loading security settings"><div className="acc-section"><Skeleton width={240} height={18} /><SkeletonText lines={3} /></div></LoadingRegion>
      ) : status.isError ? (
        <QueryError error={status.error} title="We couldn’t load your security settings" onRetry={() => void status.refetch()} retrying={status.isFetching} />
      ) : !status.data.available ? (
        <Alert tone="neutral" title="Not available on this server yet">Two-step verification needs an encryption key that is not configured. Ask your administrator.</Alert>
      ) : status.data.enabled ? (
        <EnabledPanel status={status.data} onCodes={codes => setFreshCodes({ codes, reason: 'regen' })} />
      ) : (
        <EnrolPanel status={status.data} onCodes={codes => setFreshCodes({ codes, reason: 'enrol' })} />
      )}
    </div>
  )
}

/** Groups a base32 secret in fours for manual entry: "JBSW Y3DP …". */
const grouped = (secret: string) => secret.replace(/\s+/g, '').match(/.{1,4}/g)?.join(' ') ?? secret

function EnrolPanel({ status, onCodes }: { status: MfaStatus; onCodes: (codes: string[]) => void }) {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [enrollment, setEnrollment] = useState<MfaEnrollment | null>(null)
  const [code, setCode] = useState('')
  const [codeError, setCodeError] = useState<string | null>(null)
  const [shake, setShake] = useState(0)
  const otpRef = useRef<HTMLInputElement>(null)

  const enrol = useMutation({ mutationFn: accountApi.mfaEnroll, onSuccess: setEnrollment })
  const confirm = useMutation({
    mutationFn: (value: string) => accountApi.mfaConfirm(value),
    onSuccess: result => {
      setEnrollment(null)
      setCode('')
      toast({ tone: 'success', title: 'Two-step verification is on' })
      void queryClient.invalidateQueries({ queryKey: qk.mfaStatus })
      onCodes(result.recoveryCodes)
    },
    onError: cause => {
      if (isApiError(cause, 400)) {
        setCodeError('That code did not match. Check the time on your phone and try the newest code.')
        setCode('')
        setShake(n => n + 1)
        window.setTimeout(() => otpRef.current?.focus(), 0)
      }
    },
  })

  useEffect(() => { if (enrollment) window.setTimeout(() => otpRef.current?.focus({ preventScroll: true }), 50) }, [enrollment])

  function submit(event?: FormEvent, value = code) {
    event?.preventDefault()
    if (!/^\d{6}$/.test(value)) {
      setCodeError('Enter the 6-digit code from your authenticator app.')
      return
    }
    setCodeError(null)
    confirm.mutate(value)
  }

  return (
    <section className="acc-section" aria-labelledby="mfa-h">
      {status.requiredByOrganization && <Alert tone="warning" title="Required by your organisation">Set it up now, or you will be asked to at your next sign-in.</Alert>}
      {!enrollment ? (
        <div className="acc-off">
          <div className="acc-state" data-state="off"><Icon icon={ShieldCheck} size={20} /><div><h2 id="mfa-h" className="t-h3">Two-step verification is off</h2><p className="acc-lead">You need an authenticator app, for example Google Authenticator, Microsoft Authenticator or 1Password. Set-up takes about a minute.</p></div></div>
          <InlineError error={enrol.error} />
          <div><Button variant="primary" icon={ShieldCheck} loading={enrol.isPending} onClick={() => enrol.mutate()}>Set up two-step verification</Button></div>
        </div>
      ) : (
        <form className="acc-enrol" onSubmit={submit} noValidate>
          <Stepper steps={['Scan', 'Confirm', 'Save codes']} current={code.length > 0 || confirm.isPending ? 1 : 0} label="Set-up progress" />
          <h2 id="mfa-h" className="t-h2">Scan this code with your authenticator app</h2>
          <div className="acc-qr-row">
            <Suspense fallback={<Skeleton width={194} height={194} radius={6} />}>
              <QrCode value={enrollment.otpauthUri} label="QR code for your authenticator app" />
            </Suspense>
            <div className="acc-qr-side">
              <p className="acc-lead">Open the app, add an account, and point the camera at the code. It is drawn in your browser; nothing is sent anywhere.</p>
              <p className="acc-fine">Can’t scan? Enter this setup key instead (time-based, 6 digits):</p>
              <CodeField label="Setup key" value={grouped(enrollment.secret)} />
            </div>
          </div>
          <div className="acc-confirm">
            <Field label="Enter the 6-digit code the app shows" error={codeError}>
              <OtpInput ref={otpRef} value={code} errorKey={shake} aria-invalid={codeError ? true : undefined} onChange={v => { setCode(v); if (codeError) setCodeError(null) }} onComplete={v => submit(undefined, v)} disabled={confirm.isPending} />
            </Field>
            {confirm.error && !isApiError(confirm.error, 400) && <InlineError error={confirm.error} />}
            <div className="acc-actions">
              <Button variant="ghost" onClick={() => { setEnrollment(null); setCode(''); setCodeError(null) }} disabled={confirm.isPending}>Cancel</Button>
              <Button type="submit" variant="primary" loading={confirm.isPending}>Turn on</Button>
            </div>
          </div>
        </form>
      )}
    </section>
  )
}

function EnabledPanel({ status, onCodes }: { status: MfaStatus; onCodes: (codes: string[]) => void }) {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [regenOpen, setRegenOpen] = useState(false)
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const low = status.recoveryCodesRemaining <= 2

  const regenerate = useMutation({
    mutationFn: accountApi.regenerateRecoveryCodes,
    onSuccess: result => { setRegenOpen(false); void queryClient.invalidateQueries({ queryKey: qk.mfaStatus }); onCodes(result.recoveryCodes) },
  })
  const disable = useMutation({
    mutationFn: () => accountApi.mfaDisable({ password, code: code.trim() }),
    onSuccess: () => { setPassword(''); setCode(''); toast({ tone: 'success', title: 'Two-step verification is off' }); void queryClient.invalidateQueries({ queryKey: qk.mfaStatus }) },
    onError: cause => setProblem(isApiError(cause, 400) ? errorMessage(cause, 'The password or code is not right.') : null),
  })

  function submitDisable(event: FormEvent) {
    event.preventDefault()
    if (!password || !code.trim()) return setProblem('Enter your password and a current code (or a recovery code).')
    setProblem(null)
    disable.mutate()
  }

  return (
    <>
      <section className="acc-section" aria-labelledby="mfa-on-h">
        <div className="acc-state" data-state="on">
          <Icon icon={ShieldCheck} size={20} />
          <div>
            <h2 id="mfa-on-h" className="t-h3">Two-step verification is on</h2>
            <p className="acc-lead">
              Turned on {status.enabledAt ? <time dateTime={status.enabledAt} title={formatDate(status.enabledAt)}>{relativeTime(status.enabledAt)}</time> : 'earlier'}
              {status.requiredByOrganization ? ' · required by your organisation' : ''}.
            </p>
          </div>
        </div>
        <div className="acc-codes">
          <Meter label="Recovery codes left" value={status.recoveryCodesRemaining} max={RECOVERY_TOTAL} tone={low ? 'warning' : 'accent'} valueText={`${status.recoveryCodesRemaining} of ${RECOVERY_TOTAL}`} />
          {low && <Alert tone="warning" title="Running low on recovery codes">Create new ones and store them somewhere safe. The old ones stop working.</Alert>}
          <div><Button icon={RefreshCw} variant={low ? 'primary' : 'secondary'} onClick={() => setRegenOpen(true)}>Create new recovery codes</Button></div>
        </div>
      </section>

      <section className="acc-section" aria-labelledby="mfa-off-h">
        {status.requiredByOrganization ? (
          <>
            <h2 id="mfa-off-h" className="t-h3">Turn off</h2>
            <p className="acc-lead">Your organisation requires two-step verification, so it cannot be turned off here.</p>
          </>
        ) : (
          <Panel tone="danger" title={<span id="mfa-off-h">Turn off two-step verification</span>}>
            <form onSubmit={submitDisable} noValidate className="acc-form acc-danger">
              <p className="acc-lead">Your account is then protected by your password alone.</p>
              <Field label="Password"><Input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} /></Field>
              <Field label="Code from your app, or a recovery code"><Input autoComplete="one-time-code" maxLength={20} value={code} onChange={e => setCode(e.target.value)} /></Field>
              {problem ? <InlineError error={problem} /> : <InlineError error={disable.error} />}
              <div><Button type="submit" variant="danger-ghost" icon={KeyRound} loading={disable.isPending}>Turn off</Button></div>
            </form>
          </Panel>
        )}
      </section>

      <ActionDialog
        open={regenOpen}
        title="Create new recovery codes?"
        description="Your current recovery codes stop working immediately. You will see the new ones once."
        confirmLabel="Create new codes"
        focusCancel
        busy={regenerate.isPending}
        error={regenerate.error}
        onCancel={() => { setRegenOpen(false); regenerate.reset() }}
        onConfirm={() => regenerate.mutate()}
      />
    </>
  )
}
