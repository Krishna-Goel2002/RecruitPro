/*
 * Where the calls to action point. Both origins are compiled in at build time (Docker build args
 * VITE_CANDIDATE_URL / VITE_HR_URL); anything that is not an absolute http(s) URL falls back to the local default,
 * so a typo can never produce a javascript: or relative link.
 */
export const DEFAULT_CANDIDATE_URL = 'http://localhost:5173'
export const DEFAULT_HR_URL = 'http://localhost:5174'

export function portalOrigin(value: string | undefined, fallback: string): string {
  const v = (value ?? '').trim()
  if (!v) return fallback
  try {
    const url = new URL(v)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return fallback
    return v.replace(/\/+$/, '')
  } catch {
    return fallback
  }
}

export interface PortalLinks {
  /** Candidate portal: browse open roles (sign-in first when signed out). */
  findJobs: string
  /** Candidate portal: create an account. */
  register: string
  /** Candidate portal: sign in. */
  candidateSignIn: string
  /** HR portal: sign in. */
  hrSignIn: string
}

export function portalLinks(env: { VITE_CANDIDATE_URL?: string; VITE_HR_URL?: string } = import.meta.env): PortalLinks {
  const candidate = portalOrigin(env.VITE_CANDIDATE_URL, DEFAULT_CANDIDATE_URL)
  const hr = portalOrigin(env.VITE_HR_URL, DEFAULT_HR_URL)
  return {
    findJobs: `${candidate}/jobs`,
    register: `${candidate}/register`,
    candidateSignIn: `${candidate}/login`,
    hrSignIn: `${hr}/login`,
  }
}
