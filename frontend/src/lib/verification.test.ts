import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import {
  OAUTH_ERROR_TEXT, candidateStatusLabel, challengeSteps, isOAuthNotConfigured, isRunActive, oauthErrorMessage, ownershipMethodsFor,
  runErrorText, shortHash, sourceKindLabel, verdictInfo,
} from './verification'

import { verificationErrorMessage } from '../pages/verification/shared'
describe('verdictInfo', () => {
  it('maps every server verdict to a label, a tone and a non-colour symbol', () => {
    expect(verdictInfo('VERIFIED')).toMatchObject({ label: 'Verified', tone: 'positive' })
    expect(verdictInfo('PARTLY_VERIFIED')).toMatchObject({ label: 'Partly verified', tone: 'partial' })
    // Principle 3: "not found" is neutral and always says "in connected sources".
    expect(verdictInfo('NOT_FOUND')).toMatchObject({ label: 'Not found in connected sources', tone: 'neutral' })
    expect(verdictInfo('CONTRADICTED')).toMatchObject({ label: 'Contradicted', tone: 'negative' })
    expect(verdictInfo('NOT_CHECKABLE')).toMatchObject({ tone: 'neutral' })
    for (const v of ['VERIFIED', 'PARTLY_VERIFIED', 'NOT_FOUND', 'CONTRADICTED', 'NOT_CHECKABLE']) expect(verdictInfo(v).symbol).not.toBe('')
  })

  it('degrades gracefully for an unknown or missing verdict', () => {
    expect(verdictInfo('NEW_VERDICT')).toMatchObject({ label: 'New verdict', tone: 'neutral' })
    expect(verdictInfo(null)).toMatchObject({ label: 'Unknown result' })
  })
})

describe('OAuth return codes', () => {
  it('has a sentence for every code the callback can send (GitHubOAuthService)', () => {
    for (const code of [
      'OAUTH_NOT_CONFIGURED', 'STATE_INVALID', 'STATE_USED', 'STATE_EXPIRED', 'BROWSER_MISMATCH', 'ACCESS_DENIED', 'CODE_MISSING',
      'CODE_REJECTED', 'GITHUB_UNAVAILABLE', 'CONNECT_FAILED', 'RATE_LIMITED',
    ]) {
      expect(OAUTH_ERROR_TEXT[code], code).toBeTruthy()
      expect(oauthErrorMessage(code)).toBe(OAUTH_ERROR_TEXT[code])
    }
    expect(oauthErrorMessage(' access_denied ')).toBe(OAUTH_ERROR_TEXT.ACCESS_DENIED)
  })

  it('never echoes an arbitrary query value', () => {
    const message = oauthErrorMessage('<img src=x onerror=alert(1)>'.repeat(5))
    expect(message).not.toMatch(/[<>=]/)
    expect(message).toContain('(imgsrcxonerroralert1')
    expect(message.length).toBeLessThan(110)
  })
})

describe('isOAuthNotConfigured', () => {
  it('is true only for the OAUTH_NOT_CONFIGURED answer, not for any 503', () => {
    expect(isOAuthNotConfigured(new ApiError('OAUTH_NOT_CONFIGURED: connecting GitHub is not configured on this server', 503))).toBe(true)
    expect(isOAuthNotConfigured({ status: 503, message: 'OAUTH_NOT_CONFIGURED' })).toBe(true)
    expect(isOAuthNotConfigured(new ApiError('Something went wrong on our side. Please try again shortly.', 503))).toBe(false)
    expect(isOAuthNotConfigured(new ApiError('The verification service is unavailable', 503))).toBe(false)
  })
})

