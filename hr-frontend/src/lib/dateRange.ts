/**
 * Date-range validation for filters that send inclusive ISO dates (`YYYY-MM-DD`), e.g. the fairness report
 * (`?from&to`, UTC days, both optional; the server answers 400 for `from > to` or a malformed date).
 */

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/

/** True for a real calendar date in `YYYY-MM-DD` form (rejects 2026-02-30, 2026-13-01, "16/11/2026"). */
export function isIsoDate(value: string): boolean {
  const match = ISO_DATE.exec(value)
  if (!match) return false
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])]
  if (year < 1900 || year > 9999 || month < 1 || month > 12 || day < 1) return false
  const date = new Date(Date.UTC(year, month - 1, day))
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
}

export type DateRangeErrors = { from?: string; to?: string }

/** Both ends optional; when both are given `from` must not be after `to` (the same day is a valid one-day range). */
export function validateDateRange(from: string, to: string): DateRangeErrors {
  const errors: DateRangeErrors = {}
  if (from && !isIsoDate(from)) errors.from = 'Enter a valid start date (YYYY-MM-DD).'
  if (to && !isIsoDate(to)) errors.to = 'Enter a valid end date (YYYY-MM-DD).'
  if (!errors.from && !errors.to && from && to && from > to) errors.to = 'The end date must be on or after the start date.'
  return errors
}

export function hasErrors(errors: DateRangeErrors): boolean {
  return Boolean(errors.from || errors.to)
}
