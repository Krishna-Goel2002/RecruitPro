/*
 * Where the calls to action point. Every address comes from the shared link config (src/design/lib/links.ts, the same
 * file in both portals): by default the single-origin gateway paths (/candidate/..., /hr/...), or the absolute portal
 * URLs a per-port build sets (Docker build args VITE_CANDIDATE_URL / VITE_HR_URL / VITE_SITE_URL). A value that is
 * neither a same-origin path nor an absolute http(s) URL falls back to the default, so a typo can never produce a
 * javascript: link.
 */
import { appLinks, type LinkEnv } from '../design/lib/links'

export interface PortalLinks {
  /** This landing page (the 404 page's way home). */
  home: string
  /** Candidate portal: open roles (signed-out visitors sign in first and land on the list). */
  findJobs: string
  /** Candidate portal: create an account. */
  register: string
  /** Candidate portal: sign in. */
  candidateSignIn: string
  /** HR portal: sign in. */
  hrSignIn: string
}

export function portalLinks(env: LinkEnv = import.meta.env): PortalLinks {
  const links = appLinks(env)
  return {
    home: links.site,
    findJobs: links.candidateJobs,
    register: links.candidateRegister,
    candidateSignIn: links.candidateLogin,
    hrSignIn: links.hrLogin,
  }
}
