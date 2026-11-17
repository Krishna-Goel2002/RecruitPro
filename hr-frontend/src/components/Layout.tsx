import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { NavLink, Outlet } from 'react-router-dom'
import { accountApi } from '../api/account'
import { qk } from '../api/queryKeys'
import { SIGNED_OUT_NOTICE, useAuth, useCurrentUser } from '../auth/AuthContext'
import { Capability } from '../auth/permissions'
import brand from '../design/brand/active'
import { AccountMenu, AppShell, BackNavProvider, Tooltip, type NavGroup, type NavItem } from '../design/components'
import {
  BadgeCheck, Bell, BriefcaseBusiness, Building2, CalendarClock, House, Icon, Inbox, KeyRound, LadderMark, LayoutGrid, ListChecks, MailPlus, MessagesSquare,
  Scale, ScrollText, ShieldCheck, User as UserIcon, Users,
} from '../design/icons'
import { AssistantProvider } from '../assistant/AssistantProvider'
import { AskButton, AssistantPanel } from '../assistant/AssistantPanel'
import { appLinks } from '../design/lib/links'
import { useBackNav } from '../lib/backNav'
import { fullName, roleLabel } from '../lib/format'
import { readFlag } from '../lib/lastSeen'
import { SETUP_HIDDEN_FLAG, useSetupProgress } from '../pages/home/setup'

type StaffNavItem = NavItem & { capability: Capability }

const WORKSPACE_NAV: StaffNavItem[] = [
  { to: '/jobs', label: 'Jobs', icon: BriefcaseBusiness, capability: 'jobs.view' },
  { to: '/interviews', label: 'My interviews', icon: CalendarClock, capability: 'interviews.view' },
  { to: '/assistant', label: 'Assistant', icon: MessagesSquare, capability: 'account' },
]

const ADMIN_NAV: StaffNavItem[] = [
  { to: '/admin', label: 'Overview', icon: LayoutGrid, capability: 'admin', end: true },
  { to: '/admin/users', label: 'Staff accounts', icon: Users, capability: 'admin' },
  { to: '/admin/invitations', label: 'Invitations', icon: MailPlus, capability: 'admin' },
  { to: '/admin/organization', label: 'Organisation', icon: Building2, capability: 'admin' },
  { to: '/admin/jobs', label: 'Job oversight', icon: ShieldCheck, capability: 'admin' },
  { to: '/admin/audit', label: 'Audit log', icon: ScrollText, capability: 'admin' },
  { to: '/admin/fairness', label: 'Fairness', icon: Scale, capability: 'admin' },
  { to: '/admin/verification', label: 'Verification', icon: BadgeCheck, capability: 'verification.queue' },
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

/**
 * Brand lock-up for the (not yet redesigned) sign-in screens. It keeps the legacy markup those screens style, with the
 * name from the active brand preset. Page engineers replace it with the shared AuthLayout (REDESIGN_PLAN B1).
 */
export function Brand({ dark = false }: { dark?: boolean }) {
  return (
    <span className={`brand${dark ? ' brand-dark' : ''}`}>
      <span className="brand-mark" aria-hidden="true"><Icon icon={LadderMark} size={20} /></span>
      <span className="brand-name">{brand.name}<small>{brand.portalLabel.staff.toUpperCase()}</small></span>
    </span>
  )
}

/**
 * Authenticated shell: the shared design-system AppShell with role-based navigation, the assistant ("Ask" in the top
 * bar, Ctrl/⌘ + K, right-side panel) and the account menu. Every page header gets its "← Back to …" target from here.
 */
export function AppLayout() {
  return (
    <AssistantProvider>
      <Shell />
      <AssistantPanel />
    </AssistantProvider>
  )
}

function Shell() {
  const { can, signOut } = useAuth()
  const user = useCurrentUser()
  const [signingOut, setSigningOut] = useState(false)
  const unread = useUnreadCount()

  const allowed = (items: StaffNavItem[]) => items.filter(item => can(item.capability))
  const workspace = allowed(WORKSPACE_NAV)
  // First run (admins): "Get started" with the number of open set-up steps, until done or hidden.
  const setupVisible = can('admin') && !readFlag(SETUP_HIDDEN_FLAG)
  const setup = useSetupProgress(setupVisible)
  if (setupVisible && setup.open > 0) {
    workspace.unshift({ to: '/welcome', label: 'Get started', icon: ListChecks, capability: 'admin', count: setup.open, countLabel: 'steps left' })
  }
  if (can('account')) workspace.push({ to: '/notifications', label: 'Inbox', icon: Inbox, capability: 'account', count: unread, countLabel: 'unread' })
  const admin = allowed(ADMIN_NAV)
  const nav: NavGroup[] = [
    ...(workspace.length ? [{ label: 'Workspace', items: workspace }] : []),
    ...(admin.length ? [{ label: 'Administration', items: admin }] : []),
  ]

  const name = fullName(user)
  const back = useBackNav()

  return (
    <AppShell
      portal="staff"
      homeHref="/"
      midMode="rail"
      nav={nav}
      sidebarFooter={user.organizationName ?? undefined}
      topbarEnd={
        <>
          {can('account') && <AskButton />}
          {can('account') && (
            <Tooltip content="Notifications" labelOnly>
              <NavLink to="/notifications" className="p-bell" aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}>
                <Icon icon={Bell} size={16} />
                {unread > 0 && <span className="p-bell-count" aria-hidden="true">{unread > 99 ? '99+' : unread}</span>}
              </NavLink>
            </Tooltip>
          )}
          <AccountMenu
            name={name}
            email={user.email}
            detail={[roleLabel(user.role), user.organizationName].filter(Boolean).join(' · ')}
            links={[
              ...(can('account')
                ? [
                    { label: 'Your name', to: '/account/name', icon: UserIcon },
                    { label: 'Change password', to: '/account/password', icon: KeyRound },
                    { label: 'Two-step verification', to: '/account/security', icon: ShieldCheck },
                  ]
                : []),
              { label: `${brand.name} home`, href: appLinks().site, icon: House },
            ]}
            signingOut={signingOut}
            onSignOut={() => {
              setSigningOut(true)
              // Back to this portal's sign-in page (RequireAuth), which shows the notice.
              void signOut(SIGNED_OUT_NOTICE).finally(() => setSigningOut(false))
            }}
          />
        </>
      }
    >
      <BackNavProvider value={back}>
        <Outlet />
      </BackNavProvider>
    </AppShell>
  )
}
