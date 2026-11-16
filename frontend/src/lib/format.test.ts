import { describe, expect, it } from 'vitest'
import type { AppNotification, Application, CandidateWorkSample } from '../api/types'
import { escapeIcsText, foldIcsLine, interviewIcs } from './calendar'
import { calendarDaysUntil, closesIn, describeDuration, formatDuration, formatSalaryRange } from './format'
import { evidenceFrom, isCovered, matchRequirements } from './match'
import { nextStepFor } from './nextStep'
import { bestTitleMatch, notificationTarget } from './notificationLink'
import { isHttpUrl, isHttpsUrl, parseId, safeHref } from './url'

const local = (y: number, m: number, d: number, h = 0, min = 0) => new Date(y, m - 1, d, h, min)

describe('closesIn (local calendar days)', () => {
  const now = local(2026, 11, 16, 9, 0)
  it('says "today" for a deadline later today, not "tomorrow"', () => {
    expect(closesIn(local(2026, 11, 16, 18, 0), now)).toBe('Closes today')
    expect(closesIn(local(2026, 11, 17, 1, 0), now)).toBe('Closes tomorrow')
    expect(closesIn(local(2026, 11, 20, 12, 0), now)).toBe('Closes in 4 days')
    expect(closesIn(local(2026, 12, 14, 12, 0), now)).toBe('Closes in 4 weeks')
  })
  it('says "Closed" once the deadline has passed, even on the same day', () => {
    expect(closesIn(local(2026, 11, 16, 8, 59), now)).toBe('Closed')
    expect(closesIn(local(2026, 11, 10), now)).toBe('Closed')
  })
  it('counts calendar days', () => {
    expect(calendarDaysUntil(local(2026, 11, 17, 0, 1), local(2026, 11, 16, 23, 59))).toBe(1)
    expect(calendarDaysUntil(local(2026, 11, 16, 23, 59), local(2026, 11, 16, 0, 1))).toBe(0)
  })
})

describe('numbers and durations', () => {
  it('formats salary ranges in rupees', () => {
    expect(formatSalaryRange(null, null)).toBe('Salary not listed')
    expect(formatSalaryRange(1200000, 1800000)).toBe('₹12,00,000 – ₹18,00,000')
    expect(formatSalaryRange(500000, null)).toBe('From ₹5,00,000')
    expect(formatSalaryRange(null, 900000)).toBe('Up to ₹9,00,000')
  })
  it('formats the work-sample timer', () => {
    expect(formatDuration(5400)).toBe('1:30:00')
    expect(formatDuration(249)).toBe('4:09')
    expect(formatDuration(-5)).toBe('0:00')
    expect(describeDuration(725)).toBe('12 minutes 5 seconds')
    expect(describeDuration(3600)).toBe('1 hour')
    expect(describeDuration(0)).toBe('0 seconds')
  })
})

describe('urls and ids', () => {
  it('only allows http(s) links in hrefs', () => {
    expect(isHttpUrl('https://github.com/x')).toBe(true)
    expect(isHttpUrl('javascript:alert(1)')).toBe(false)
    expect(isHttpUrl('data:text/html,hi')).toBe(false)
    expect(isHttpUrl('/relative')).toBe(false)
    expect(safeHref('JAVASCRIPT:alert(1)')).toBeUndefined()
    expect(safeHref('http://a.example/x')).toBe('http://a.example/x')
    expect(safeHref(null)).toBeUndefined()
    expect(isHttpsUrl('http://github.com/login/oauth')).toBe(false)
    expect(isHttpsUrl('https://github.com/login/oauth')).toBe(true)
  })
  it('parses ids from the URL strictly', () => {
    expect(parseId('42')).toBe(42)
    expect(parseId('0')).toBeNull()
    expect(parseId('-1')).toBeNull()
    expect(parseId('1.5')).toBeNull()
    expect(parseId('abc')).toBeNull()
    expect(parseId('1e3')).toBeNull()
    expect(parseId(null)).toBeNull()
  })
})

describe('calendar file', () => {
  it('escapes RFC 5545 text, including CRLF', () => {
    expect(escapeIcsText('Room 4; floor 2, Pune\r\nGate B\\C')).toBe('Room 4\\; floor 2\\, Pune\\nGate B\\\\C')
  })
  it('folds long lines at 75 octets', () => {
    const folded = foldIcsLine(`LOCATION:${'x'.repeat(200)}`)
    for (const line of folded.split('\r\n')) expect(new TextEncoder().encode(line).length).toBeLessThanOrEqual(75)
    expect(folded.split('\r\n').slice(1).every(l => l.startsWith(' '))).toBe(true)
    expect(folded.replace(/\r\n /g, '')).toBe(`LOCATION:${'x'.repeat(200)}`)
  })
  it('writes UTC start and end', () => {
    const ics = interviewIcs({ id: 5, scheduledAt: '2026-11-19T04:45:00Z', durationMinutes: 45, location: 'https://meet.example/abc' }, 'Backend, Pune', new Date('2026-11-16T00:00:00Z'))
    expect(ics).toContain('DTSTART:20261119T044500Z')
    expect(ics).toContain('DTEND:20261119T053000Z')
    expect(ics).toContain('SUMMARY:Interview: Backend\\, Pune')
    expect(ics.endsWith('END:VCALENDAR\r\n')).toBe(true)
  })
})

