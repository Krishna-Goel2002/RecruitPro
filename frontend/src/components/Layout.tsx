import { useEffect, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { authApi, meApi } from '../api/endpoints'
import { errorMessage } from '../api/client'
import { queryKeys } from '../api/queryKeys'
import { useAuth, useCurrentUser } from '../auth/AuthProvider'
import { initials } from '../lib/format'
import { useNotify } from './Notices'

const NAV_ITEMS = [
  { to: '/jobs', label: 'Find jobs', icon: '⌕' },
  { to: '/applications', label: 'Applications', icon: '▣' },
  { to: '/resumes', label: 'Resumes', icon: '▤' },
  { to: '/evidence', label: 'Evidence', icon: '✓' },
  { to: '/work-samples', label: 'Work samples', icon: '✎' },
  { to: '/interviews', label: 'Interviews', icon: '◷' },
  { to: '/notifications', label: 'Notifications', icon: 'bell' },
  { to: '/profile', label: 'Profile', icon: '◉' },
  { to: '/privacy', label: 'Privacy', icon: '◈' },
] as const

function BellIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
      <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
    </svg>
  )
}

/** Unread notifications. Refreshed on focus and every two minutes while the tab is visible. */
function useUnreadCount(): number {
  const { data } = useQuery({
    queryKey: queryKeys.unreadCount,
    queryFn: meApi.unreadCount,
    refetchInterval: 120_000,
    refetchOnWindowFocus: true,
    staleTime: 30_000,
  })
  return data?.count ?? 0
}

function UnreadBadge({ count }: { count: number }) {
  if (count <= 0) return null
  return (
    <span className="nav-count">
      <span aria-hidden="true">{count > 99 ? '99+' : count}</span>
      <span className="visually-hidden">, {count} unread</span>
    </span>
  )
}

export function Layout() {
  const user = useCurrentUser()
  const { logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const unread = useUnreadCount()
  const [menuOpen, setMenuOpen] = useState(false)
  const [signingOut, setSigningOut] = useState(false)
  // Hiding the reminder lasts for this page load only (nothing is stored in the browser).
  const [verifyHidden, setVerifyHidden] = useState(false)
  const firstRender = useRef(true)
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const mainRef = useRef<HTMLElement>(null)

  // After client-side navigation, close the mobile menu and move focus to the new page's heading.
  useEffect(() => {
    setMenuOpen(false)
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    const timer = window.setTimeout(() => {
      const heading = document.querySelector<HTMLElement>('[data-page-heading]')
      heading?.focus({ preventScroll: false })
    }, 0)
    return () => window.clearTimeout(timer)
  }, [location.pathname])

  // While the full-screen mobile menu is open, the page behind it is inert (not reachable with Tab).
  useEffect(() => {
    if (mainRef.current) mainRef.current.inert = menuOpen
  }, [menuOpen])

  // The menu only exists below the desktop breakpoint; close it if the window grows past it.
  useEffect(() => {
    const desktop = window.matchMedia('(min-width: 960px)')
    const onChange = () => {
      if (desktop.matches) setMenuOpen(false)
    }
    desktop.addEventListener('change', onChange)
    return () => desktop.removeEventListener('change', onChange)
  }, [])

  useEffect(() => {
    if (!menuOpen) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setMenuOpen(false)
        menuButtonRef.current?.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [menuOpen])

  async function signOut() {
    setSigningOut(true)
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">
        Skip to main content
      </a>
      <header className="mobile-bar">
        <div className="brand">
          <div className="brand-mark" aria-hidden="true">
            R
          </div>
          <span>RecruitPro</span>
        </div>
        <NavLink to="/notifications" className="mobile-bell" aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}>
          <BellIcon />
          {unread > 0 && (
            <span className="bell-count" aria-hidden="true">
              {unread > 99 ? '99+' : unread}
            </span>
          )}
        </NavLink>
        <button
          ref={menuButtonRef}
          type="button"
          className="quiet-button menu-button"
          aria-expanded={menuOpen}
          aria-controls="primary-nav"
          onClick={() => setMenuOpen(open => !open)}
        >
          {menuOpen ? 'Close' : 'Menu'}
        </button>
      </header>

      <aside className="sidebar" id="primary-nav" data-open={menuOpen}>
        <div className="brand desktop-only">
          <div className="brand-mark" aria-hidden="true">
            R
          </div>
          <span>RecruitPro</span>
        </div>
        <div className="side-label" id="nav-label">
          CANDIDATE SPACE
        </div>
        <nav aria-labelledby="nav-label">
          <ul>
            {NAV_ITEMS.map(item => (
              <li key={item.to}>
                <NavLink to={item.to} className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`} onClick={() => setMenuOpen(false)}>
                  <span className="nav-icon" aria-hidden="true">
                    {item.icon === 'bell' ? <BellIcon /> : item.icon}
                  </span>
                  {item.label}
                  {item.to === '/notifications' && <UnreadBadge count={unread} />}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <div className="sidebar-bottom">
          <div className="avatar" aria-hidden="true">
            {initials(user.firstName, user.lastName)}
          </div>
          <div className="account">
            <strong>
              {user.firstName} {user.lastName}
            </strong>
            <small>{user.email}</small>
          </div>
          <button type="button" className="quiet-button sign-out" onClick={() => void signOut()} disabled={signingOut}>
            {signingOut ? 'Signing out…' : 'Sign out'}
          </button>
        </div>
      </aside>

      <main ref={mainRef} className="main-area" id="main" tabIndex={-1}>
        <div className="content-area">
          {!user.emailVerified && !verifyHidden && <VerifyEmailBanner email={user.email} onHide={() => setVerifyHidden(true)} />}
          <Outlet />
        </div>
      </main>
    </div>
  )
}

function VerifyEmailBanner({ email, onHide }: { email: string; onHide: () => void }) {
  const notify = useNotify()
  const resend = useMutation({
    mutationFn: authApi.resendVerification,
    onSuccess: () => notify('success', `If ${email} is not verified yet, a new link is on its way.`),
    onError: error => notify('error', errorMessage(error)),
  })
  return (
    <aside className="verify-banner" aria-label="Email verification">
      <div>
        <strong>Please verify your email address</strong>
        <p>
          We sent a link to <span className="break-anywhere">{email}</span>. Some employers accept applications only from
          verified addresses.
        </p>
      </div>
      <div className="verify-actions">
        <button type="button" className="quiet-button" onClick={() => resend.mutate()} disabled={resend.isPending}>
          {resend.isPending ? 'Sending…' : 'Resend link'}
        </button>
        <button type="button" className="text-button" onClick={onHide}>
          Not now
        </button>
      </div>
    </aside>
  )
}
