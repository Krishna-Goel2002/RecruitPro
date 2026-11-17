import { describe, expect, it } from 'vitest'
import { absoluteAppUrl } from './appUrl'

describe('absoluteAppUrl', () => {
  it('keeps the portal base path behind the gateway', () => {
    expect(absoluteAppUrl('/jobs/7', '/hr/', 'http://localhost:8088')).toBe('http://localhost:8088/hr/jobs/7')
    expect(absoluteAppUrl('/jobs/7?tab=applicants', '/hr/', 'http://localhost:8088')).toBe('http://localhost:8088/hr/jobs/7?tab=applicants')
  })

  it('works on its own port (base /)', () => {
    expect(absoluteAppUrl('/jobs/7', '/', 'http://localhost:5174')).toBe('http://localhost:5174/jobs/7')
  })
})
