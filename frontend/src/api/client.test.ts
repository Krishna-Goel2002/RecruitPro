import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fail, mockApi, ok, session } from '../test/utils'

type ClientModule = typeof import('./client')

/** A fresh client per test: the access token, refresh flight and block flag are module state. */
async function freshClient(): Promise<ClientModule> {
  vi.resetModules()
  return import('./client')
}

/** The ApiError a call rejects with (fails the test if it resolves). */
async function rejection(promise: Promise<unknown>): Promise<InstanceType<ClientModule['ApiError']>> {
  try {
    await promise
  } catch (error) {
    return error as InstanceType<ClientModule['ApiError']>
  }
  throw new Error('Expected the call to fail')
}

let client: ClientModule
beforeEach(async () => {
  client = await freshClient()
})
afterEach(() => {
  vi.useRealTimers()
})

describe('buildUrl', () => {
  it('drops empty query values and encodes the rest', () => {
    expect(client.buildUrl('/jobs', { page: 0, size: 10, search: 'c++ dev', department: '', location: null, jobType: undefined })).toBe(
      `${client.API_BASE_URL}/jobs?page=0&size=10&search=c%2B%2B+dev`,
    )
    expect(client.buildUrl('me')).toBe(`${client.API_BASE_URL}/me`)
  })
})

describe('envelope and errors', () => {
  it('returns envelope data and sends the portal header, credentials and JSON body', async () => {
    const api = mockApi([{ method: 'POST', path: '/auth/login', reply: ok(session()) }])
    const result = await client.apiRequest('/auth/login', { method: 'POST', body: { email: 'a@b.co', password: 'x' }, auth: false })
    expect(result).toEqual(session())
    const [call] = api.calls
    expect(call.headers.get('X-RecruitPro-Portal')).toBe('candidate')
    expect(call.headers.get('Content-Type')).toBe('application/json')
    expect(call.headers.get('Authorization')).toBeNull()
    expect(call.credentials).toBe('include')
    expect(call.body).toEqual({ email: 'a@b.co', password: 'x' })
  })

  it('returns null when the backend omits `data` (non_null serialisation)', async () => {
    mockApi([{ method: 'POST', path: '/me/notifications/read-all', reply: ok(undefined, 'All notifications marked as read') }])
    await expect(client.apiRequest('/me/notifications/read-all', { method: 'POST' })).resolves.toBeNull()
  })

  it('maps field errors and the server message into ApiError', async () => {
    mockApi([
      { method: 'POST', path: '/auth/register', reply: fail(400, 'Validation failed', [{ field: 'email', message: 'must be a well-formed email address' }]) },
    ])
    const error = await rejection(client.apiRequest('/auth/register', { method: 'POST', body: {}, auth: false }))
    expect(error).toBeInstanceOf(client.ApiError)
    expect(error.status).toBe(400)
    expect(error.message).toBe('Validation failed')
    expect(error.fieldErrors).toEqual([{ field: 'email', message: 'must be a well-formed email address' }])
  })

  it('splits a coded message such as OAUTH_NOT_CONFIGURED: into code and readable text', async () => {
    mockApi([
      {
        path: '/candidates/me/verification/oauth/github/start',
        reply: fail(503, 'OAUTH_NOT_CONFIGURED: connecting GitHub is not configured on this server'),
      },
    ])
    client.setAccessToken('t')
    const error = await rejection(client.apiRequest('/candidates/me/verification/oauth/github/start'))
    expect(error.status).toBe(503)
    expect(error.code).toBe('OAUTH_NOT_CONFIGURED')
    expect(error.message).toBe('Connecting GitHub is not configured on this server')
  })

  it('reads Retry-After on 429 and mentions it in errorMessage', async () => {
    mockApi([{ method: 'POST', path: '/auth/login', reply: { ...fail(429, 'Too many requests. Please try again later.'), headers: { 'Retry-After': '42' } } }])
    const error = await rejection(client.apiRequest('/auth/login', { method: 'POST', body: {}, auth: false }))
    expect(error.retryAfterSeconds).toBe(42)
    expect(client.errorMessage(error)).toBe('Too many requests. Please try again later. You can try again in 42 seconds.')
  })

  it('parses Retry-After as an HTTP date too', () => {
    const now = Date.parse('2026-11-16T10:00:00Z')
    expect(client.retryAfterSeconds('Mon, 16 Nov 2026 10:02:00 GMT', now)).toBe(120)
    expect(client.retryAfterSeconds('soon', now)).toBeNull()
    expect(client.retryAfterSeconds(null, now)).toBeNull()
  })

  it('uses a plain-language default for a non-JSON gateway error', async () => {
    mockApi([{ path: '/jobs', reply: { status: 502, raw: '<html>Bad gateway</html>' } }])
    client.setAccessToken('t')
    const error = await rejection(client.apiRequest('/jobs'))
    expect(error.status).toBe(502)
    expect(error.message).toBe('Something went wrong on our side. Please try again shortly.')
  })

  it('turns a 413 from the proxy into "The file is too large."', async () => {
    mockApi([{ method: 'POST', path: '/candidates/me/resumes', reply: { status: 413, raw: '' } }])
    client.setAccessToken('t')
    const error = await rejection(client.apiRequest('/candidates/me/resumes', { method: 'POST', body: new FormData() }))
    expect(error.message).toBe('The file is too large.')
  })

  it('reports a network failure as status 0', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))))
    const error = await rejection(client.apiRequest('/jobs', { auth: false }))
    expect(error.isNetworkError).toBe(true)
    expect(error.message).toMatch(/Could not reach the server/)
  })

  it('does not set a JSON content type for FormData uploads', async () => {
    const api = mockApi([{ method: 'POST', path: '/candidates/me/resumes', reply: ok({ id: 1 }) }])
    client.setAccessToken('t')
    const form = new FormData()
    form.append('file', new File(['x'], 'cv.pdf'))
    await client.apiRequest('/candidates/me/resumes', { method: 'POST', body: form })
    expect(api.calls[0].headers.get('Content-Type')).toBeNull()
    expect(api.calls[0].body).toBe(form)
    expect(api.calls[0].headers.get('Authorization')).toBe('Bearer t')
  })
})

