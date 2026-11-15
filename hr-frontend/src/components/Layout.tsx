import { ReactNode, useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { accountApi } from '../api/account'
import { qk } from '../api/queryKeys'
import { useAuth, useCurrentUser } from '../auth/AuthContext'
import { Capability } from '../auth/permissions'
import { fullName, roleLabel } from '../lib/format'
import {
  BellIcon, BriefcaseIcon, BuildingIcon, CalendarIcon, CheckIcon, DocumentIcon, GridIcon, InviteIcon, KeyIcon, LockIcon, ScaleIcon, ShieldIcon, UsersIcon,
} from './Icons'

type NavItem = { to: string; label: string; icon: ReactNode; capability: Capability; end?: boolean }

const WORKSPACE_NAV: NavItem[] = [
  { to: '/jobs', label: 'Jobs', icon: <BriefcaseIcon />, capability: 'jobs.view' },
  { to: '/interviews', label: 'My interviews', icon: <CalendarIcon />, capability: 'interviews.view' },
]

const ADMIN_NAV: NavItem[] = [
  { to: '/admin', label: 'Overview', icon: <GridIcon />, capability: 'admin', end: true },
  { to: '/admin/users', label: 'Staff accounts', icon: <UsersIcon />, capability: 'admin' },
  { to: '/admin/invitations', label: 'Invitations', icon: <InviteIcon />, capability: 'admin' },
  { to: '/admin/organization', label: 'Organisation', icon: <BuildingIcon />, capability: 'admin' },
  { to: '/admin/jobs', label: 'Job oversight', icon: <ShieldIcon />, capability: 'admin' },
  { to: '/admin/audit', label: 'Audit log', icon: <DocumentIcon />, capability: 'admin' },
  { to: '/admin/fairness', label: 'Fairness', icon: <ScaleIcon />, capability: 'admin' },
  { to: '/admin/verification', label: 'Verification', icon: <CheckIcon />, capability: 'verification.queue' },
]

const ACCOUNT_NAV: NavItem[] = [
  { to: '/notifications', label: 'Notifications', icon: <BellIcon />, capability: 'account' },
  { to: '/account/password', label: 'Change password', icon: <KeyIcon />, capability: 'account' },
  { to: '/account/security', label: 'Two-step verification', icon: <LockIcon />, capability: 'account' },
]

/** Unread in-app notifications; refreshed every two minutes and when the window regains focus. */
function useUnreadCount(): number {
  const { data } = useQuery({
    queryKey: qk.unreadCount,
    queryFn: ({ signal }) => accountApi.unreadCount(signal),
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
      <span className="sr-only">, {count} unread</span>
    </span>
  )
}

export function Brand({ dark = false }: { dark?: boolean }) {
  return (
    <span className={`brand${dark ? ' brand-dark' : ''}`}>
      <span className="brand-mark" aria-hidden="true"><span>R</span><i /></span>
      <span className="brand-name">Recruit<span>Pro</span><small>HR WORKSPACE</small></span>
    </span>
  )
}

/** Authenticated shell: role-based sidebar (or a scrollable strip on narrow screens), header, and focus on navigation. */
export function AppLayout() {
  const { can, signOut } = useAuth()
  const user = useCurrentUser()
  const location = useLocation()
  const mainRef = useRef<HTMLElement>(null)
  const [signingOut, setSigningOut] = useState(false)
  const firstRender = useRef(true)

  const workspace = WORKSPACE_NAV.filter(item => can(item.capability))
  const admin = ADMIN_NAV.filter(item => can(item.capability))
  const account = ACCOUNT_NAV.filter(item => can(item.capability))
  const unread = useUnreadCount()

  // Move focus to the new screen so keyboard and screen-reader users start at its content.
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    mainRef.current?.focus({ preventScroll: true })
    window.scrollTo({ top: 0 })
  }, [location.pathname])

  const name = fullName(user)

  return (
    <div className="workspace-shell">
      <a className="skip-link" href="#main-content">Skip to content</a>
      <aside className="workspace-sidebar" aria-label="Main navigation">
        <NavLink to="/" className="brand-link" aria-label="RecruitPro HR home"><Brand /></NavLink>
        <nav aria-label="Primary">
          {workspace.length > 0 && <>
            <div className="sidebar-caption">WORKSPACE</div>
            {workspace.map(item => <SideLink key={item.to} item={item} />)}
          </>}
          {admin.length > 0 && <>
            <div className="sidebar-caption spaced">ADMINISTRATION</div>
            {admin.map(item => <SideLink key={item.to} item={item} />)}
          </>}
          {account.length > 0 && <>
            <div className="sidebar-caption spaced">ACCOUNT</div>
            {account.map(item => <SideLink key={item.to} item={item} badge={item.to === '/notifications' ? <UnreadBadge count={unread} /> : undefined} />)}
          </>}
        </nav>
        <div className="sidebar-bottom">
          <span className="sidebar-secure"><LockIcon /> {user.organizationName ?? 'Private HR workspace'}</span>
          <span className="sidebar-version">RecruitPro · Evidence-first hiring</span>
        </div>
      </aside>
      <div className="workspace-main">
        <header className="workspace-header">
          <div className="mobile-workspace-brand"><NavLink to="/" aria-label="RecruitPro HR home"><Brand dark /></NavLink></div>
          <div className="header-org">{user.organizationName}</div>
          <div className="user-menu">
            <NavLink to="/notifications" className="header-bell" aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}>
              <BellIcon />
              {unread > 0 && <span className="bell-count" aria-hidden="true">{unread > 99 ? '99+' : unread}</span>}
            </NavLink>
            <span className="user-avatar" aria-hidden="true">{name.charAt(0).toUpperCase()}</span>
            <span className="user-name"><strong>{name}</strong><small>{roleLabel(user.role)}</small></span>
            <button
              type="button"
              className="signout-link"
              disabled={signingOut}
              onClick={() => { setSigningOut(true); void signOut().finally(() => setSigningOut(false)) }}
            >
              {signingOut ? 'Signing out…' : 'Sign out'}
            </button>
          </div>
        </header>
        <nav className="mobile-nav" aria-label="Primary (compact)">
          {[...workspace, ...admin, ...account].map(item => (
            <NavLink key={item.to} to={item.to} end={item.end} className={({ isActive }) => `mobile-nav-item${isActive ? ' active' : ''}`}>{item.label}</NavLink>
          ))}
        </nav>
        <main id="main-content" className="workspace-content" ref={mainRef} tabIndex={-1}>
          <Outlet />
        </main>
      </div>
    </div>
  )
}

function SideLink({ item, badge }: { item: NavItem; badge?: ReactNode }) {
  return (
    <NavLink to={item.to} end={item.end} className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
      {item.icon} {item.label}{badge}
    </NavLink>
  )
}

/** Page heading row used by every screen. */
export function PageHeader({ eyebrow, title, subtitle, actions }: { eyebrow?: string; title: ReactNode; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page-title-row">
      <div>
        {eyebrow && <p className="eyebrow">{eyebrow}</p>}
        <h1>{title}</h1>
        {subtitle && <p className="page-subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  )
}
