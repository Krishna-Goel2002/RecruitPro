/*
 * Where the three web apps and the API live: the ONE place every cross-link comes from (landing site <-> candidate
 * portal <-> HR portal), the API base URL, and each app's own base path. Shared byte-for-byte by hr-frontend/,
 * frontend/ and site/ (src/design is checked by tools/check_design_sync.py), so the three apps cannot disagree.
 *
 * Build-time settings (Vite env, Docker build args). The defaults are the single-origin gateway layout (docker compose
 * service `gateway`, http://localhost:8088):
 *
 *   VITE_SITE_URL       landing site       default "/"
 *   VITE_CANDIDATE_URL  candidate portal   default "/candidate"
 *   VITE_HR_URL         HR portal          default "/hr"
 *   VITE_API_BASE_URL   API (portals)      default "/api/v1"
 *
 * A value is either a path on the same origin ("/hr": gateway mode; nothing names a host, so moving to another domain
 * needs no rebuild) or an absolute http(s) URL ("http://localhost:5174": the per-port development mode). Anything else
 * ("javascript:...", "//host", a typo) falls back to the default, so a setting can never produce a script link or a
 * protocol-relative link to another host.
 *
 * Each app's Vite `base` comes from its own entry (vite.config.ts: basePath(...)) and its React Router basename from
 * Vite's BASE_URL (routerBasename), so a portal and the links pointing at it always agree.
 */

export const GATEWAY_DEFAULTS = { site: '/', candidate: '/candidate', hr: '/hr', api: '/api/v1' } as const

const SAME_ORIGIN_PATH = /^\/(?!\/)[A-Za-z0-9._~\-/]*$/

const stripTrailingSlashes = (value: string) => value.replace(/\/+$/, '')

/** A configured base without its trailing slash ("" is the origin root), or the fallback's when the value is unusable. */
export function resolveBase(value: string | undefined, fallback: string): string {
  const v = (value ?? '').trim()
  if (v) {
    if (SAME_ORIGIN_PATH.test(v)) return stripTrailingSlashes(v)
    try {
      const url = new URL(v)
      if ((url.protocol === 'http:' || url.protocol === 'https:') && !url.username && !url.password && !url.search && !url.hash) {
        return stripTrailingSlashes(v)
      }
    } catch {
      // not a URL: use the fallback
    }
  }
  return stripTrailingSlashes(fallback)
}

/** `base` + `path` with exactly one slash between them ("" + "/" = "/", "/hr" + "/login" = "/hr/login"). */
export function joinUrl(base: string, path: string): string {
  return `${stripTrailingSlashes(base)}${path.startsWith('/') ? path : `/${path}`}`
}

/** The path part of a resolved base as a Vite `base`: "/candidate" -> "/candidate/", "http://localhost:5173" -> "/". */
export function basePath(base: string): string {
  let path = base
  if (!base.startsWith('/') && base !== '') {
    try {
      path = new URL(base).pathname
    } catch {
      path = '/'
    }
  }
  return `${stripTrailingSlashes(path)}/`
}

/** React Router basename for Vite's BASE_URL: "/candidate/" -> "/candidate", "/" -> "/". */
export function routerBasename(baseUrl: string): string {
  return stripTrailingSlashes(baseUrl) || '/'
}

export interface LinkEnv {
  VITE_SITE_URL?: string
  VITE_CANDIDATE_URL?: string
  VITE_HR_URL?: string
}

export interface AppLinks {
  /** The landing page ("RecruitPro home"). */
  site: string
  candidateLogin: string
  candidateRegister: string
  /** Open roles (signed-out visitors sign in first and land on the list). */
  candidateJobs: string
  hrLogin: string
  hrSignup: string
}

export function appLinks(env: LinkEnv = import.meta.env): AppLinks {
  const site = resolveBase(env.VITE_SITE_URL, GATEWAY_DEFAULTS.site)
  const candidate = resolveBase(env.VITE_CANDIDATE_URL, GATEWAY_DEFAULTS.candidate)
  const hr = resolveBase(env.VITE_HR_URL, GATEWAY_DEFAULTS.hr)
  return {
    site: joinUrl(site, '/'),
    candidateLogin: joinUrl(candidate, '/login'),
    candidateRegister: joinUrl(candidate, '/register'),
    candidateJobs: joinUrl(candidate, '/jobs'),
    hrLogin: joinUrl(hr, '/login'),
    hrSignup: joinUrl(hr, '/signup'),
  }
}

/** API base URL including /api/v1, without a trailing slash: "/api/v1" (same origin) or an absolute URL. */
export function apiBaseUrl(value: string | undefined = import.meta.env.VITE_API_BASE_URL): string {
  return resolveBase(value, GATEWAY_DEFAULTS.api)
}
