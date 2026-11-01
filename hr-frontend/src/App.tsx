import { FormEvent, useCallback, useState } from 'react'
import Workspace from './Workspace'

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1').replace(/\/$/, '')
const SESSION_KEY = 'recruitpro.hr.session'

type UserRole = 'ADMIN' | 'HR_MANAGER' | 'RECRUITER'
const userRoles: UserRole[] = ['ADMIN', 'HR_MANAGER', 'RECRUITER']

type AuthUser = {
  id: number
  email: string
  firstName: string
  lastName: string
  role: string
}

type AuthData = {
  accessToken: string
  refreshToken: string
  tokenType: string
  user: AuthUser
}

type ApiEnvelope<T> = {
  success: boolean
  message?: string
  data?: T
  errors?: { field: string; message: string }[]
}

type HrSession = {
  accessToken: string
  refreshToken: string
  role: UserRole
  email: string
  firstName: string
  lastName: string
}

function readSession(): HrSession | null {
  try {
    const stored = sessionStorage.getItem(SESSION_KEY)
    if (!stored) return null
    const value = JSON.parse(stored) as Partial<HrSession>
    if (!value.accessToken || !value.refreshToken || !value.email || !userRoles.includes(value.role as UserRole)) {
      sessionStorage.removeItem(SESSION_KEY)
      return null
    }
    return value as HrSession
  } catch {
    sessionStorage.removeItem(SESSION_KEY)
    return null
  }
}

function messageFrom(body: unknown, fallback: string): string {
  if (typeof body === 'object' && body !== null && 'message' in body) {
    const message = (body as { message?: unknown }).message
    if (typeof message === 'string' && message.trim()) return message
  }
  return fallback
}

async function revokeRefreshToken(refreshToken: string): Promise<void> {
  try {
    await fetch(`${API_BASE_URL}/auth/logout`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    })
  } catch {
    // The token is never retained for an unsupported role; this best-effort call revokes its refresh token.
  }
}

