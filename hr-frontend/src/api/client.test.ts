import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError, PORTAL, PORTAL_HEADER, api, downloadFile, errorMessage, filenameFromDisposition, hasAccessToken, isLockout, onSessionChange,
  parseRetryAfter, setSession, splitCodedMessage, waitText,
} from './client'
import type { AuthResponse } from './types'
import { adminApi } from './admin'

const session = (token: string): AuthResponse => ({
  accessToken: token,
  tokenType: 'Bearer',
  expiresIn: 900,
  user: { id: 1, email: 'a@example.test', firstName: 'A', lastName: 'B', role: 'HR_MANAGER', emailVerified: true },
} as unknown as AuthResponse)

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const ok = (data: unknown) => json(200, { success: true, message: 'ok', data })

type Call = { url: string; init: RequestInit }
let calls: Call[]
/** Resolves with the ApiError a request rejected with (and fails the test if it succeeded). */
const fail = (request: Promise<unknown>): Promise<ApiError> => request.then(() => { throw new Error('expected the request to fail') }, (cause: ApiError) => cause)
let fetchMock: ReturnType<typeof vi.fn>

function header(init: RequestInit, name: string): string | undefined {
  return (init.headers as Record<string, string> | undefined)?.[name]
}

beforeEach(() => {
  calls = []
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', (url: string, init: RequestInit) => {
    calls.push({ url, init })
    return fetchMock(url, init)
  })
})
afterEach(() => {
  vi.unstubAllGlobals()
  setSession(null)
})

