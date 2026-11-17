import { Suspense, useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { authApi, meApi } from '../api/endpoints'
import { errorMessage } from '../api/client'
import { queryKeys } from '../api/queryKeys'
import { useAuth, useCurrentUser } from '../auth/AuthProvider'
import brand from '../design/brand/active'
import { AccountMenu, Alert, AppShell, Button, LoadingRegion, Skeleton, SkeletonText, Tooltip, type NavGroup } from '../design/components'
import {
  BadgeCheck, Bell, BriefcaseBusiness, CalendarClock, FileText, House, Icon, Inbox, LayoutGrid, ListChecks, ScrollText, ShieldCheck, User,
} from '../design/icons'
import { appLinks } from '../design/lib/links'
import { ledgerQuery, workSamplesQuery } from '../lib/queries'
import { readStored, writeStored } from '../lib/storage'
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

/** Navigation counts: results waiting for the candidate's review, and work samples not started yet. */
function useTaskCounts() {
  const ledger = useQuery({ ...ledgerQuery, staleTime: 60_000 })
  const samples = useQuery({ ...workSamplesQuery, staleTime: 60_000 })
  const awaiting = (ledger.data ?? []).reduce((n, claim) => n + claim.verdicts.filter(v => v.candidateStatus === 'AWAITING_CANDIDATE').length, 0)
  const toDo = (samples.data ?? []).filter(s => s.status === 'ASSIGNED' || s.status === 'STARTED').length
  return { awaiting, toDo }
}

const VERIFY_HIDDEN_KEY = 'verify-email-banner-hidden'

/** Generic page skeleton shown while a route's code loads (routes are lazy to keep the first load small). */
export function RouteFallback() {
  return (
    <LoadingRegion label="Loading page" className="c-route-fallback">
      <Skeleton width={220} height={24} />
      <Skeleton width={340} height={12} style={{ marginTop: 12 }} />
      <div style={{ marginTop: 32 }}>
        <SkeletonText lines={4} />
      </div>
    </LoadingRegion>
  )
}

/**
 * Candidate shell: the shared AppShell with the candidate navigation (FLOWS-CANDIDATE §12). Profile, Privacy, the
 * theme and Sign out live in the account menu.
 */
export function Layout() {
  const user = useCurrentUser()
  const { logout } = useAuth()
  const navigate = useNavigate()
  const unread = useUnreadCount()
  const { awaiting, toDo } = useTaskCounts()
  const [signingOut, setSigningOut] = useState(false)
  // Hiding the reminder lasts for this browser session; the Overview checklist keeps the same step.
  const [verifyHidden, setVerifyHidden] = useState(() => readStored(VERIFY_HIDDEN_KEY, 'session') === '1')

  async function signOut() {
    setSigningOut(true)
    await logout()
    // This portal's sign-in page; it shows "You have signed out." (reason 'signed-out').
    navigate('/login', { replace: true })
  }

  const nav: NavGroup[] = [
    {
      items: [
        { to: '/home', label: 'Overview', icon: LayoutGrid },
        { to: '/jobs', label: 'Find jobs', icon: BriefcaseBusiness },
        { to: '/applications', label: 'Applications', icon: FileText },
      ],
    },
    {
      label: 'My evidence',
      items: [
        { to: '/resumes', label: 'Resume', icon: ScrollText },
        { to: '/evidence', label: 'Claims', icon: ShieldCheck },
        { to: '/verification', label: 'Strengthen', icon: BadgeCheck, count: awaiting, countLabel: 'results waiting for your review' },
      ],
    },
    {
      label: 'Tasks',
      items: [
        { to: '/work-samples', label: 'Work samples', icon: ListChecks, count: toDo, countLabel: 'to do' },
        { to: '/interviews', label: 'Interviews', icon: CalendarClock },
      ],
    },
    { items: [{ to: '/notifications', label: 'Inbox', icon: Inbox, count: unread, countLabel: 'unread' }] },
  ]

  const name = `${user.firstName} ${user.lastName}`.trim()

  return (
    <AppShell
      portal="candidate"
      homeHref="/home"
      midMode="drawer"
      nav={nav}
      banner={
        !user.emailVerified && !verifyHidden ? (
          <VerifyEmailBanner
            email={user.email}
            onHide={() => {
              writeStored(VERIFY_HIDDEN_KEY, '1', 'session')
              setVerifyHidden(true)
            }}
          />
        ) : undefined
      }
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
              { label: 'Privacy and data', to: '/privacy', icon: ShieldCheck },
              { label: `${brand.name} home`, href: appLinks().site, icon: House },
            ]}
            onSignOut={() => void signOut()}
            signingOut={signingOut}
          />
        </>
      }
    >
      <Suspense fallback={<RouteFallback />}>
        <Outlet />
      </Suspense>
    </AppShell>
  )
}

function VerifyEmailBanner({ email, onHide }: { email: string; onHide: () => void }) {
  const notify = useNotify()
  const resend = useMutation({
    mutationFn: authApi.resendVerification,
    onSuccess: () => notify('success', 'Verification link sent', { body: `Check ${email}. The link works once and expires after 24 hours.` }),
    onError: error => notify('error', errorMessage(error)),
  })
  return (
    <Alert
      variant="banner"
      tone="warning"
      title="Confirm your email address."
      onDismiss={onHide}
      action={
        <Button size="sm" variant="ghost" loading={resend.isPending} onClick={() => resend.mutate()}>
          Resend link
        </Button>
      }
    >
      Some employers accept applications only from confirmed addresses.
    </Alert>
  )
}
