// Shared helpers for fixtures: envelope wrapping, paging, relative timestamps.
// Fixtures return plain data; the harness wraps it in the backend envelope `{ success, message, data, errors }`.

export const NOW = Date.now()
const H = 3600_000
/** ISO timestamp `hours` before the moment the harness started. */
export const ago = (hours) => new Date(NOW - hours * H).toISOString()
/** ISO timestamp `hours` after the moment the harness started. */
export const ahead = (hours) => new Date(NOW + hours * H).toISOString()

export function page(content, { page = 0, size = content.length || 10, total } = {}) {
  const totalElements = total ?? content.length
  const totalPages = Math.max(1, Math.ceil(totalElements / size))
  return { content, page, size, totalElements, totalPages, first: page === 0, last: page >= totalPages - 1 }
}

/** Return this from a handler to answer with an error envelope instead of data. */
export function fail(status, message, errors) {
  return { __status: status, __body: { success: false, message, errors: errors ?? null } }
}

/** Answer after `ms` (use with a screen's `waitUntil: 'domcontentloaded'` to capture loading skeletons). */
export function delayed(ms, value) {
  return { __delay: ms, __value: value }
}

export function session(user) {
  return { accessToken: 'snapshot-token', tokenType: 'Bearer', expiresInSeconds: 900, user }
}

/**
 * A route is [method, pathRegex, handler]. `handler(ctx)` gets { method, path, query, body, match }
 * and returns data (wrapped as success), `fail(...)`, or a raw `{ __status, __body, __headers }`.
 */
export function route(method, pattern, handler) {
  const re = pattern instanceof RegExp ? pattern : new RegExp(`^${pattern}$`)
  return [method, re, typeof handler === 'function' ? handler : () => handler]
}