describe('challengeSteps (protocol constants must not change)', () => {
  it('asks code hosts for the code in the profile bio', () => {
    const [step] = challengeSteps('GITHUB', 'octocat', 'rp-abc')
    expect(step.method).toBe('PROFILE_BIO_CODE')
    expect(step.copy).toEqual([{ label: 'Code', value: 'rp-abc' }])
    expect(challengeSteps('GITLAB', 'octocat', 'rp-abc')[0].title).toMatch(/GitLab/)
  })

  it('gives websites the DNS TXT value, the well-known file and the meta tag on the exact host', () => {
    const steps = challengeSteps('WEBSITE', 'https://meera.dev/portfolio', 'rp-XYZ')
    expect(steps.map(s => s.method)).toEqual(['DNS_TXT', 'WELL_KNOWN_FILE', 'PAGE_CODE'])
    expect(steps[0].copy).toEqual([
      { label: 'Host', value: 'meera.dev' },
      { label: 'TXT value', value: 'recruitpro-verification=rp-XYZ' },
    ])
    expect(steps[1].copy[0].value).toBe('https://meera.dev/.well-known/recruitpro-verification.txt')
    expect(steps[2].copy[1].value).toBe('<meta name="recruitpro-verification" content="rp-XYZ">')
  })

  it('has no proof for documents and badges', () => {
    expect(challengeSteps('DOCUMENT', 'cert.pdf', 'rp-1')).toEqual([])
    expect(ownershipMethodsFor('OPEN_BADGE')).toEqual([])
    expect(ownershipMethodsFor('WEBSITE')).toEqual(['DNS_TXT', 'WELL_KNOWN_FILE', 'PAGE_CODE'])
  })
})

describe('labels', () => {
  it('describes runs and their errors in plain words', () => {
    expect(isRunActive('PENDING')).toBe(true)
    expect(isRunActive('RUNNING')).toBe(true)
    expect(isRunActive('COMPLETED')).toBe(false)
    expect(runErrorText('SOURCE_REVOKED')).toMatch(/revoked/)
    expect(runErrorText('SOMETHING_NEW')).toBe('The check did not finish (SOMETHING_NEW). Please try again later.')
    expect(runErrorText(null)).toMatch(/did not finish/)
  })

  it('labels kinds, statuses and hashes', () => {
    expect(sourceKindLabel('OPEN_BADGE')).toBe('Open Badge')
    expect(sourceKindLabel('REFERENCE')).toBe('Reference')
    expect(sourceKindLabel(undefined)).toBe('Source')
    expect(candidateStatusLabel('AWAITING_CANDIDATE')).toBe('Awaiting your review')
    expect(shortHash('sha256:0123456789abcdef')).toBe('0123456789ab…')
  })
})

describe('verificationErrorMessage (integration pass: 409 business caps, 429 with Retry-After)', () => {
  it('names the coded 409 caps and keeps the old wording for un-coded conflicts', () => {
    expect(verificationErrorMessage(new ApiError('REFERENCE_LIMIT: You can have at most 5 open reference requests', 409), 'reference')).toMatch(/already have 5 open reference requests/)
    expect(verificationErrorMessage(new ApiError('SOURCE_LIMIT: You can connect at most 30 sources.', 409), 'connect')).toMatch(/at most 30 sources/)
    expect(verificationErrorMessage(new ApiError('Already connected', 409), 'connect')).toMatch(/already connected/)
    expect(verificationErrorMessage(new ApiError('Busy', 409), 'run')).toMatch(/already running/)
  })

  it('uses Retry-After for the rate limits', () => {
    expect(verificationErrorMessage(new ApiError('Too many', 429, [], { retryAfterSeconds: 7200 }), 'reference')).toBe('You can send at most 10 reference requests in 24 hours. Please try again in about 2 hours.')
    expect(verificationErrorMessage(new ApiError('Too many', 429), 'reference')).toMatch(/try again tomorrow/)
    expect(verificationErrorMessage(new ApiError('You started connecting GitHub too often.', 429, [], { retryAfterSeconds: 600 }), 'connect')).toBe('You started connecting GitHub too often. Please wait about 10 minutes and try again.')
    expect(verificationErrorMessage(new ApiError('Too many', 429, [], { retryAfterSeconds: 30 }), 'run')).toMatch(/in about a minute/)
  })
})
