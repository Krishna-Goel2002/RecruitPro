/** True for absolute http(s) URLs; used to validate links and to decide whether to render a value as a link. */
export function isHttpUrl(value: string | null | undefined): value is string {
  if (!value) return false
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

/** True for absolute https URLs only (redirects that leave the app, such as the GitHub sign-in). */
export function isHttpsUrl(value: string | null | undefined): value is string {
  if (!value) return false
  try {
    return new URL(value).protocol === 'https:'
  } catch {
    return false
  }
}

/** A positive integer id from a route or query parameter ("42"), or null for anything else ("abc", "-1", "1.5"). */
export function parseId(value: string | null | undefined): number | null {
  if (!value || !/^\d{1,15}$/.test(value.trim())) return null
  const id = Number(value.trim())
  return id > 0 ? id : null
}

/**
 * The value when it is safe to put in an `href` that opens outside the app (http or https only), otherwise
 * undefined. Data from the server, an agent or another user never becomes a `javascript:` or `data:` link.
 */
export function safeHref(value: string | null | undefined): string | undefined {
  return isHttpUrl(value) ? value : undefined
}
