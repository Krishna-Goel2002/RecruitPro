import { describe, expect, it } from 'vitest'
import { apiBaseUrl, appLinks, basePath, GATEWAY_DEFAULTS, joinUrl, resolveBase, routerBasename } from './links'

describe('appLinks (one config for every cross-link)', () => {
  it('defaults to the single-origin gateway layout', () => {
    expect(appLinks({})).toEqual({
      site: '/',
      candidateLogin: '/candidate/login',
      candidateRegister: '/candidate/register',
      candidateJobs: '/candidate/jobs',
      hrLogin: '/hr/login',
      hrSignup: '/hr/signup',
    })
  })

  it('supports the per-port development mode with absolute URLs', () => {
    const links = appLinks({
      VITE_SITE_URL: 'http://localhost:5170/',
      VITE_CANDIDATE_URL: 'http://localhost:5173',
      VITE_HR_URL: 'http://localhost:5174//',
    })
    expect(links.site).toBe('http://localhost:5170/')
    expect(links.candidateLogin).toBe('http://localhost:5173/login')
    expect(links.candidateJobs).toBe('http://localhost:5173/jobs')
    expect(links.hrLogin).toBe('http://localhost:5174/login')
  })

  it('keeps a sub-path on another domain (one setting per app)', () => {
    const links = appLinks({ VITE_SITE_URL: 'https://hire.example.org', VITE_CANDIDATE_URL: 'https://hire.example.org/candidate/', VITE_HR_URL: '/staff' })
    expect(links.site).toBe('https://hire.example.org/')
    expect(links.candidateRegister).toBe('https://hire.example.org/candidate/register')
    expect(links.hrLogin).toBe('/staff/login')
  })

  it('never produces a script, protocol-relative or credentialed link', () => {
    for (const bad of ['javascript:alert(1)', '//evil.example', '/\\evil', 'ftp://x.example', 'http://user:pw@x.example', 'candidate', ' ']) {
      const links = appLinks({ VITE_SITE_URL: bad, VITE_CANDIDATE_URL: bad, VITE_HR_URL: bad })
      expect(links.site).toBe('/')
      expect(links.candidateLogin).toBe('/candidate/login')
      expect(links.hrLogin).toBe('/hr/login')
    }
  })
})

describe('apiBaseUrl', () => {
  it('is the relative same-origin /api/v1 by default', () => {
    expect(apiBaseUrl(undefined)).toBe('/api/v1')
    expect(apiBaseUrl('')).toBe('/api/v1')
    expect(GATEWAY_DEFAULTS.api).toBe('/api/v1')
  })

  it('accepts an absolute URL for the per-port mode, without trailing slashes', () => {
    expect(apiBaseUrl('http://localhost:8080/api/v1')).toBe('http://localhost:8080/api/v1')
    expect(apiBaseUrl('https://api.example.org/api/v1///')).toBe('https://api.example.org/api/v1')
    expect(apiBaseUrl('/api/v1/')).toBe('/api/v1')
  })

  it('falls back to the default for unusable values', () => {
    expect(apiBaseUrl('javascript:alert(1)')).toBe('/api/v1')
    expect(apiBaseUrl('//evil.example/api/v1')).toBe('/api/v1')
  })
})

describe('base paths', () => {
  it('derives the Vite base and the router basename', () => {
    expect(basePath(resolveBase('/candidate', '/'))).toBe('/candidate/')
    expect(basePath(resolveBase('/hr/', '/'))).toBe('/hr/')
    expect(basePath(resolveBase('http://localhost:5173', '/'))).toBe('/')
    expect(basePath(resolveBase('https://x.example/candidate', '/'))).toBe('/candidate/')
    expect(basePath(resolveBase('/', '/'))).toBe('/')
    expect(routerBasename('/candidate/')).toBe('/candidate')
    expect(routerBasename('/')).toBe('/')
  })

  it('joins with exactly one slash', () => {
    expect(joinUrl('', '/')).toBe('/')
    expect(joinUrl('/hr/', 'login')).toBe('/hr/login')
    expect(joinUrl('http://localhost:8088/candidate', '/reference/abc')).toBe('http://localhost:8088/candidate/reference/abc')
  })
})
