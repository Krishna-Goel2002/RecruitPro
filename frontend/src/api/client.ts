/**
 * Typed fetch client for the RecruitPro API.
 *
 * - Unwraps the `{ success, message, data, errors }` envelope and throws `ApiError` for failures.
 * - Keeps the access token in memory only (never localStorage/sessionStorage - audit S2).
 * - Every request uses `credentials: 'include'` so the httpOnly `rp_refresh_candidate` cookie travels to `/auth/*`.
 * - Every request sends `X-RecruitPro-Portal: candidate`, so the server reads and writes this portal's own refresh
 *   cookie (a staff session on the HR portal in the same browser is left alone) and refuses staff accounts with 403.
 * - On a 401 it refreshes once (single flight shared by all concurrent requests) and retries once (audit M6).
 *   When the refresh is refused, the session is cleared and subscribers are told to sign the user out.
 * - JSON bodies go through `JSON.stringify`, so `Date` values are sent as `toISOString()` (D15).
 */
import type { ApiEnvelope, ApiFieldError, AuthResponse } from './types'

const DEFAULT_BASE_URL = 'http://localhost:8080/api/v1'

export const API_BASE_URL: string = (import.meta.env.VITE_API_BASE_URL || DEFAULT_BASE_URL).replace(/\/+$/, '')

/** Names this portal to the server (per-portal refresh cookie and role check, see backend `auth.web.Portal`). */
export const PORTAL_HEADER = 'X-RecruitPro-Portal'
export const PORTAL = 'candidate'

/** A machine code the server puts in front of a message, e.g. `OAUTH_NOT_CONFIGURED: connecting GitHub is …`. */
const CODE_PREFIX = /^([A-Z][A-Z0-9]*_[A-Z0-9_]+):\s*(.*)$/s

/** Splits `CODE: sentence` into the code and a sentence fit to show (first letter upper-cased). */
export function splitCodedMessage(message: string): { code: string | null; text: string } {
  const match = CODE_PREFIX.exec(message.trim())
  if (!match) return { code: null, text: message }
  const rest = match[2].trim()
  return { code: match[1], text: rest ? `${rest.charAt(0).toUpperCase()}${rest.slice(1)}` : message }
}

export class ApiError extends Error {
  /** HTTP status; 0 for a network failure. */
  readonly status: number
  readonly fieldErrors: ApiFieldError[]
  /** Machine code from a `CODE: message` answer (the code is removed from `message`). */
  readonly code: string | null
  /** Seconds from a `Retry-After` header (429), when the server sent one. */
  readonly retryAfterSeconds: number | null

  constructor(message: string, status: number, fieldErrors: ApiFieldError[] = [], options: { code?: string | null; retryAfterSeconds?: number | null } = {}) {
    const coded = options.code === undefined ? splitCodedMessage(message) : { code: options.code, text: message }
    super(coded.text)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
    this.code = coded.code
    this.retryAfterSeconds = options.retryAfterSeconds ?? null
  }

