/*
 * The public landing site (site/, compose service `site`). Signed-out pages link back to it. Compiled in from
 * VITE_SITE_URL (default http://localhost:5170); anything that is not an absolute http(s) URL falls back to the default.
 */
export const DEFAULT_SITE_URL = 'http://localhost:5170/'

export function siteUrl(value: string | undefined = import.meta.env.VITE_SITE_URL): string {
  const v = (value ?? '').trim()
  if (!v) return DEFAULT_SITE_URL
  try {
    const url = new URL(v)
    return url.protocol === 'http:' || url.protocol === 'https:' ? v.replace(/\/+$/, '') + '/' : DEFAULT_SITE_URL
  } catch {
    return DEFAULT_SITE_URL
  }
}