export default function App() {
  const [session, setSession] = useState<HrSession | null>(() => readSession())
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [loading, setLoading] = useState(false)
  const [signingOut, setSigningOut] = useState(false)
  const [error, setError] = useState('')
  const expireSession = useCallback(() => {
    sessionStorage.removeItem(SESSION_KEY)
    setSession(null)
  }, [])

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError('')

    const normalizedEmail = email.trim()
    if (!normalizedEmail) {
      setError('Enter your work email address.')
      return
    }
    if (!password.trim()) {
      setError('Enter your password.')
      return
    }

    setLoading(true)
    try {
      const response = await fetch(`${API_BASE_URL}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: normalizedEmail, password }),
      })

      let envelope: ApiEnvelope<AuthData> | null = null
      try {
        envelope = await response.json() as ApiEnvelope<AuthData>
      } catch {
        throw new Error(`Sign-in failed (${response.status}). The server returned an unreadable response.`)
      }

      if (!response.ok || envelope?.success !== true || !envelope.data) {
        throw new Error(messageFrom(envelope, `Sign-in failed (${response.status}). Check your details and try again.`))
      }

      const auth = envelope.data
      if (!userRoles.includes(auth.user?.role as UserRole)) {
        if (auth.refreshToken) await revokeRefreshToken(auth.refreshToken)
        throw new Error('This workspace is available to ADMIN, HR_MANAGER, and RECRUITER accounts.')
      }
      if (!auth.accessToken || !auth.refreshToken || !auth.user.email) {
        if (auth.refreshToken) await revokeRefreshToken(auth.refreshToken)
        throw new Error('The server returned an incomplete sign-in response. Please try again.')
      }

      const nextSession: HrSession = {
        accessToken: auth.accessToken,
        refreshToken: auth.refreshToken,
        role: auth.user.role as UserRole,
        email: auth.user.email,
        firstName: auth.user.firstName || '',
        lastName: auth.user.lastName || '',
      }
      sessionStorage.setItem(SESSION_KEY, JSON.stringify(nextSession))
      setSession(nextSession)
      setPassword('')
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to sign in. Please try again.')
      setPassword('')
    } finally {
      setLoading(false)
    }
  }

  async function signOut() {
    if (!session) return
    setSigningOut(true)
    try {
      await fetch(`${API_BASE_URL}/auth/logout`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: session.refreshToken }),
      })
    } catch {
      // Clear this browser session even if the API is unavailable.
    } finally {
      sessionStorage.removeItem(SESSION_KEY)
      setSession(null)
      setSigningOut(false)
      setError('')
    }
  }

  if (session) {
    return <Workspace session={session} signingOut={signingOut} onSignOut={() => void signOut()} onSessionExpired={expireSession} />
  }

  return (
    <main className="page-shell">
      <section className="welcome-panel" aria-label="RecruitPro HR workspace">
        <div className="welcome-content">
          <Brand />
          <div className="welcome-copy">
            <p className="eyebrow">HIRING, WITH CLARITY</p>
            <h1>Make room for better hiring.</h1>
            <p className="welcome-description">A focused workspace for the people shaping your team’s next opportunity.</p>
          </div>
          <div className="workflow-card" aria-hidden="true">
            <div className="workflow-topline"><span className="workflow-dot" /><span>HIRING WORKSPACE</span><span className="workflow-line" /></div>
            <div className="workflow-title"><span className="workflow-icon"><BriefcaseIcon /></span><span><strong>Thoughtful roles.</strong><small>Clear from draft to published.</small></span></div>
            <div className="workflow-progress"><span /><span /><span /></div>
            <div className="workflow-foot"><span>RECRUITPRO</span><span>PEOPLE FIRST · ALWAYS</span></div>
          </div>
        </div>
        <div className="welcome-footer"><span>© RecruitPro</span><span>Built for teams that grow with purpose</span></div>
      </section>

      <section className="form-panel" aria-labelledby="sign-in-title">
        <div className="mobile-brand"><Brand /></div>
        <div className="form-wrap">
          <div className="form-heading">
            <span className="secure-label"><LockIcon /> PRIVATE HR ACCESS</span>
            <h2 id="sign-in-title">Welcome back</h2>
            <p>Sign in with your RecruitPro work account to continue.</p>
          </div>

          <form className="sign-in-form" onSubmit={event => void signIn(event)}>
            <label className="field-label" htmlFor="email">Work email</label>
            <div className="input-wrap">
              <MailIcon />
              <input
                id="email"
                name="email"
                type="email"
                autoComplete="username"
                inputMode="email"
                maxLength={255}
                placeholder="you@company.com"
                value={email}
                onChange={event => setEmail(event.target.value)}
                disabled={loading}
                required
                aria-invalid={Boolean(error)}
                aria-describedby={error ? 'sign-in-error' : undefined}
              />
            </div>

            <div className="password-label-row">
              <label className="field-label" htmlFor="password">Password</label>
            </div>
            <div className="input-wrap">
              <LockIcon />
              <input
                id="password"
                name="password"
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                maxLength={72}
                placeholder="Enter your password"
                value={password}
                onChange={event => setPassword(event.target.value)}
                disabled={loading}
                required
                aria-invalid={Boolean(error)}
                aria-describedby={error ? 'sign-in-error' : undefined}
              />
              <button className="visibility-button" type="button" onClick={() => setShowPassword(value => !value)} aria-label={showPassword ? 'Hide password' : 'Show password'} aria-pressed={showPassword}>
                {showPassword ? 'Hide' : 'Show'}
              </button>
            </div>

            {error && <p className="error-message" id="sign-in-error" role="alert"><AlertIcon />{error}</p>}

            <button className="primary-button" type="submit" disabled={loading}>
              {loading ? <><span className="spinner" aria-hidden="true" />Signing in…</> : <>Sign in to HR workspace <ArrowIcon /></>}
            </button>
          </form>

          <div className="form-security-note"><LockIcon /><p>Authorized RecruitPro personnel only. Access is verified by the server for every protected action.</p></div>
          <div className="form-bottom"><span>RecruitPro HR Workspace</span><span>Secure sign-in</span></div>
        </div>
      </section>
    </main>
  )
}

function Brand() {
  return <a className="brand" href="/" aria-label="RecruitPro HR workspace home"><span className="brand-mark"><span>R</span><i /></span><span className="brand-name">Recruit<span>Pro</span><small>HR WORKSPACE</small></span></a>
}

function MailIcon() {
  return <svg className="input-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="3.5" y="5" width="17" height="14" rx="3" /><path d="m5 7 7 5.5L19 7" /></svg>
}

function LockIcon() {
  return <svg className="lock-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="4.5" y="10" width="15" height="11" rx="2.5" /><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 4v3" /></svg>
}

function BriefcaseIcon() {
  return <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="3" y="7" width="18" height="13" rx="2.5" /><path d="M8 7V5.5A1.5 1.5 0 0 1 9.5 4h5A1.5 1.5 0 0 1 16 5.5V7M3 12h18m-11 0v2h4v-2" /></svg>
}

function AlertIcon() {
  return <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 7.5v5m0 4h.01" /></svg>
}

function ArrowIcon() {
  return <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 12h14m-6-6 6 6-6 6" /></svg>
}
