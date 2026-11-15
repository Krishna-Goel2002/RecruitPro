import { FormEvent, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { accountApi } from '../../api/account'
import { errorMessage, isApiError } from '../../api/client'
import { qk } from '../../api/queryKeys'
import type { MfaEnrollment, MfaStatus } from '../../api/types'
import { useCurrentUser } from '../../auth/AuthContext'
import { ConfirmDialog } from '../../components/Dialog'
import { Field } from '../../components/Field'
import { PageHeader } from '../../components/Layout'
import { useNotices } from '../../components/Notices'
import { ErrorPanel, FormError, LoadingPanel } from '../../components/States'
import { formatDateTime } from '../../lib/format'
import { usePageTitle } from '../../lib/hooks'
import { MfaSecret, RecoveryCodesList } from '../auth/LoginPage'

const CODE_PATTERN = /^\d{6}$/

/** /account/security — staff two-step verification (WP-PLAT 1.1): status, enrol, recovery codes, disable. */
export function SecurityPage() {
  usePageTitle('Account security')
  const user = useCurrentUser()
  const status = useQuery({ queryKey: qk.mfaStatus, queryFn: ({ signal }) => accountApi.mfaStatus(signal) })
  /** Recovery codes are shown once, right after they are created. */
  const [freshCodes, setFreshCodes] = useState<string[] | null>(null)

  return (
    <>
      <PageHeader eyebrow="ACCOUNT" title="Account security" subtitle={`Two-step verification for ${user.email}. After your password, you also enter a code from an authenticator app.`} />
      {freshCodes && (
        <section className="detail-card form-card" aria-labelledby="codes-heading">
          <h2 id="codes-heading">Save your recovery codes</h2>
          <p role="status">Each code lets you sign in once if you lose your phone. They will not be shown again; earlier codes no longer work.</p>
          <RecoveryCodesList codes={freshCodes} />
          <button type="button" className="primary-button workspace-primary" onClick={() => setFreshCodes(null)}>I have saved them</button>
        </section>
      )}
      {status.isPending ? <LoadingPanel label="Loading…" /> : status.isError ? (
        <ErrorPanel error={status.error} onRetry={() => void status.refetch()} />
      ) : !status.data.available ? (
        <section className="detail-card form-card"><p>Two-step verification is not available on this server yet (no encryption key is configured). Ask your administrator.</p></section>
      ) : status.data.enabled ? (
        <EnabledPanel status={status.data} onCodes={setFreshCodes} />
      ) : (
        <EnrolPanel status={status.data} onCodes={setFreshCodes} />
      )}
    </>
  )
}

function EnrolPanel({ status, onCodes }: { status: MfaStatus; onCodes: (codes: string[]) => void }) {
  const queryClient = useQueryClient()
  const notices = useNotices()
  const [enrollment, setEnrollment] = useState<MfaEnrollment | null>(null)
  const [code, setCode] = useState('')
  const [codeError, setCodeError] = useState<string | null>(null)

  const enrol = useMutation({ mutationFn: accountApi.mfaEnroll, onSuccess: setEnrollment })
  const confirm = useMutation({
    mutationFn: () => accountApi.mfaConfirm(code.trim()),
    onSuccess: result => {
      setEnrollment(null)
      setCode('')
      onCodes(result.recoveryCodes)
      notices.success('Two-step verification is on.')
      void queryClient.invalidateQueries({ queryKey: qk.mfaStatus })
    },
    onError: cause => {
      if (isApiError(cause, 400)) setCodeError('That code did not match. Check the time on your phone and try the newest code.')
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!CODE_PATTERN.test(code.trim())) return setCodeError('Enter the 6-digit code from your authenticator app.')
    setCodeError(null)
    confirm.mutate()
  }

  return (
    <section className="detail-card form-card settings-form" aria-labelledby="mfa-heading">
      <h2 id="mfa-heading">Two-step verification is off</h2>
      {status.requiredByOrganization && <p className="mode-banner">Your organisation requires two-step verification. You will be asked to set it up at your next sign-in.</p>}
      {!enrollment ? (
        <>
          <p>You need an authenticator app on your phone, for example Google Authenticator, Microsoft Authenticator or 1Password.</p>
          <FormError error={enrol.error} />
          <div><button type="button" className="primary-button workspace-primary" onClick={() => enrol.mutate()} disabled={enrol.isPending}>{enrol.isPending ? 'Preparing…' : 'Set up two-step verification'}</button></div>
        </>
      ) : (
        <form onSubmit={submit} noValidate className="settings-form">
          <MfaSecret enrollment={enrollment} />
          <Field label="6-digit code from the app" required error={codeError}>
            <input type="text" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]*" maxLength={6} value={code} onChange={event => setCode(event.target.value.replace(/\D/g, ''))} />
          </Field>
          {confirm.error && !isApiError(confirm.error, 400) && <FormError error={confirm.error} />}
          <div className="item-actions">
            <button type="submit" className="primary-button workspace-primary" disabled={confirm.isPending}>{confirm.isPending ? 'Checking…' : 'Turn on'}</button>
            <button type="button" className="outline-button" onClick={() => { setEnrollment(null); setCode(''); setCodeError(null) }} disabled={confirm.isPending}>Cancel</button>
          </div>
        </form>
      )}
    </section>
  )
}

function EnabledPanel({ status, onCodes }: { status: MfaStatus; onCodes: (codes: string[]) => void }) {
  const queryClient = useQueryClient()
  const notices = useNotices()
  const [regenOpen, setRegenOpen] = useState(false)
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [problem, setProblem] = useState<string | null>(null)

  const regenerate = useMutation({
    mutationFn: accountApi.regenerateRecoveryCodes,
    onSuccess: result => {
      setRegenOpen(false)
      onCodes(result.recoveryCodes)
      void queryClient.invalidateQueries({ queryKey: qk.mfaStatus })
    },
  })

  const disable = useMutation({
    mutationFn: () => accountApi.mfaDisable({ password, code: code.trim() }),
    onSuccess: () => {
      setPassword('')
      setCode('')
      notices.success('Two-step verification is off.')
      void queryClient.invalidateQueries({ queryKey: qk.mfaStatus })
    },
    onError: cause => setProblem(isApiError(cause, 400) ? `${errorMessage(cause)}` : null),
  })

  function submitDisable(event: FormEvent) {
    event.preventDefault()
    if (!password || !code.trim()) return setProblem('Enter your password and a current code (or a recovery code).')
    setProblem(null)
    disable.mutate()
  }

  return (
    <>
      <section className="detail-card form-card" aria-labelledby="mfa-on-heading">
        <h2 id="mfa-on-heading">Two-step verification is on</h2>
        <dl className="metric-grid">
          <div><dt>Turned on</dt><dd className="small-value">{formatDateTime(status.enabledAt)}</dd></div>
          <div><dt>Recovery codes left</dt><dd>{status.recoveryCodesRemaining}</dd></div>
        </dl>
        {status.recoveryCodesRemaining <= 2 && <p className="mode-banner">You are running low on recovery codes. Create new ones and store them safely.</p>}
        <div className="item-actions">
          <button type="button" className="outline-button" onClick={() => setRegenOpen(true)}>Create new recovery codes</button>
        </div>
      </section>

      <section className="detail-card form-card settings-form" aria-labelledby="mfa-off-heading">
        <h2 id="mfa-off-heading">Turn off two-step verification</h2>
        {status.requiredByOrganization ? (
          <p>Your organisation requires two-step verification, so it cannot be turned off.</p>
        ) : (
          <form onSubmit={submitDisable} noValidate className="settings-form">
            <Field label="Password" required>
              <input type="password" autoComplete="current-password" maxLength={72} value={password} onChange={event => setPassword(event.target.value)} />
            </Field>
            <Field label="Code from your app, or a recovery code" required>
              <input type="text" autoComplete="one-time-code" maxLength={20} value={code} onChange={event => setCode(event.target.value)} />
            </Field>
            {problem && <p className="field-error" role="alert">{problem}</p>}
            {disable.error && !problem && <FormError error={disable.error} />}
            <div><button type="submit" className="danger-button" disabled={disable.isPending}>{disable.isPending ? 'Turning off…' : 'Turn off'}</button></div>
          </form>
        )}
      </section>

      <ConfirmDialog
        open={regenOpen}
        title="Create new recovery codes?"
        message="Your current recovery codes will stop working immediately."
        confirmLabel="Create new codes"
        busy={regenerate.isPending}
        error={regenerate.error}
        onCancel={() => { setRegenOpen(false); regenerate.reset() }}
        onConfirm={() => regenerate.mutate()}
      />
    </>
  )
}