describe('notification links', () => {
  const apps = [
    { id: 1, jobTitle: 'Backend Engineer' },
    { id: 2, jobTitle: 'Senior Backend Engineer' },
  ] as Application[]
  const n = (template: string, subject: string): AppNotification => ({ id: 1, template, subject, body: '', createdAt: '2026-11-16T10:00:00Z', readAt: null })

  it('prefers the exact title, then the longest contained title', () => {
    expect(bestTitleMatch(apps, a => a.jobTitle, 'Senior Backend Engineer')?.id).toBe(2)
    expect(notificationTarget(n('APPLICATION_STATUS_CHANGED', 'Update on your application for Senior Backend Engineer'), apps).to).toBe('/applications/2')
    expect(notificationTarget(n('APPLICATION_RECEIVED', 'We received your application for Backend Engineer'), apps).to).toBe('/applications/1')
    expect(notificationTarget(n('APPLICATION_RECEIVED', 'We received your application for Designer'), apps).to).toBe('/applications')
  })

  it('links work samples, interviews and verification', () => {
    const samples = [{ assignmentId: 9, title: 'Refunds API' }] as CandidateWorkSample[]
    expect(notificationTarget(n('WORK_SAMPLE_ASSIGNED', 'A work sample is waiting for you: Refunds API'), [], samples).to).toBe('/work-samples/9')
    expect(notificationTarget(n('INTERVIEW_SCHEDULED', 'Interview scheduled for X'), []).to).toBe('/interviews')
    expect(notificationTarget(n('VERIFICATION_READY', 'Your verification results are ready'), []).to).toBe('/verification?review=first')
    expect(notificationTarget(n('SOMETHING_ELSE', 'Hi'), []).to).toBeNull()
  })
})

describe('next step and match estimate', () => {
  const sample = (status: string, extra: Partial<CandidateWorkSample> = {}) =>
    ({ assignmentId: 3, jobTitle: 'Dev', title: 'Task', status, timeLimitMinutes: 90, dueAt: null, deadlineAt: null, ...extra }) as CandidateWorkSample

  it('puts a started work sample first, then an assigned one, then the next interview', () => {
    const now = Date.parse('2026-11-16T10:00:00Z')
    expect(nextStepFor({ status: 'SHORTLISTED', jobTitle: 'Dev' }, [sample('ASSIGNED'), sample('STARTED')], [], now).kind).toBe('work-sample-started')
    expect(nextStepFor({ status: 'SHORTLISTED', jobTitle: 'Dev' }, [sample('ASSIGNED')], [], now).to).toBe('/work-samples/3')
    const interview = { id: 1, jobTitle: 'Dev', scheduledAt: '2026-11-19T04:45:00Z', durationMinutes: 45, location: null, status: 'SCHEDULED' }
    expect(nextStepFor({ status: 'INTERVIEWING', jobTitle: 'Dev' }, [], [interview], now).kind).toBe('interview')
    expect(nextStepFor({ status: 'WITHDRAWN', jobTitle: 'Dev' }, [sample('ASSIGNED')], [], now).kind).toBe('closed')
    expect(nextStepFor({ status: 'SUBMITTED', jobTitle: 'Dev' }, [], [], now).label).toBe('In blind review with the employer')
  })

  it('matches whole words and keeps the best tier', () => {
    const req = { id: 1, type: 'SKILL', description: 'Java', mandatory: true, knockout: false, minimumYears: null, alternatives: ['Kotlin'], proficiency: null } as const
    const evidence = evidenceFrom(
      [{ id: 1, type: 'SKILL', value: 'JavaScript', extractedValue: 'JavaScript', evidence: null, sourceSection: null, tier: 'RESUME_STATED' }],
      [{ id: 2, claimType: 'SKILL', value: 'Kotlin', evidenceUrl: null, tier: 'SELF_CLAIMED', verifiedAt: null, verificationNote: null, createdAt: '' }],
    )
    const [match] = matchRequirements([{ ...req, alternatives: [...req.alternatives] }], evidence)
    expect(match.matched).toBe('Kotlin')
    expect(match.tier).toBe('SELF_CLAIMED')
    expect(isCovered(match)).toBe(false)
  })
})
