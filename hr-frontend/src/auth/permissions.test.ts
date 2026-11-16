import { describe, expect, it } from 'vitest'
import type { Role } from '../api/types'
import { can, homePath, isStaff, type Capability } from './permissions'

const ROLES: Role[] = ['ADMIN', 'HR_MANAGER', 'RECRUITER', 'INTERVIEWER']

/** The server's @PreAuthorize rules, written out independently (backend controllers, Kshitij-redesign). */
const EXPECTED: Record<Capability, Role[]> = {
  'jobs.view': ['ADMIN', 'HR_MANAGER', 'RECRUITER'],
  'jobs.manage': ['ADMIN', 'HR_MANAGER'],
  'applicants.decide': ['ADMIN', 'HR_MANAGER'],
  'applicants.reveal': ['ADMIN', 'HR_MANAGER'],
  'applicants.comment': ['ADMIN', 'HR_MANAGER', 'RECRUITER'],
  'applicants.verifyClaims': ['ADMIN', 'HR_MANAGER', 'RECRUITER'],
  'applicants.downloadResume': ['ADMIN', 'HR_MANAGER', 'RECRUITER'],
  'applicants.retryAnalysis': ['ADMIN', 'HR_MANAGER'],
  'applicants.rematch': ['ADMIN', 'HR_MANAGER'],
  'workSamples.manage': ['ADMIN', 'HR_MANAGER'],
  'workSamples.score': ['ADMIN', 'HR_MANAGER', 'RECRUITER'],
  'workSamples.cancel': ['ADMIN', 'HR_MANAGER'],
  'workSamples.downloadFiles': ['ADMIN', 'HR_MANAGER', 'RECRUITER'],
  'interviewKit.manage': ['ADMIN', 'HR_MANAGER'],
  'interviews.schedule': ['ADMIN', 'HR_MANAGER'],
  'interviews.view': ['ADMIN', 'HR_MANAGER', 'RECRUITER', 'INTERVIEWER'],
  'fairness.job': ['ADMIN', 'HR_MANAGER'],
  'audit.jobExport': ['ADMIN', 'HR_MANAGER'],
  'verification.view': ['ADMIN', 'HR_MANAGER', 'RECRUITER'],
  'verification.review': ['ADMIN', 'HR_MANAGER', 'RECRUITER'],
  'verification.queue': ['ADMIN', 'HR_MANAGER'],
  'verification.settings': ['ADMIN'],
  admin: ['ADMIN'],
  account: ['ADMIN', 'HR_MANAGER', 'RECRUITER', 'INTERVIEWER'],
}

describe('permissions matrix', () => {
  for (const [capability, allowed] of Object.entries(EXPECTED) as [Capability, Role[]][]) {
    it(`${capability}: ${allowed.join(', ')}`, () => {
      for (const role of ROLES) expect(can(role, capability)).toBe(allowed.includes(role))
      expect(can('CANDIDATE', capability)).toBe(false)
      expect(can(null, capability)).toBe(false)
      expect(can(undefined, capability)).toBe(false)
    })
  }

  it('only staff roles are staff', () => {
    expect(ROLES.every(isStaff)).toBe(true)
    expect(isStaff('CANDIDATE')).toBe(false)
    expect(isStaff(null)).toBe(false)
  })

  it('lands each role on a page it may open', () => {
    expect(homePath('ADMIN')).toBe('/admin')
    expect(homePath('HR_MANAGER')).toBe('/jobs')
    expect(homePath('RECRUITER')).toBe('/jobs')
    expect(homePath('INTERVIEWER')).toBe('/interviews')
  })
})
