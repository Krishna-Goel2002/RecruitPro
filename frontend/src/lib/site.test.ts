import { describe, expect, it } from 'vitest'
import { DEFAULT_SITE_URL, siteUrl } from './site'

describe('siteUrl', () => {
  it('uses the configured landing site and falls back to the local default', () => {
    expect(siteUrl('https://www.example.org')).toBe('https://www.example.org/')
    expect(siteUrl('https://www.example.org///')).toBe('https://www.example.org/')
    expect(siteUrl('')).toBe(DEFAULT_SITE_URL)
    expect(siteUrl(undefined)).toBe(DEFAULT_SITE_URL)
    expect(siteUrl('javascript:alert(1)')).toBe(DEFAULT_SITE_URL)
    expect(siteUrl('/relative')).toBe(DEFAULT_SITE_URL)
  })
})
