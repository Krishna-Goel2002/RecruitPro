import { InputHTMLAttributes, ReactNode, useId, useState } from 'react'
import { AlertIcon, BriefcaseIcon, LockIcon } from '../../components/Icons'
import { Brand } from '../../components/Layout'

/** Split-screen layout of the original sign-in page, reused by every public account screen. */
export function AuthLayout({ eyebrow, title, intro, children, footer }: {
  eyebrow: string
  title: string
  intro: ReactNode
  children: ReactNode
  footer?: ReactNode
}) {
  return (
    <main className="page-shell">
      <section className="welcome-panel" aria-hidden="true">
        <div className="welcome-content">
          <Brand />
          <div className="welcome-copy">
            <p className="eyebrow">HIRING, ON EVIDENCE</p>
            <h2 className="welcome-title">Make room for better hiring.</h2>
            <p className="welcome-description">Blind-first review, verified evidence and structured interviews, so every decision can be explained.</p>
          </div>
          <div className="workflow-card">
            <div className="workflow-topline"><span className="workflow-dot" /><span>EVIDENCE LADDER</span><span className="workflow-line" /></div>
            <div className="workflow-title"><span className="workflow-icon"><BriefcaseIcon /></span><span><strong>Claimed → Resume → Verified.</strong><small>Rank on what is shown, not what is said.</small></span></div>
            <div className="workflow-progress"><span /><span /><span /></div>
            <div className="workflow-foot"><span>RECRUITPRO</span><span>PEOPLE FIRST · ALWAYS</span></div>
          </div>
        </div>
        <div className="welcome-footer"><span>© RecruitPro</span><span>Built for teams that grow with purpose</span></div>
      </section>

      <section className="form-panel">
        <div className="form-wrap">
          <div className="mobile-brand"><Brand dark /></div>
          <div className="form-heading">
            <span className="secure-label"><LockIcon className="lock-icon" /> {eyebrow}</span>
            <h1>{title}</h1>
            <p>{intro}</p>
          </div>
          {children}
          {footer && <div className="auth-footer-links">{footer}</div>}
          <div className="form-bottom"><span>RecruitPro HR Workspace</span><span>Secure access</span></div>
        </div>
      </section>
    </main>
  )
}

type AuthInputProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> & {
  label: string
  icon?: ReactNode
  error?: string | null
  hint?: string
  revealable?: boolean
}

/** Labelled input in the sign-in style, with optional show/hide for passwords. */
export function AuthInput({ label, icon, error, hint, revealable = false, type = 'text', ...rest }: AuthInputProps) {
  const id = useId()
  const [visible, setVisible] = useState(false)
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined
  return (
    <div className="auth-field">
      <label className="field-label" htmlFor={id}>{label}</label>
      <div className={`input-wrap${icon ? '' : ' no-icon'}`}>
        {icon}
        <input id={id} type={revealable && visible ? 'text' : type} aria-invalid={Boolean(error)} aria-describedby={describedBy} {...rest} />
        {revealable && (
          <button className="visibility-button" type="button" onClick={() => setVisible(value => !value)} aria-label={visible ? 'Hide password' : 'Show password'} aria-pressed={visible}>
            {visible ? 'Hide' : 'Show'}
          </button>
        )}
      </div>
      {error ? <p className="auth-field-error" id={`${id}-error`}>{error}</p> : hint ? <p className="auth-field-hint" id={`${id}-hint`}>{hint}</p> : null}
    </div>
  )
}

export function AuthError({ message }: { message: string | null }) {
  if (!message) return null
  return <p className="error-message" role="alert"><AlertIcon />{message}</p>
}

export function AuthSuccess({ children }: { children: ReactNode }) {
  return <div className="auth-success" role="status">{children}</div>
}
