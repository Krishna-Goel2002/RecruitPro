import { useEffect, useRef, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import brand from '../../design/brand/active'
import { Alert, CoverageMeter, EvidenceLadder, ThemeSwitch } from '../../design/components'
import { ArrowLeft, Icon } from '../../design/icons'
import { appLinks } from '../../design/lib/links'
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
  const links = appLinks()
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
          <span className="hra-auth-top-end">
            <a href={links.site} className="hra-auth-site-link">
              <Icon icon={ArrowLeft} size={14} />
              Back to home
            </a>
            <ThemeSwitch variant="icon" />
          </span>
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
          <p className="hra-auth-switch">
            Looking for a job? <a href={links.candidateLogin}>Candidate sign in</a>
          </p>
          <p className="hra-auth-legal">Staff workspace. Every action is checked against your role on the server.</p>
        </footer>
      </div>
      <AuthSpecimen />
    </div>
  )
}

/** Specimen rows for the product preview: fictional, blind codes only (blind-first is the default the product ships). */
const PREVIEW = [
  { code: 'A-3001', tier: 'INTERVIEW_VERIFIED', verified: 86, claimed: 90, note: 'Every gate verified' },
  { code: 'A-3014', tier: 'DEMONSTRATED', verified: 74, claimed: 82, note: 'Work sample scored 4 of 4' },
  { code: 'A-3022', tier: 'CORROBORATED', verified: 61, claimed: 78, note: 'Certificate confirmed' },
] as const

/**
 * The right-hand column: what the product actually does, drawn with the real components. Decorative (aria-hidden).
 * A product preview (a blind ranking whose coverage bars grow and whose rungs fill on arrival) above the ladder legend,
 * on a quiet dotted field.
 */
function AuthSpecimen() {
  return (
    <aside className="hra-auth-aside" aria-hidden="true">
      <div className="hra-auth-specimen ui-stagger">
        <p className="hra-specimen-kicker">Evidence ladder</p>
        <p className="hra-specimen-line">Every rank shows the evidence behind it.</p>
        <p className="hra-specimen-sub">
          Rankings count what was shown, never what was only said. People make every decision.
        </p>
        <div className="hra-preview">
          <div className="hra-preview-head">
            <span className="hra-preview-title">Senior Backend Engineer</span>
            <span className="hra-preview-live"><span className="hra-preview-dot" />Ranked by verified evidence</span>
          </div>
          <ol className="hra-preview-list ui-stagger">
            {PREVIEW.map((row, i) => (
              <li key={row.code} className="hra-preview-row">
                <span className="hra-preview-rank numeric">{i + 1}</span>
                <span className="hra-preview-who">
                  <span className="hra-preview-code mono">{row.code}</span>
                  <span className="hra-preview-note">{row.note}</span>
                </span>
                <EvidenceLadder tier={row.tier} variant="chip" audience="staff" compact />
                <CoverageMeter compact verified={row.verified} claimed={row.claimed} note={null} className="hra-preview-cov" />
              </li>
            ))}
          </ol>
        </div>
        <EvidenceLadder tier="INTERVIEW_VERIFIED" variant="legend" audience="staff" compact />
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
