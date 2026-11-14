import type { AuthResponse, Envelope, FieldError } from './types'

/**
 * Typed fetch client.
 * - Unwraps the `{ success, message, data, errors }` envelope.
 * - Keeps the access token in memory only (never in local/session storage). The refresh token lives in the
 *   httpOnly `rp_refresh_staff` cookie, so every call uses `credentials: 'include'`.
 * - Every call sends `X-RecruitPro-Portal: staff`, so the server uses this portal's own refresh cookie (the candidate
 *   portal's session on the same browser is left alone) and refuses candidate accounts with 403.
 * - On a 401 it refreshes once (single flight shared by all concurrent callers) and retries the request once.
 *   If the refresh itself is rejected the session is cleared and listeners are told to sign out.
 */

export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1').replace(/\/+$/, '')

/** Names this portal to the server (per-portal refresh cookie and role check, see backend `auth.web.Portal`). */
export const PORTAL_HEADER = 'X-RecruitPro-Portal'
export const PORTAL = 'staff'

export class ApiError extends Error {
  readonly status: number
  readonly fieldErrors: FieldError[]

  constructor(status: number, message: string, fieldErrors: FieldError[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

export function isApiError(value: unknown, status?: number): value is ApiError {
  return value instanceof ApiError && (status === undefined || value.status === status)
}

/** A readable message for any thrown value, including field errors. */
export function errorMessage(cause: unknown, fallback = 'Something went wrong. Try again.'): string {
  if (cause instanceof ApiError) {
    if (cause.fieldErrors.length) return cause.fieldErrors.map(error => `${humanField(error.field)}: ${error.message}`).join(' · ')
    return cause.message || fallback
  }
  if (cause instanceof Error && cause.message) return cause.message
  return fallback
}

function humanField(field: string) {
  return field.replace(/([A-Z])/g, ' $1').replace(/[._]/g, ' ').trim().replace(/^\w/, c => c.toUpperCase())
}

// ---------------------------------------------------------------------------------------------------------------------
// Session state (memory only)
// ---------------------------------------------------------------------------------------------------------------------

let accessToken: string | null = null
type SessionListener = (auth: AuthResponse | null) => void
const listeners = new Set<SessionListener>()

export function setSession(auth: AuthResponse | null) {
  accessToken = auth?.accessToken ?? null
  listeners.forEach(listener => listener(auth))
}

export function onSessionChange(listener: SessionListener): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

export function hasAccessToken() {
  return accessToken !== null
}

type RefreshOutcome = { kind: 'ok'; auth: AuthResponse } | { kind: 'unauthorized' } | { kind: 'network' }
let refreshInFlight: Promise<RefreshOutcome> | null = null

/**
 * Single-flight refresh: concurrent callers share one request, so one rotation happens per expiry.
 * `unauthorized` clears the session; a network failure leaves it alone (the server may just be unreachable).
 */
export function refreshSession(): Promise<RefreshOutcome> {
  if (!refreshInFlight) {
    refreshInFlight = (async (): Promise<RefreshOutcome> => {
      let response: Response
      try {
        response = await fetch(`${API_BASE_URL}/auth/refresh`, {
          method: 'POST',
          credentials: 'include',
          headers: { Accept: 'application/json', [PORTAL_HEADER]: PORTAL },
        })
      } catch {
        return { kind: 'network' }
      }
      const envelope = await readEnvelope<AuthResponse>(response)
      if (response.ok && envelope?.success && envelope.data?.accessToken) {
        setSession(envelope.data)
        return { kind: 'ok', auth: envelope.data }
      }
      if (response.status >= 500 || response.status === 429) return { kind: 'network' }
      setSession(null)
      return { kind: 'unauthorized' }
    })().finally(() => { refreshInFlight = null })
  }
  return refreshInFlight
}

// ---------------------------------------------------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------------------------------------------------

export type Query = Record<string, string | number | boolean | null | undefined>

export type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  query?: Query
  signal?: AbortSignal
  /** Set false for public endpoints (login, signup, invitation preview…): no bearer token, no refresh-retry. */
  auth?: boolean
}

export function buildUrl(path: string, query?: Query): string {
  const url = `${API_BASE_URL}${path.startsWith('/') ? path : `/${path}`}`
  if (!query) return url
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    params.set(key, String(value))
  }
  const text = params.toString()
  return text ? `${url}?${text}` : url
}

async function readEnvelope<T>(response: Response): Promise<Envelope<T> | null> {
  const text = await response.text().catch(() => '')
  if (!text) return null
  try {
    return JSON.parse(text) as Envelope<T>
  } catch {
    return null
  }
}

function defaultMessage(status: number): string {
  if (status === 0) return 'Cannot reach the RecruitPro server. Check your connection and try again.'
  if (status === 401) return 'Your session has ended. Sign in again to continue.'
  if (status === 403) return 'Your role does not allow this action.'
  if (status === 404) return 'This item was not found, or it belongs to another organisation.'
  if (status === 409) return 'This item changed in the meantime. Reload and try again.'
  if (status === 429) return 'Too many attempts. Wait a moment and try again.'
  if (status >= 500) return 'The server could not complete the request. Try again shortly.'
  return `Request failed (${status}).`
}

async function send(path: string, options: RequestOptions, accept: string): Promise<Response> {
  const useAuth = options.auth !== false
  const tokenUsed = useAuth ? accessToken : null
  const headers: Record<string, string> = { Accept: accept, [PORTAL_HEADER]: PORTAL }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (tokenUsed) headers.Authorization = `Bearer ${tokenUsed}`

  let response: Response
  try {
    response = await fetch(buildUrl(path, options.query), {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      credentials: 'include',
      signal: options.signal,
    })
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause
    throw new ApiError(0, defaultMessage(0))
  }

  if (response.status === 401 && useAuth) {
    // Another caller may already hold a newer token; otherwise refresh once (shared) and retry once.
    if (accessToken && accessToken !== tokenUsed) return sendOnce(path, options, accept)
    const outcome = await refreshSession()
    if (outcome.kind === 'ok') return sendOnce(path, options, accept)
    if (outcome.kind === 'network') throw new ApiError(0, defaultMessage(0))
    throw new ApiError(401, defaultMessage(401))
  }
  return response
}

/** The retry after a refresh: no further refresh, a second 401 ends the session. */
async function sendOnce(path: string, options: RequestOptions, accept: string): Promise<Response> {
  const headers: Record<string, string> = { Accept: accept, [PORTAL_HEADER]: PORTAL }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`
  let response: Response
  try {
    response = await fetch(buildUrl(path, options.query), {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      credentials: 'include',
      signal: options.signal,
    })
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause
    throw new ApiError(0, defaultMessage(0))
  }
  if (response.status === 401) {
    setSession(null)
    throw new ApiError(401, defaultMessage(401))
  }
  return response
}

/** JSON request; returns the envelope's `data`. */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(path, options, 'application/json')
  const envelope = await readEnvelope<T>(response)
  if (!response.ok || (envelope && envelope.success === false)) {
    throw new ApiError(response.status, envelope?.message || defaultMessage(response.status), envelope?.errors ?? [])
  }
  return (envelope?.data ?? undefined) as T
}

export const api = {
  get: <T>(path: string, query?: Query, signal?: AbortSignal) => apiRequest<T>(path, { query, signal }),
  post: <T>(path: string, body?: unknown) => apiRequest<T>(path, { method: 'POST', body }),
  put: <T>(path: string, body?: unknown) => apiRequest<T>(path, { method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown) => apiRequest<T>(path, { method: 'PATCH', body }),
  delete: <T>(path: string) => apiRequest<T>(path, { method: 'DELETE' }),
}

// ---------------------------------------------------------------------------------------------------------------------
// File downloads (resume, CSV exports)
// ---------------------------------------------------------------------------------------------------------------------

/** Parses `filename*=UTF-8''…` (preferred) or `filename="…"` from Content-Disposition. */
export function filenameFromDisposition(header: string | null): string | null {
  if (!header) return null
  const extended = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/.exec(header)
  if (extended) {
    try {
      return sanitizeFilename(decodeURIComponent(extended[1].trim().replace(/^"|"$/g, '')))
    } catch {
      // fall through to the plain form
    }
  }
  const plain = /filename\s*=\s*("([^"]*)"|[^;]+)/.exec(header)
  if (plain) return sanitizeFilename((plain[2] ?? plain[1]).trim())
  return null
}

function sanitizeFilename(name: string) {
  const cleaned = name.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim()
  return cleaned || null
}

/** Downloads a file through the authenticated client and saves it with the server's filename. */
export async function downloadFile(path: string, fallbackName: string, query?: Query): Promise<string> {
  const response = await send(path, { query }, '*/*')
  if (!response.ok) {
    const envelope = await readEnvelope<unknown>(response)
    throw new ApiError(response.status, envelope?.message || defaultMessage(response.status), envelope?.errors ?? [])
  }
  const blob = await response.blob()
  const filename = filenameFromDisposition(response.headers.get('Content-Disposition')) ?? fallbackName
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.rel = 'noopener'
  anchor.style.display = 'none'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 30_000)
  return filename
}
