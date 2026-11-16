import { useEffect, useRef, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import brand from '../../design/brand/active'
import { Alert, EvidenceLadder, ThemeSwitch } from '../../design/components'
import { usePageTitle } from '../../lib/hooks'
import './auth.css'

/** Brand lock-up for the auth pages: mark + name + portal label, never wrapping (B1). */
export function AuthBrand() {
  return (
    <Link to="/login" className="hra-auth-brand">
      <brand.Mark size={20} />
      <span className="hra-auth-wordmark">{brand.name}</span>
      {brand.portalLabel.staff && <span className="hra-auth-portal">{brand.portalLabel.staff}</span>}
    </Link>
  )
}

/**
 * Split auth layout shared by every public staff screen (sign-in, MFA, enrolment, sign-up, invitation, password,
 * e-mail verification, SSO). Form column (max 400 px) on the left; on ≥ 900 px a quiet, product-true specimen of the
 * evidence ladder on the right. The H1 takes focus whenever the step changes, so screen readers hear the new step.
 */
export function AuthLayout({ title, intro, overline, children, footer, stepKey, documentTitle }: {
  title: string
  intro?: ReactNode
  /** Small line above the title, e.g. "Step 2 of 3". At most one per screen. */
  overline?: ReactNode
  children: ReactNode
  footer?: ReactNode
  /** Changing this moves focus to the heading (multi-step flows on one route). */
  stepKey?: string
  documentTitle?: string
}) {
  usePageTitle(documentTitle ?? title)
  const heading = useRef<HTMLHeadingElement>(null)
  const location = useLocation()
  const first = useRef(true)
  useEffect(() => {
    if (first.current) {
      first.current = false
      return
    }
    heading.current?.focus({ preventScroll: true })
  }, [stepKey, location.pathname])

  return (
    <div className="hra-auth">
      <div className="hra-auth-col">
        <header className="hra-auth-top">
          <AuthBrand />
          <ThemeSwitch variant="icon" />
        </header>
        <main className="hra-auth-main" id="main-content">
          <div className="hra-auth-body" key={stepKey}>
            {overline && <p className="t-overline hra-auth-overline">{overline}</p>}
            <h1 ref={heading} className="hra-auth-title" tabIndex={-1} data-page-heading="">
              {title}
            </h1>
            {intro && <p className="hra-auth-intro">{intro}</p>}
            {children}
          </div>
        </main>
        <footer className="hra-auth-foot">
          {footer && <div className="hra-auth-links">{footer}</div>}
          <p className="hra-auth-legal">Staff workspace. Every action is checked against your role on the server.</p>
        </footer>
      </div>
      <AuthSpecimen />
    </div>
  )
}

/** The right-hand column: what the product actually does, drawn with the real components. Decorative. */
function AuthSpecimen() {
  return (
    <aside className="hra-auth-aside" aria-hidden="true">
      <div className="hra-auth-specimen">
        <p className="hra-specimen-kicker">Evidence ladder</p>
        <p className="hra-specimen-line">Every rank shows the evidence behind it.</p>
        <p className="hra-specimen-sub">
          Five rungs, from a candidate’s own claim to a structured interview. Rankings count what was shown, never what was only said.
        </p>
        <EvidenceLadder tier="INTERVIEW_VERIFIED" variant="legend" audience="staff" />
        <p className="hra-specimen-foot">Coverage is the share of weighted requirements with evidence. It is not a probability of success.</p>
      </div>
    </aside>
  )
}

/** One message slot above a form: why the session ended, an SSO error, or a request failure. */
export function AuthNotice({ tone, children, title }: { tone: 'info' | 'danger' | 'success' | 'warning'; children?: ReactNode; title?: ReactNode }) {
  return (
    <Alert tone={tone} title={title} live className="hra-auth-alert">
      {children}
    </Alert>
  )
}
