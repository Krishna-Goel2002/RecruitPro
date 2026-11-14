import { useEffect, useRef, type ReactNode } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { useDocumentTitle } from '../../lib/hooks'

/** Centered card used by sign-in, registration, password and verification pages. */
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
    <main className="auth-shell" id="main">
      <div className="auth-card">
        <div className="brand-mark" aria-hidden="true">
          R
        </div>
        <span className="eyebrow">RECRUITPRO · CANDIDATE PORTAL</span>
        <Outlet />
      </div>
    </main>
  )
}

export function AuthHeading({ title, children }: { title: string; children?: ReactNode }) {
  useDocumentTitle(title)
  return (
    <>
      <h1 tabIndex={-1} data-page-heading>
        {title}
      </h1>
      {children && <p className="muted auth-intro">{children}</p>}
    </>
  )
}