describe('api client', () => {
  it('sends the portal header and the bearer token on every call, including the refresh', async () => {
    setSession(session('old'))
    fetchMock.mockImplementation(async (url: string, init: RequestInit) => {
      if (url.endsWith('/auth/refresh')) return ok(session('new'))
      return header(init, 'Authorization') === 'Bearer new' ? ok({ id: 1 }) : json(401, { success: false, message: 'expired' })
    })
    await expect(api.get('/jobs/1')).resolves.toEqual({ id: 1 })
    expect(calls).toHaveLength(3)
    for (const call of calls) expect(header(call.init, PORTAL_HEADER)).toBe(PORTAL)
    for (const call of calls) expect(call.init.credentials).toBe('include')
    expect(header(calls[0].init, 'Authorization')).toBe('Bearer old')
  })

  it('refreshes once for concurrent 401s (single flight) and retries each request once', async () => {
    setSession(session('old'))
    let refreshes = 0
    fetchMock.mockImplementation(async (url: string, init: RequestInit) => {
      if (url.endsWith('/auth/refresh')) {
        refreshes += 1
        await new Promise(resolve => setTimeout(resolve, 10))
        return ok(session('new'))
      }
      return header(init, 'Authorization') === 'Bearer new' ? ok(url) : json(401, { success: false, message: 'expired' })
    })
    const results = await Promise.all([api.get('/a'), api.get('/b'), api.get('/c')])
    expect(refreshes).toBe(1)
    expect(results.map(r => String(r).split('/').pop())).toEqual(['a', 'b', 'c'])
  })

  it('ends the session when the refresh is rejected, and tells listeners', async () => {
    setSession(session('old'))
    const seen: (AuthResponse | null)[] = []
    const off = onSessionChange(auth => seen.push(auth))
    fetchMock.mockImplementation(async (url: string) => (url.endsWith('/auth/refresh') ? json(401, { success: false, message: 'no' }) : json(401, { success: false, message: 'expired' })))
    const error = await fail(api.get('/jobs'))
    off()
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(401)
    expect(hasAccessToken()).toBe(false)
    expect(seen).toContain(null)
  })

  it('keeps the session when the refresh endpoint is unreachable (network, 5xx)', async () => {
    setSession(session('old'))
    fetchMock.mockImplementation(async (url: string) => (url.endsWith('/auth/refresh') ? json(503, { success: false, message: 'down' }) : json(401, { success: false, message: 'expired' })))
    const error = await fail(api.get('/jobs'))
    expect(error.status).toBe(0)
    expect(hasAccessToken()).toBe(true)
  })

  it('a second 401 after a successful refresh ends the session (no refresh loop)', async () => {
    setSession(session('old'))
    fetchMock.mockImplementation(async (url: string) => (url.endsWith('/auth/refresh') ? ok(session('new')) : json(401, { success: false, message: 'expired' })))
    const error = await fail(api.get('/jobs'))
    expect(error.status).toBe(401)
    expect(calls.filter(c => c.url.endsWith('/auth/refresh'))).toHaveLength(1)
    expect(hasAccessToken()).toBe(false)
  })

  it('public calls (auth: false) never refresh', async () => {
    fetchMock.mockResolvedValue(json(401, { success: false, message: 'Invalid email or password' }))
    const { authApi } = await import('./auth')
    const error = await fail(authApi.login({ email: 'a@b.c', password: 'x' }))
    expect(error.status).toBe(401)
    expect(calls).toHaveLength(1)
    expect(header(calls[0].init, 'Authorization')).toBeUndefined()
  })

  it('turns envelope errors into ApiError with the server message and field errors', async () => {
    setSession(session('t'))
    fetchMock.mockResolvedValue(json(400, { success: false, message: 'Validation failed', errors: [{ field: 'minimumYears', message: 'must be at most 50' }] }))
    const error = await fail(api.post('/jobs', {}))
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(400)
    expect(error.fieldErrors).toHaveLength(1)
    expect(errorMessage(error)).toBe('Minimum Years: must be at most 50')
  })

  it('treats success:false in a 200 answer as an error', async () => {
    setSession(session('t'))
    fetchMock.mockResolvedValue(json(200, { success: false, message: 'Nope' }))
    await expect(api.get('/x')).rejects.toMatchObject({ status: 200, message: 'Nope' })
  })

  it('splits message-prefixed codes and replaces them with friendly copy', async () => {
    setSession(session('t'))
    fetchMock.mockResolvedValue(json(503, { success: false, message: 'MFA_NOT_CONFIGURED: two-factor authentication is not available on this server' }))
    const error = await fail(api.post('/me/mfa/enroll', {}))
    expect(error.code).toBe('MFA_NOT_CONFIGURED')
    expect(error.message).not.toMatch(/MFA_NOT_CONFIGURED/)
    expect(error.message).toMatch(/not available on this server/i)
    expect(splitCodedMessage('Plain message: with a colon')).toEqual({ code: null, message: 'Plain message: with a colon' })
    expect(splitCodedMessage('SOME_CODE: rest of it')).toEqual({ code: 'SOME_CODE', message: 'Rest of it' })
  })

  it('maps a network failure to status 0 with a readable message', async () => {
    setSession(session('t'))
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    const error = await fail(api.get('/x'))
    expect(error.status).toBe(0)
    expect(errorMessage(error)).toMatch(/cannot reach/i)
  })

  it('recognises lockouts (401 "too many") as different from an expired step', () => {
    expect(isLockout(new ApiError(401, 'Too many failed attempts. Please try again later.'))).toBe(true)
    expect(isLockout(new ApiError(401, 'The sign-in step expired'))).toBe(false)
    expect(isLockout(new ApiError(400, 'Too many'))).toBe(false)
  })

  it('a 429 MFA_LOCKED (integration pass) is a lockout and carries Retry-After', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ success: false, message: 'MFA_LOCKED: Too many failed attempts. Try again in 5 minutes.' }),
      { status: 429, headers: { 'Content-Type': 'application/json', 'Retry-After': '297' } }))
    const error = await fail(api.post('/auth/mfa/verify', { mfaToken: 'x', code: '123456' }))
    expect(error.status).toBe(429)
    expect(error.code).toBe('MFA_LOCKED')
    expect(error.retryAfterSeconds).toBe(297)
    expect(error.message).not.toMatch(/MFA_LOCKED/)
    expect(isLockout(error)).toBe(true)
    expect(waitText(error)).toBe('about 5 minutes')
    expect(parseRetryAfter('Wed, 21 Oct 2015 07:28:00 GMT', Date.parse('Wed, 21 Oct 2015 07:27:00 GMT'))).toBe(60)
    expect(parseRetryAfter('soon')).toBeNull()
  })

  it('the audit export reports X-Export-Truncated (R1-U8)', async () => {
    setSession(session('t'))
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    fetchMock.mockResolvedValueOnce(new Response('csv', { status: 200, headers: { 'Content-Disposition': 'attachment; filename="audit.csv"', 'X-Export-Truncated': 'true' } }))
    expect(await adminApi.exportAudit(undefined, undefined)).toEqual({ filename: 'audit.csv', truncated: true })
    fetchMock.mockResolvedValueOnce(new Response('csv', { status: 200, headers: { 'Content-Disposition': 'attachment; filename="audit.csv"', 'X-Export-Truncated': 'false' } }))
    expect((await adminApi.exportAudit(undefined, undefined)).truncated).toBe(false)
  })

  it('downloads with the server file name and sanitises it', async () => {
    setSession(session('t'))
    fetchMock.mockResolvedValue(new Response('csv', { status: 200, headers: { 'Content-Disposition': "attachment; filename*=UTF-8''job%2F101%3Caudit%3E.csv" } }))
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const name = await downloadFile('/hr/jobs/101/audit-export', 'fallback.csv')
    expect(name).toBe('job_101_audit_.csv')
    expect(click).toHaveBeenCalled()
    expect(header(calls[0].init, PORTAL_HEADER)).toBe(PORTAL)
  })

  it('download errors carry the envelope message', async () => {
    setSession(session('t'))
    fetchMock.mockResolvedValue(json(404, { success: false, message: 'File not found' }))
    await expect(downloadFile('/x', 'f')).rejects.toMatchObject({ status: 404, message: 'File not found' })
  })

  it('parses Content-Disposition forms', () => {
    expect(filenameFromDisposition('attachment; filename="report.csv"')).toBe('report.csv')
    expect(filenameFromDisposition('attachment; filename=plain.txt')).toBe('plain.txt')
    expect(filenameFromDisposition(null)).toBeNull()
    expect(filenameFromDisposition("attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf")).toBe('résumé.pdf')
  })
})
