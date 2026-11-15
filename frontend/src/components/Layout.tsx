import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { authApi, meApi } from '../api/endpoints'
import { errorMessage } from '../api/client'
import { queryKeys } from '../api/queryKeys'
import { useAuth, useCurrentUser } from '../auth/AuthProvider'
import { AccountMenu, Alert, AppShell, Button, Tooltip, type NavGroup } from '../design/components'
import {
  BadgeCheck, Bell, BriefcaseBusiness, CalendarClock, FileText, Icon, Inbox, ListChecks, ScrollText, ShieldCheck, User,
} from '../design/icons'
import { useNotify } from './Notices'

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

/**
 * Candidate shell: the shared design-system AppShell with the candidate navigation. Profile, Privacy, the theme and
 * Sign out live in the account menu. Pages render inside it unchanged (they are restyled screen by screen).
 */
export function Layout() {
  const user = useCurrentUser()
  const { logout } = useAuth()
  const navigate = useNavigate()
  const unread = useUnreadCount()
  const [signingOut, setSigningOut] = useState(false)
  // Hiding the reminder lasts for this page load only (nothing is stored in the browser).
  const [verifyHidden, setVerifyHidden] = useState(false)

  async function signOut() {
    setSigningOut(true)
    await logout()
    navigate('/login', { replace: true })
  }

  const nav: NavGroup[] = [
    {
      items: [
        { to: '/jobs', label: 'Find jobs', icon: BriefcaseBusiness },
        { to: '/applications', label: 'Applications', icon: FileText },
      ],
    },
    {
      label: 'My evidence',
      items: [
        { to: '/resumes', label: 'Resumes', icon: ScrollText },
        { to: '/evidence', label: 'Claims', icon: ShieldCheck },
        { to: '/verification', label: 'Verification', icon: BadgeCheck },
      ],
    },
    {
      label: 'Tasks',
      items: [
        { to: '/work-samples', label: 'Work samples', icon: ListChecks },
        { to: '/interviews', label: 'Interviews', icon: CalendarClock },
      ],
    },
    { items: [{ to: '/notifications', label: 'Inbox', icon: Inbox, count: unread, countLabel: 'unread' }] },
  ]

  const name = `${user.firstName} ${user.lastName}`.trim()

  return (
    <AppShell
      portal="candidate"
      homeHref="/jobs"
      midMode="drawer"
      nav={nav}
      banner={!user.emailVerified && !verifyHidden ? <VerifyEmailBanner email={user.email} onHide={() => setVerifyHidden(true)} /> : undefined}
      topbarEnd={
        <>
          <Tooltip content="Notifications" labelOnly>
            <NavLink to="/notifications" className="p-bell" aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}>
              <Icon icon={Bell} size={16} />
              {unread > 0 && (
                <span className="p-bell-count" aria-hidden="true">
                  {unread > 99 ? '99+' : unread}
                </span>
              )}
            </NavLink>
          </Tooltip>
          <AccountMenu
            name={name || user.email}
            email={user.email}
            detail="Candidate"
            links={[
              { label: 'Profile', to: '/profile', icon: User },
              { label: 'Privacy', to: '/privacy', icon: ShieldCheck },
            ]}
            onSignOut={() => void signOut()}
            signingOut={signingOut}
          />
        </>
      }
    >
      <Outlet />
    </AppShell>
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
    <Alert
      variant="banner"
      tone="warning"
      title="Please verify your email address."
      onDismiss={onHide}
      action={
        <Button size="sm" variant="ghost" loading={resend.isPending} onClick={() => resend.mutate()}>
          Resend link
        </Button>
      }
    >
      Some employers accept applications only from verified addresses.
    </Alert>
  )
}