  get isNetworkError(): boolean {
    return this.status === 0
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// In-memory session
// ---------------------------------------------------------------------------------------------------------------------

let accessToken: string | null = null

export function getAccessToken(): string | null {
  return accessToken
}

export function setAccessToken(token: string | null): void {
  accessToken = token
  if (token) refreshBlocked = false
}

/**
 * Set after the account was deleted: the server has already revoked every session and cleared the refresh cookie,
 * so no request may try to refresh (WP-BE-C follow-up). Cleared by the next sign-in or registration.
 */
let refreshBlocked = false

export function blockRefreshUntilSignIn(): void {
  refreshBlocked = true
  accessToken = null
}

export interface SessionListener {
  /** A refresh produced a new access token (the user may have changed, e.g. emailVerified). */
  onRefreshed?: (auth: AuthResponse) => void
  /** The refresh cookie was refused: the user must sign in again. */
  onExpired?: () => void
}

const listeners = new Set<SessionListener>()

export function subscribeSession(listener: SessionListener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function expireSession(): void {
  accessToken = null
  listeners.forEach(listener => listener.onExpired?.())
}

// ---------------------------------------------------------------------------------------------------------------------
// Low-level helpers
// ---------------------------------------------------------------------------------------------------------------------

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
export type QueryValue = string | number | boolean | null | undefined

export interface RequestOptions {
  method?: HttpMethod
  query?: Record<string, QueryValue>
  /** A JSON-serialisable object, or FormData for uploads. */
  body?: FormData | object
  /** Attach the bearer token and refresh on 401 (default true). */
  auth?: boolean
  signal?: AbortSignal
}

export function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const url = `${API_BASE_URL}${path.startsWith('/') ? path : `/${path}`}`
  if (!query) return url
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    params.set(key, String(value))
  }
  const search = params.toString()
  return search ? `${url}?${search}` : url
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isEnvelope(value: unknown): value is ApiEnvelope<unknown> {
  return isRecord(value) && typeof value.success === 'boolean'
}

function toFieldErrors(value: unknown): ApiFieldError[] {
  if (!Array.isArray(value)) return []
  // Array.isArray narrows `unknown` to `any[]`; re-type the items as unknown before inspecting them.
  const items: readonly unknown[] = value
  const errors: ApiFieldError[] = []
  for (const item of items) {
    if (isRecord(item) && typeof item.field === 'string' && typeof item.message === 'string') {
      errors.push({ field: item.field, message: item.message })
    }
  }
  return errors
}

function defaultMessage(status: number): string {
  if (status === 401) return 'Please sign in to continue.'
  if (status === 403) return 'You do not have access to this.'
  if (status === 404) return 'We could not find what you were looking for.'
  if (status === 409) return 'This conflicts with the current state. Refresh and try again.'
  if (status === 413) return 'The file is too large.'
  if (status === 429) return 'Too many attempts. Please wait a minute and try again.'
  if (status >= 500) return 'Something went wrong on our side. Please try again shortly.'
  return `Request failed (${status}).`
}

function networkError(): ApiError {
  return new ApiError('Could not reach the server. Check your connection and try again.', 0)
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text) return undefined
  try {
    const parsed: unknown = JSON.parse(text)
    return parsed
  } catch {
    return undefined
  }
}

/** `Retry-After` as seconds (delta-seconds or an HTTP date); null when absent or unreadable. */
export function retryAfterSeconds(header: string | null, now = Date.now()): number | null {
  if (!header) return null
  const value = header.trim()
  if (/^\d+$/.test(value)) return Number(value)
  const at = Date.parse(value)
  return Number.isNaN(at) ? null : Math.max(0, Math.ceil((at - now) / 1000))
}

/** Turns a response into the envelope's `data`, or throws `ApiError`. */
async function unwrap<T>(response: Response): Promise<T> {
  const payload = response.status === 204 ? undefined : await readJson(response)
  const envelope = isEnvelope(payload) ? payload : null
  if (!response.ok || (envelope !== null && !envelope.success)) {
    const serverMessage = envelope?.message ?? (isRecord(payload) && typeof payload.message === 'string' ? payload.message : null)
    const status = response.status
    throw new ApiError(serverMessage || defaultMessage(status), status, toFieldErrors(envelope?.errors), {
      retryAfterSeconds: status === 429 ? retryAfterSeconds(response.headers.get('Retry-After')) : null,
    })
  }
  // `data` is absent for "null" responses (the backend omits null fields); callers typed `T = void | null` accept that.
  return (envelope ? envelope.data ?? null : payload ?? null) as T
}

async function send(path: string, options: RequestOptions, token: string | null): Promise<Response> {
  const headers = new Headers({ Accept: 'application/json', [PORTAL_HEADER]: PORTAL })
  let body: BodyInit | undefined
  if (options.body instanceof FormData) {
    body = options.body
  } else if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json')
    body = JSON.stringify(options.body)
  }
  if (token) headers.set('Authorization', `Bearer ${token}`)
  try {
    return await fetch(buildUrl(path, options.query), {
      method: options.method ?? (body === undefined ? 'GET' : 'POST'),
      headers,
      body,
      credentials: 'include',
      signal: options.signal,
    })
  } catch (error) {
    if (isAbortError(error)) throw error
    throw networkError()
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Refresh (single flight)
// ---------------------------------------------------------------------------------------------------------------------

let refreshInFlight: Promise<AuthResponse | null> | null = null

async function performRefresh(): Promise<AuthResponse | null> {
  const response = await send('/auth/refresh', { method: 'POST', auth: false }, null)
  if (response.status === 400 || response.status === 401 || response.status === 403) {
    accessToken = null
    return null
  }
  const auth = await unwrap<AuthResponse>(response)
  accessToken = auth.accessToken
  listeners.forEach(listener => listener.onRefreshed?.(auth))
  return auth
}

/**
 * Exchanges the refresh cookie for a new access token. Concurrent callers share one request.
 * Resolves `null` when the server refuses the cookie (signed out); rejects with `ApiError` on network/5xx/429.
 */
export function refreshSession(): Promise<AuthResponse | null> {
  if (refreshBlocked) return Promise.resolve(null)
  if (!refreshInFlight) {
    refreshInFlight = performRefresh().finally(() => {
      refreshInFlight = null
    })
  }
  return refreshInFlight
}

// ---------------------------------------------------------------------------------------------------------------------
// Authorised requests
// ---------------------------------------------------------------------------------------------------------------------

async function authorizedFetch(path: string, options: RequestOptions): Promise<Response> {
  const useAuth = options.auth !== false
  const tokenUsed = useAuth ? accessToken : null
  const first = await send(path, options, tokenUsed)
  if (first.status !== 401 || !useAuth) return first
  // Signed out because the account was deleted: a 401 is final, no refresh attempt.
  if (refreshBlocked) throw new ApiError('Please sign in to continue.', 401)

  // Another request may already have refreshed while this one was in flight.
  let nextToken: string | null
  if (accessToken !== null && accessToken !== tokenUsed) {
    nextToken = accessToken
  } else {
    const session = await refreshSession()
    nextToken = session?.accessToken ?? null
  }
  if (nextToken === null) {
    expireSession()
    throw new ApiError('Your session has ended. Please sign in again.', 401)
  }
  // One retry only. A second 401 is returned to the caller as an ordinary error.
  return send(path, options, nextToken)
}

/** Calls the API and returns the envelope's `data`. */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await authorizedFetch(path, options)
  return unwrap<T>(response)
}

export interface DownloadedFile {
  blob: Blob
  /** File name from Content-Disposition, when the server sent one. */
  filename: string | null
}

function filenameFromDisposition(header: string | null): string | null {
  if (!header) return null
  const encoded = /filename\*\s*=\s*(?:UTF-8'')?([^;]+)/i.exec(header)
  if (encoded?.[1]) {
    try {
      return decodeURIComponent(encoded[1].trim().replace(/^"|"$/g, ''))
    } catch {
      /* fall through to the plain parameter */
    }
  }
  const plain = /filename\s*=\s*"?([^";]+)"?/i.exec(header)
  return plain?.[1]?.trim() || null
}

/** Downloads a binary response (resume file, data export). */
export async function apiDownload(path: string, options: RequestOptions = {}): Promise<DownloadedFile> {
  const response = await authorizedFetch(path, options)
  if (!response.ok) {
    await unwrap<never>(response)
  }
  return { blob: await response.blob(), filename: filenameFromDisposition(response.headers.get('Content-Disposition')) }
}

/** Message suitable for showing to the user. */
export function errorMessage(error: unknown, fallback = 'Something went wrong. Please try again.'): string {
  if (error instanceof ApiError) {
    const wait = error.retryAfterSeconds
    if (error.status === 429 && wait !== null && wait > 0) {
      const when = wait < 90 ? `${wait} second${wait === 1 ? '' : 's'}` : `${Math.ceil(wait / 60)} minutes`
      return `${error.message} You can try again in ${when}.`
    }
    return error.message
  }
  if (error instanceof Error && error.message) return error.message
  return fallback
}