describe('refresh on 401', () => {
  it('refreshes once for concurrent 401s and retries each request with the new token', async () => {
    let refreshes = 0
    const api = mockApi([
      {
        method: 'POST',
        path: '/auth/refresh',
        reply: async () => {
          refreshes += 1
          await new Promise(resolve => setTimeout(resolve, 10))
          return ok(session(undefined, 'fresh'))
        },
      },
      { path: /^\/(me|jobs)$/, reply: call => (call.headers.get('Authorization') === 'Bearer fresh' ? ok({ path: call.path }) : fail(401, 'Authentication is required')) },
    ])
    client.setAccessToken('stale')
    const onRefreshed = vi.fn()
    client.subscribeSession({ onRefreshed })
    const [a, b] = await Promise.all([client.apiRequest('/me'), client.apiRequest('/jobs')])
    expect(a).toEqual({ path: '/me' })
    expect(b).toEqual({ path: '/jobs' })
    expect(refreshes).toBe(1)
    expect(onRefreshed).toHaveBeenCalledTimes(1)
    expect(client.getAccessToken()).toBe('fresh')
    // The refresh call itself carries no bearer token but does carry the portal header (per-portal cookie).
    const refresh = api.find('POST', '/auth/refresh')[0]
    expect(refresh.headers.get('Authorization')).toBeNull()
    expect(refresh.headers.get('X-RecruitPro-Portal')).toBe('candidate')
  })

  it('signs out (onExpired) when the refresh cookie is refused, without retrying', async () => {
    const api = mockApi([
      { method: 'POST', path: '/auth/refresh', reply: fail(401, 'Invalid or expired refresh token') },
      { path: '/me', reply: fail(401, 'Authentication is required') },
    ])
    client.setAccessToken('stale')
    const onExpired = vi.fn()
    client.subscribeSession({ onExpired })
    const error = await rejection(client.apiRequest('/me'))
    expect(error.status).toBe(401)
    expect(error.message).toBe('Your session has ended. Please sign in again.')
    expect(onExpired).toHaveBeenCalledTimes(1)
    expect(api.find('GET', '/me')).toHaveLength(1)
    expect(client.getAccessToken()).toBeNull()
  })

  it('treats 403 from refresh (staff cookie on this portal) as signed out', async () => {
    mockApi([{ method: 'POST', path: '/auth/refresh', reply: fail(403, 'This is the candidate portal.') }])
    await expect(client.refreshSession()).resolves.toBeNull()
  })

  it('rejects (does not sign out) when the refresh fails with 5xx or 429', async () => {
    mockApi([{ method: 'POST', path: '/auth/refresh', reply: fail(503, 'Unavailable') }])
    await expect(client.refreshSession()).rejects.toMatchObject({ status: 503 })
  })

  it('returns a second 401 to the caller instead of looping', async () => {
    const api = mockApi([
      { method: 'POST', path: '/auth/refresh', reply: ok(session(undefined, 'fresh')) },
      { path: '/me', reply: fail(401, 'Authentication is required') },
    ])
    client.setAccessToken('stale')
    const error = await rejection(client.apiRequest('/me'))
    expect(error.status).toBe(401)
    expect(api.find('GET', '/me')).toHaveLength(2)
    expect(api.find('POST', '/auth/refresh')).toHaveLength(1)
  })

  it('never refreshes for public calls (auth: false)', async () => {
    const api = mockApi([{ path: '/public/references/abc', reply: fail(401, 'Nope') }])
    client.setAccessToken('secret')
    const error = await rejection(client.apiRequest('/public/references/abc', { auth: false }))
    expect(error.status).toBe(401)
    expect(api.find('POST', '/auth/refresh')).toHaveLength(0)
    expect(api.calls[0].headers.get('Authorization')).toBeNull()
  })

  it('never refreshes after the account was deleted, until the next sign-in', async () => {
    const api = mockApi([
      { method: 'POST', path: '/auth/refresh', reply: ok(session()) },
      { path: '/me', reply: fail(401, 'Authentication is required') },
    ])
    client.blockRefreshUntilSignIn()
    await expect(client.refreshSession()).resolves.toBeNull()
    await expect(client.apiRequest('/me')).rejects.toMatchObject({ status: 401 })
    expect(api.find('POST', '/auth/refresh')).toHaveLength(0)
    client.setAccessToken('new-sign-in')
    await expect(client.refreshSession()).resolves.toMatchObject({ accessToken: 'access-1' })
  })
})

describe('apiDownload', () => {
  it('returns the blob and the RFC 5987 file name', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('bytes', { status: 200, headers: { 'Content-Disposition': "attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf" } })),
    )
    const file = await client.apiDownload('/candidates/me/resumes/1/download', { auth: false })
    expect(file.filename).toBe('résumé.pdf')
    expect(await file.blob.text()).toBe('bytes')
  })

  it('throws the envelope error for a failed download', async () => {
    mockApi([{ path: '/candidates/me/export', reply: fail(404, 'Resume file not found') }])
    await expect(client.apiDownload('/candidates/me/export', { auth: false })).rejects.toMatchObject({ status: 404, message: 'Resume file not found' })
  })
})

describe('splitCodedMessage', () => {
  it('leaves ordinary sentences alone', () => {
    expect(client.splitCodedMessage('Resume not found')).toEqual({ code: null, text: 'Resume not found' })
    expect(client.splitCodedMessage('URL: must be https')).toEqual({ code: null, text: 'URL: must be https' })
    expect(client.splitCodedMessage('RATE_LIMITED: slow down')).toEqual({ code: 'RATE_LIMITED', text: 'Slow down' })
  })
})
