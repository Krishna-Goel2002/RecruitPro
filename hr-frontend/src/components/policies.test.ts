import { describe, expect, it } from 'vitest'
import type { AppNotification } from '../api/types'
import { notificationTarget } from '../pages/account/NotificationsPage'
import { safeHttpUrl } from '../lib/format'
import { passwordError } from './PasswordChecklist'
import { normalizeDomain, domainProblem } from './DomainListInput'

describe('links from server data', () => {
  it('only http(s) URLs become links', () => {
    expect(safeHttpUrl('https://example.test/a')).toBe('https://example.test/a')
    expect(safeHttpUrl('http://example.test')).toBe('http://example.test/')
    expect(safeHttpUrl('javascript:alert(1)')).toBeNull()
    expect(safeHttpUrl('JAVASCRIPT:alert(1)')).toBeNull()
    expect(safeHttpUrl('data:text/html,<script>alert(1)</script>')).toBeNull()
    expect(safeHttpUrl('vbscript:x')).toBeNull()
    expect(safeHttpUrl('//evil.test')).toBeNull()
    expect(safeHttpUrl('not a url')).toBeNull()
    expect(safeHttpUrl(null)).toBeNull()
  })

  it('notification links stay inside the app', () => {
    const n = (link?: string, template = 'INTERVIEW_ASSIGNED') => ({ id: 1, template, subject: 's', body: 'b', createdAt: '', readAt: null, link } as unknown as AppNotification)
    expect(notificationTarget(n('/interviews/5'))).toBe('/interviews/5')
    expect(notificationTarget(n('//evil.test/x'))).toBe('/interviews')
    expect(notificationTarget(n('/\\evil.test'))).toBe('/interviews')
    expect(notificationTarget(n('https://evil.test'))).toBe('/interviews')
    expect(notificationTarget(n('javascript:alert(1)'))).toBe('/interviews')
    expect(notificationTarget(n(undefined, 'UNKNOWN'))).toBeNull()
  })
})

describe('password policy mirrors the server (PasswordPolicy)', () => {
  it('needs 10+ characters, a letter and a digit, at most 72 bytes', () => {
    expect(passwordError('short1')).toMatch(/10 characters/)
    expect(passwordError('onlyletters')).toMatch(/digit/)
    expect(passwordError('1234567890')).toMatch(/letter/)
    expect(passwordError('ünïcödé123')).toBeNull()
    expect(passwordError('पासवर्ड१२३४५६')).toBeNull()
    expect(passwordError('a1' + 'é'.repeat(40))).toMatch(/72 bytes/)
  })

  it('a letter outside the basic plane counts (the server checks code points since R6-B7)', () => {
    expect(passwordError('𝐀𝐁𝐂𝐃𝐄1234567')).toBeNull()
    expect(passwordError('😀😀😀😀😀1234567')).toMatch(/letter/)
  })
})

describe('trusted domain input', () => {
  it('normalises and validates like the server', () => {
    expect(normalizeDomain('https://www.Globex.example/careers?x=1')).toBe('globex.example')
    expect(normalizeDomain('hr@acme.test')).toBe('acme.test')
    expect(domainProblem('acme')).toBeTruthy()
    expect(domainProblem('co.uk')).toMatch(/public suffix/)
    expect(domainProblem('acme.co.uk')).toBeNull()
  })
})
