import { useId, useRef, useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { ApiError, errorMessage } from '../../api/client'
import { verificationApi } from '../../api/endpoints'
import type { SourceCreateRequest, SourceKind, VerificationSource } from '../../api/types'
import { CONSENT_REQUIRED, ConsentCheckbox } from '../../components/Consent'
import { FormError } from '../../components/Feedback'
import { Field } from '../../components/Form'
import { useNotify } from '../../components/Notices'
import { Section } from '../../components/PageHeader'
import { DOCUMENT_ACCEPT, validateDocumentFile } from '../../lib/files'
import { formatFileSize } from '../../lib/format'
import { isHttpUrl } from '../../lib/url'
import { SOURCE_KIND_LABEL, isOAuthNotConfigured } from '../../lib/verification'
import { verificationErrorMessage } from './shared'

const KINDS: { value: SourceKind; hint: string }[] = [
  { value: 'GITHUB', hint: 'Public repositories and contributions' },
  { value: 'GITLAB', hint: 'Public projects on gitlab.com' },
  { value: 'WEBSITE', hint: 'Your portfolio or personal site' },
  { value: 'DOCUMENT', hint: 'A certificate or transcript (PDF, DOCX, PPTX)' },
  { value: 'OPEN_BADGE', hint: 'A digital badge from an issuer' },
]

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

/** Connect a new source. Consent is required for every kind; GitHub can be connected (and proven) by signing in. */
export function ConnectSection({ onConnected }: { onConnected: (source: VerificationSource) => void }) {
  const notify = useNotify()
  const radioName = useId()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [kind, setKind] = useState<SourceKind>('GITHUB')
  const [consent, setConsent] = useState(false)
  const [consentError, setConsentError] = useState<string | null>(null)
  const [identifier, setIdentifier] = useState('')
  const [credential, setCredential] = useState('')
  const [badgeMode, setBadgeMode] = useState<'url' | 'paste'>('url')
  const [file, setFile] = useState<File | null>(null)
  const [fieldError, setFieldError] = useState<string | null>(null)
  /** GitHub sign-in is not configured on this server: only the username route is offered. */
  const [oauthUnavailable, setOauthUnavailable] = useState(false)
  const [githubByUsername, setGithubByUsername] = useState(false)

  function reset() {
    setIdentifier('')
    setCredential('')
    setFile(null)
    setConsent(false)
    setFieldError(null)
    setConsentError(null)
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

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
      } else {
        body.identifier = identifier.trim()
      }
      return verificationApi.createSource(body)
    },
    onSuccess: source => {
      reset()
      notify('success', `${source.displayLabel || SOURCE_KIND_LABEL[kind]} connected.`)
      onConnected(source)
    },
  })

  const github = useMutation({
    mutationFn: verificationApi.startGitHubOAuth,
    onSuccess: ({ authorizeUrl }) => window.location.assign(authorizeUrl),
    onError: error => {
      if (error instanceof ApiError && isOAuthNotConfigured(error.status, error.message)) {
        setOauthUnavailable(true)
        setGithubByUsername(true)
      }
    },
  })

  function requireConsent(): boolean {
    if (!consent) {
      setConsentError(CONSENT_REQUIRED)
      return false
    }
    setConsentError(null)
    return true
  }

  function signInWithGitHub() {
    if (!requireConsent()) return
    github.mutate()
  }

  function submit(event: FormEvent) {
    event.preventDefault()
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
  }

  function chooseKind(next: SourceKind) {
    setKind(next)
    setFieldError(null)
    setIdentifier('')
    create.reset()
    github.reset()
  }

  const showGitHubButton = kind === 'GITHUB' && !oauthUnavailable && !githubByUsername
  const createError = create.error ? verificationErrorMessage(create.error, 'connect') : null

  return (
    <Section title="Connect a source" id="connect" description="Choose where your work can be seen. Nothing is read until you agree.">
      <div className="panel">
        <form className="stack-form" onSubmit={submit} noValidate>
          <fieldset className="plain-fieldset">
            <legend>Kind of source</legend>
            <div className="choice-list kind-choices">
              {KINDS.map(option => (
                <label key={option.value} className={`choice${kind === option.value ? ' selected' : ''}`}>
                  <input
                    type="radio"
                    name={radioName}
                    value={option.value}
                    checked={kind === option.value}
                    onChange={() => chooseKind(option.value)}
                  />
                  <span>
                    <strong>{SOURCE_KIND_LABEL[option.value]}</strong>
                    <small>{option.hint}</small>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>

          {kind === 'GITHUB' && oauthUnavailable && (
            <p className="alert info" role="status">
              Signing in with GitHub is not available on this server. Connect your GitHub username instead and prove it is yours with a
              code in your profile bio.
            </p>
          )}

          {(kind === 'GITHUB' && !showGitHubButton) || kind === 'GITLAB' ? (
            <Field label={`${SOURCE_KIND_LABEL[kind]} username`} required error={fieldError} hint="Your username or profile address, for example octocat.">
              {control => (
                <input {...control} type="text" autoComplete="username" maxLength={200} value={identifier} onChange={event => setIdentifier(event.target.value)} />
              )}
            </Field>
          ) : null}

          {kind === 'WEBSITE' && (
            <Field label="Website address" required error={fieldError} hint="The page that shows your work, for example https://example.com/portfolio.">
              {control => (
                <input
                  {...control}
                  type="url"
                  inputMode="url"
                  placeholder="https://"
                  maxLength={500}
                  value={identifier}
                  onChange={event => setIdentifier(event.target.value)}
                />
              )}
            </Field>
          )}

          {kind === 'DOCUMENT' && (
            <Field
              label="Document"
              required
              error={fieldError}
              hint={file ? `${file.name} · ${formatFileSize(file.size)}` : 'PDF, DOCX or PPTX, at most 15 MB. Ownership of a document cannot be proven.'}
            >
              {control => (
                <input
                  {...control}
                  ref={fileInputRef}
                  type="file"
                  accept={DOCUMENT_ACCEPT}
                  onChange={event => {
                    const chosen = event.target.files?.[0] ?? null
                    setFile(chosen)
                    setFieldError(chosen ? validateDocumentFile(chosen) : null)
                  }}
                />
              )}
            </Field>
          )}

          {kind === 'OPEN_BADGE' && (
            <>
              <fieldset className="plain-fieldset">
                <legend>How do you have the badge?</legend>
                <div className="choice-list">
                  <label className={`choice${badgeMode === 'url' ? ' selected' : ''}`}>
                    <input type="radio" name={`${radioName}-badge`} checked={badgeMode === 'url'} onChange={() => setBadgeMode('url')} />
                    <span>
                      <strong>A link to the badge</strong>
                    </span>
                  </label>
                  <label className={`choice${badgeMode === 'paste' ? ' selected' : ''}`}>
                    <input type="radio" name={`${radioName}-badge`} checked={badgeMode === 'paste'} onChange={() => setBadgeMode('paste')} />
                    <span>
                      <strong>Paste the credential</strong>
                    </span>
                  </label>
                </div>
              </fieldset>
              {badgeMode === 'url' ? (
                <Field label="Badge address" required error={fieldError} hint="The address of the hosted badge assertion.">
                  {control => (
                    <input
                      {...control}
                      type="url"
                      inputMode="url"
                      placeholder="https://"
                      maxLength={500}
                      value={identifier}
                      onChange={event => setIdentifier(event.target.value)}
                    />
                  )}
                </Field>
              ) : (
                <Field label="Badge credential" required error={fieldError} hint="The JSON object or signed token your issuer gave you.">
                  {control => <textarea {...control} rows={5} value={credential} onChange={event => setCredential(event.target.value)} />}
                </Field>
              )}
            </>
          )}

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

          {github.error && !oauthUnavailable && <FormError error={errorMessage(github.error)} />}
          {createError && <FormError error={createError} />}

          <div className="form-actions">
            {showGitHubButton ? (
              <>
                <button type="button" className="primary" onClick={signInWithGitHub} disabled={github.isPending}>
                  {github.isPending ? 'Opening GitHub…' : 'Sign in with GitHub'}
                </button>
                <button type="button" className="text-button" onClick={() => setGithubByUsername(true)}>
                  Connect with my username instead
                </button>
              </>
            ) : (
              <>
                <button type="submit" className="primary" disabled={create.isPending}>
                  {create.isPending ? (kind === 'DOCUMENT' ? 'Uploading…' : 'Connecting…') : kind === 'DOCUMENT' ? 'Upload document' : 'Connect source'}
                </button>
                {kind === 'GITHUB' && !oauthUnavailable && (
                  <button type="button" className="text-button" onClick={() => setGithubByUsername(false)}>
                    Sign in with GitHub instead
                  </button>
                )}
              </>
            )}
          </div>
          {showGitHubButton && (
            <p className="muted">Signing in proves the account is yours straight away. We only ask GitHub for read access to public information.</p>
          )}
        </form>
      </div>
    </Section>
  )
}
