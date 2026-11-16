import { useEffect, useMemo } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useLocation } from 'react-router-dom'
import { qk } from '../api/queryKeys'
import type { JobStaff, Role } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { homePath, type Capability } from '../auth/permissions'
import type { BackTarget } from '../design/components'

/**
 * Back navigation (HR-UX §2): every page below a top-level destination gets "← Back to <parent>", where the parent is
 * the LOGICAL parent (not browser history), restored with the list state the user left it in:
 *
 *   /jobs/:id/applicants/:aid  → the job's applicant list, with its view, sort and page as last seen
 *   /jobs/:id (any tab)         → /jobs with the status tab, search and page as last seen
 *   /jobs/new, /jobs/:id/edit   → /jobs, or the job
 *   /interviews/:id             → My interviews
 *   /admin/* and /welcome       → Overview (admins); /admin/verification for HR managers → their home
 *   /notifications, /account/*, /assistant → the previous in-app page, else home
 *   unknown routes              → home
 *
 * Top-level destinations (/jobs, /interviews, /admin) have no back control.
 */

const MEMORY_KEY = 'rp-back-memory'
type Memory = { lists: Record<string, string>; previous: string | null }

function readMemory(): Memory {
  try {
    const raw = window.sessionStorage.getItem(MEMORY_KEY)
    const parsed = raw ? (JSON.parse(raw) as Partial<Memory>) : null
    return { lists: parsed?.lists && typeof parsed.lists === 'object' ? parsed.lists : {}, previous: typeof parsed?.previous === 'string' ? parsed.previous : null }
  } catch {
    return { lists: {}, previous: null }
  }
}

function writeMemory(m: Memory) {
  try {
    window.sessionStorage.setItem(MEMORY_KEY, JSON.stringify(m))
  } catch {
    /* storage unavailable: back still works, without the remembered filters */
  }
}

/** Pages that "go back to where you were" rather than to a fixed parent. */
const DETOURS = [/^\/notifications$/, /^\/account\//, /^\/assistant$/]
const isDetour = (path: string) => DETOURS.some(re => re.test(path))

/** The list key a URL is remembered under (its parent's restore point), or null. */
export function listKey(pathname: string, search: string): string | null {
  if (pathname === '/jobs') return 'jobs'
  const job = /^\/jobs\/(\d+)$/.exec(pathname)
  if (job) {
    const tab = new URLSearchParams(search).get('tab') ?? 'applicants'
    return tab === 'applicants' ? `applicants:${job[1]}` : null
  }
  if (pathname === '/interviews') return 'interviews'
  return null
}

/** Remember list URLs and the last non-detour page; call on every location change. */
export function rememberLocation(pathname: string, search: string) {
  const m = readMemory()
  const key = listKey(pathname, search)
  if (key) m.lists[key] = `${pathname}${search}`
  if (!isDetour(pathname)) m.previous = `${pathname}${search}`
  writeMemory(m)
}

/** Short names for "Back to …" when the target is a previous page. */
export function routeLabel(path: string, jobTitle?: (id: number) => string | undefined): string {
  const pathname = path.split('?')[0]
  if (pathname === '/jobs') return 'Jobs'
  if (pathname === '/jobs/new') return 'New job'
  const applicant = /^\/jobs\/(\d+)\/applicants\/\d+$/.exec(pathname)
  if (applicant) return 'applicant'
  const job = /^\/jobs\/(\d+)(\/edit)?$/.exec(pathname)
  if (job) return jobTitle?.(Number(job[1])) ?? 'job'
  if (pathname === '/interviews') return 'My interviews'
  if (/^\/interviews\/\d+$/.test(pathname)) return 'interview'
  const admin: Record<string, string> = {
    '/admin': 'Overview',
    '/admin/users': 'Staff accounts',
    '/admin/invitations': 'Invitations',
    '/admin/organization': 'Organisation',
    '/admin/jobs': 'Job oversight',
    '/admin/audit': 'Audit log',
    '/admin/fairness': 'Fairness',
    '/admin/verification': 'Verification',
    '/welcome': 'Get started',
    '/notifications': 'Inbox',
    '/assistant': 'Assistant',
  }
  return admin[pathname] ?? 'previous page'
}

export interface BackInput {
  pathname: string
  state?: unknown
  role: Role
  can: (c: Capability) => boolean
  /** Remembered list URLs and the last non-detour page (defaults to session storage). */
  memory?: Memory
  jobTitle?: (id: number) => string | undefined
}

/** The logical parent of a route, or null for a top-level destination. Pure, so every route is unit-tested. */
export function resolveBack({ pathname, state, role, can, memory = readMemory(), jobTitle }: BackInput): BackTarget | null {
  const home = homePath(role)
  const homeTarget = (): BackTarget => ({ to: home, label: routeLabel(home, jobTitle) })
  const list = (key: string, fallback: string) => memory.lists[key] ?? fallback

  if (pathname === '/' || pathname === '/jobs' || pathname === '/interviews' || pathname === '/admin') return null
  if (pathname === home) return null

  const applicant = /^\/jobs\/(\d+)\/applicants\/\d+\/?$/.exec(pathname)
  if (applicant) {
    const jobId = applicant[1]
    const fromState = (state as { back?: unknown } | null)?.back
    const to = typeof fromState === 'string' && fromState.startsWith(`/jobs/${jobId}`) ? fromState : list(`applicants:${jobId}`, `/jobs/${jobId}?tab=applicants`)
    return { to, label: 'applicants' }
  }
  if (pathname === '/jobs/new') return { to: list('jobs', '/jobs'), label: 'Jobs' }
  const edit = /^\/jobs\/(\d+)\/edit\/?$/.exec(pathname)
  if (edit) return { to: `/jobs/${edit[1]}`, label: jobTitle?.(Number(edit[1])) ?? 'job' }
  if (/^\/jobs\/\d+\/?$/.test(pathname)) return can('jobs.view') ? { to: list('jobs', '/jobs'), label: 'Jobs' } : homeTarget()

  if (/^\/interviews\/\d+\/?$/.test(pathname)) return { to: list('interviews', '/interviews'), label: 'My interviews' }

  if (pathname === '/welcome' || pathname.startsWith('/admin/')) {
    return can('admin') ? { to: '/admin', label: 'Overview' } : homeTarget()
  }

  if (isDetour(pathname)) {
    const previous = memory.previous
    if (previous && previous.split('?')[0] !== pathname) return { to: previous, label: routeLabel(previous, jobTitle) }
    return homeTarget()
  }

  // Unknown route (not found): home.
  return homeTarget()
}

/** Resolves the back target for the current location and keeps the restore points up to date. */
export function useBackNav(): BackTarget | null {
  const location = useLocation()
  const { user, can } = useAuth()
  const queryClient = useQueryClient()
  // Resolve against the memory BEFORE this location is recorded (a detour must not point at itself).
  const target = useMemo(() => {
    if (!user) return null
    return resolveBack({
      pathname: location.pathname,
      state: location.state,
      role: user.role,
      can,
      jobTitle: id => queryClient.getQueryData<JobStaff>(qk.job(id))?.title,
    })
    // location.key changes on every navigation, including state-only ones.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.key, location.pathname, user, can])
  useEffect(() => {
    rememberLocation(location.pathname, location.search)
  }, [location.pathname, location.search])
  return target
}
