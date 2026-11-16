import { Suspense, useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, Outlet, useLocation } from 'react-router-dom'
import brand from '../../design/brand/active'
import { EvidenceLadder, Spinner, ThemeSwitch, VerdictChip } from '../../design/components'
import { useDocumentTitle } from '../../design/components/PageHeader'
import { reduced } from '../../design/motion'
import './auth.css'

/**
 * Auth layout (REDESIGN_PLAN A1): the form column (max 400 px) on the left and, from 900 px, a quiet product
 * specimen on the right: three real-looking claims climbing the evidence ladder, one of them being checked. No
 * gradients, blobs or filler copy (UX X-5). Phone: the form only.
 */
export function AuthLayout() {
  const location = useLocation()
  const firstRender = useRef(true)

  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    document.querySelector<HTMLElement>('[data-page-heading]')?.focus()
  }, [location.pathname])

  return (
    <div className="auth">
      <main className="auth-main" id="main">
        <div className="auth-top">
          <Link to="/login" className="auth-brand" aria-label={`${brand.name}, sign in`}>
            <brand.Mark size={20} />
            <span className="p-wordmark">{brand.name}</span>
          </Link>
          <ThemeSwitch variant="icon" />
        </div>
        <div className="auth-form-wrap">
          <div className="auth-form" key={location.pathname}>
            {/* Register, forgot, reset and verify are lazy routes: they need a boundary of their own here. */}
            <Suspense
              fallback={
                <p className="auth-wait" role="status">
                  <Spinner size={14} /> Loading…
                </p>
              }
            >
              <Outlet />
            </Suspense>
          </div>
        </div>
        <p className="auth-foot">A person reviews every application. Nothing you connect is shown to employers until you choose.</p>
      </main>
      <aside className="auth-aside" aria-hidden="true">
        <Specimen />
      </aside>
    </div>
  )
}

/** Decorative product specimen (hidden from assistive tech). The verdict chip resolves once after load (M6). */
function Specimen() {
  const [checked, setChecked] = useState(() => reduced())
  useEffect(() => {
    if (checked) return
    const t = window.setTimeout(() => setChecked(true), 1400)
    return () => window.clearTimeout(t)
  }, [checked])

  return (
    <div className="auth-specimen">
      <p className="auth-aside-title">Your evidence, your call.</p>
      <p className="auth-aside-text">
        Show what you can do, check it against places that prove it, and decide what employers see. Hiring here runs on
        evidence, not keywords.
      </p>
      <div className="auth-sheet">
        <div className="auth-sheet-head">
          <span>Claim</span>
          <span>Evidence</span>
        </div>
        <div className="auth-sheet-row">
          <span className="auth-claim">Java 17 and Spring Boot</span>
          <span className="auth-cell">
            <EvidenceLadder tier="CORROBORATED" audience="candidate" />
          </span>
          <span className="auth-verdict">
            <VerdictChip verdict="VERIFIED" state={checked ? 'done' : 'checking'} size="sm" />
            <span className="auth-src">github.com</span>
          </span>
        </div>
        <div className="auth-sheet-row">
          <span className="auth-claim">PostgreSQL query tuning</span>
          <span className="auth-cell">
            <EvidenceLadder tier="RESUME_STATED" audience="candidate" />
          </span>
        </div>
        <div className="auth-sheet-row">
          <span className="auth-claim">Kubernetes</span>
          <span className="auth-cell">
            <EvidenceLadder tier="SELF_CLAIMED" audience="candidate" />
          </span>
        </div>
      </div>
      <EvidenceLadder tier={null} audience="candidate" variant="legend" compact className="auth-legend" />
    </div>
  )
}

export function AuthHeading({ title, children }: { title: string; children?: ReactNode }) {
  useDocumentTitle(title)
  return (
    <header className="auth-heading">
      <h1 className="auth-title" tabIndex={-1} data-page-heading="">
        {title}
      </h1>
      {children && <p className="auth-intro">{children}</p>}
    </header>
  )
}
