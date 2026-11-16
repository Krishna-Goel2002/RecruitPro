import { describe, expect, it } from 'vitest'
import { hasErrors, isIsoDate, validateDateRange } from './dateRange'

describe('fairness date range validation', () => {
  it('accepts real calendar dates only', () => {
    expect(isIsoDate('2026-02-28')).toBe(true)
    expect(isIsoDate('2028-02-29')).toBe(true)
    expect(isIsoDate('2026-02-29')).toBe(false)
    expect(isIsoDate('2026-02-30')).toBe(false)
    expect(isIsoDate('2026-13-01')).toBe(false)
    expect(isIsoDate('16/11/2026')).toBe(false)
    expect(isIsoDate('2026-1-1')).toBe(false)
    expect(isIsoDate('')).toBe(false)
  })

  it('both ends are optional; from must not be after to; one day is valid', () => {
    expect(hasErrors(validateDateRange('', ''))).toBe(false)
    expect(hasErrors(validateDateRange('2026-01-01', ''))).toBe(false)
    expect(hasErrors(validateDateRange('', '2026-01-01'))).toBe(false)
    expect(hasErrors(validateDateRange('2026-03-01', '2026-03-01'))).toBe(false)
    expect(validateDateRange('2026-03-02', '2026-03-01').to).toMatch(/on or after/)
    expect(validateDateRange('2026-02-30', '2026-03-01').from).toMatch(/valid start/)
    expect(validateDateRange('2026-03-01', 'nope').to).toMatch(/valid end/)
  })
})
