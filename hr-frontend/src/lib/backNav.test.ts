import { describe, expect, it } from 'vitest'
import type { Role } from '../api/types'
import { can as roleCan, type Capability } from '../auth/permissions'
import { listKey, resolveBack, routeLabel } from './backNav'

const empty = { lists: {}, previous: null }
const as = (role: Role, pathname: string, extra: { state?: unknown; memory?: { lists: Record<string, string>; previous: string | null } } = {}) =>
  resolveBack({ pathname, role, can: (c: Capability) => roleCan(role, c), memory: extra.memory ?? empty, state: extra.state, jobTitle: id => (id === 101 ? 'Senior Backend Engineer' : undefined) })

describe('back navigation: every route in App.tsx has a logical parent or is a top-level destination', () => {
  it('top-level destinations have no back control', () => {
    expect(as('HR_MANAGER', '/jobs')).toBeNull()
    expect(as('INTERVIEWER', '/interviews')).toBeNull()
    expect(as('HR_MANAGER', '/interviews')).toBeNull()
    expect(as('ADMIN', '/admin')).toBeNull()
    expect(as('ADMIN', '/')).toBeNull()
  })

  it('applicant → the job applicant list, with the list state it was opened from', () => {
    expect(as('HR_MANAGER', '/jobs/101/applicants/3014', { state: { back: '/jobs/101?status=SHORTLISTED&sort=appliedAt' } }))
      .toEqual({ to: '/jobs/101?status=SHORTLISTED&sort=appliedAt', label: 'applicants' })
  })

  it('applicant opened from a link (no router state) → the remembered list view, else the applicants tab', () => {
    const memory = { lists: { 'applicants:101': '/jobs/101?status=INTERVIEWING&page=2' }, previous: null }
    expect(as('RECRUITER', '/jobs/101/applicants/3014', { memory })).toEqual({ to: '/jobs/101?status=INTERVIEWING&page=2', label: 'applicants' })
    expect(as('RECRUITER', '/jobs/101/applicants/3014')).toEqual({ to: '/jobs/101?tab=applicants', label: 'applicants' })
  })

  it('ignores a router-state back that points at another job', () => {
    expect(as('HR_MANAGER', '/jobs/101/applicants/3014', { state: { back: '/jobs/999' } })).toEqual({ to: '/jobs/101?tab=applicants', label: 'applicants' })
  })

  it('job (any tab) → jobs list with its filters; new job → jobs; edit → the job', () => {
    const memory = { lists: { jobs: '/jobs?status=DRAFT&q=data' }, previous: null }
    expect(as('HR_MANAGER', '/jobs/101', { memory })).toEqual({ to: '/jobs?status=DRAFT&q=data', label: 'Jobs' })
    expect(as('HR_MANAGER', '/jobs/101')).toEqual({ to: '/jobs', label: 'Jobs' })
    expect(as('HR_MANAGER', '/jobs/new')).toEqual({ to: '/jobs', label: 'Jobs' })
    expect(as('HR_MANAGER', '/jobs/101/edit')).toEqual({ to: '/jobs/101', label: 'Senior Backend Engineer' })
    expect(as('HR_MANAGER', '/jobs/7/edit')).toEqual({ to: '/jobs/7', label: 'job' })
  })

  it('interview → My interviews', () => {
    expect(as('INTERVIEWER', '/interviews/601')).toEqual({ to: '/interviews', label: 'My interviews' })
  })

  it('admin subpages and first run → Overview', () => {
    for (const path of ['/admin/users', '/admin/invitations', '/admin/organization', '/admin/jobs', '/admin/audit', '/admin/fairness', '/admin/verification', '/welcome']) {
      expect(as('ADMIN', path)).toEqual({ to: '/admin', label: 'Overview' })
    }
  })

  it('HR managers reach /admin/verification without the admin overview: back goes to their home (Jobs)', () => {
    expect(as('HR_MANAGER', '/admin/verification')).toEqual({ to: '/jobs', label: 'Jobs' })
  })

  it('account pages, inbox and the assistant page → the previous page, else home', () => {
    const memory = { lists: {}, previous: '/jobs/101?tab=fairness' }
    for (const path of ['/account/password', '/account/security', '/notifications', '/assistant']) {
      expect(as('HR_MANAGER', path, { memory })).toEqual({ to: '/jobs/101?tab=fairness', label: 'Senior Backend Engineer' })
      expect(as('HR_MANAGER', path)).toEqual({ to: '/jobs', label: 'Jobs' })
      expect(as('INTERVIEWER', path)).toEqual({ to: '/interviews', label: 'My interviews' })
      expect(as('ADMIN', path)).toEqual({ to: '/admin', label: 'Overview' })
    }
  })

  it('unknown routes (not found) → home', () => {
    expect(as('RECRUITER', '/nowhere')).toEqual({ to: '/jobs', label: 'Jobs' })
    expect(as('INTERVIEWER', '/jobs/12')).toEqual({ to: '/interviews', label: 'My interviews' })
  })
})

describe('restore points', () => {
  it('remembers the jobs list, a job’s applicant list and my interviews', () => {
    expect(listKey('/jobs', '?status=DRAFT')).toBe('jobs')
    expect(listKey('/jobs/101', '')).toBe('applicants:101')
    expect(listKey('/jobs/101', '?tab=applicants&status=SHORTLISTED')).toBe('applicants:101')
    expect(listKey('/jobs/101', '?tab=details')).toBeNull()
    expect(listKey('/interviews', '')).toBe('interviews')
    expect(listKey('/jobs/101/applicants/3', '')).toBeNull()
  })

  it('labels previous pages by route', () => {
    expect(routeLabel('/admin/audit?from=x')).toBe('Audit log')
    expect(routeLabel('/jobs/101/applicants/3')).toBe('applicant')
    expect(routeLabel('/somewhere')).toBe('previous page')
  })
})
