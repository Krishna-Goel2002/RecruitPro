/** Display helpers. All timestamps from the API are UTC; they are shown in the viewer's local time with Intl. */

function toDate(value: string | Date | null | undefined): Date | null {
  if (!value) return null
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

const dateFormat = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
const dateTimeFormat = new Intl.DateTimeFormat(undefined, {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
})
const longDateTimeFormat = new Intl.DateTimeFormat(undefined, {
  weekday: 'long',
  day: 'numeric',
  month: 'long',
  year: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
  timeZoneName: 'short',
})
const timeFormat = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit', timeZoneName: 'short' })

export function formatDate(value: string | Date | null | undefined, fallback = '—'): string {
  const date = toDate(value)
  return date ? dateFormat.format(date) : fallback
}

export function formatDateTime(value: string | Date | null | undefined, fallback = '—'): string {
  const date = toDate(value)
  return date ? dateTimeFormat.format(date) : fallback
}

/** Weekday, date, time and time-zone name - used for interviews and deadlines. */
export function formatLongDateTime(value: string | Date | null | undefined, fallback = '—'): string {
  const date = toDate(value)
  return date ? longDateTimeFormat.format(date) : fallback
}

export function formatTime(value: string | Date | null | undefined, fallback = '—'): string {
  const date = toDate(value)
  return date ? timeFormat.format(date) : fallback
}

const relativeFormat = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })

export function formatRelative(value: string | null | undefined, now: number = Date.now()): string {
  const date = toDate(value)
  if (!date) return '—'
  const seconds = Math.round((date.getTime() - now) / 1000)
  const abs = Math.abs(seconds)
  if (abs < 60) return relativeFormat.format(seconds, 'second')
  if (abs < 3600) return relativeFormat.format(Math.round(seconds / 60), 'minute')
  if (abs < 86400) return relativeFormat.format(Math.round(seconds / 3600), 'hour')
  if (abs < 86400 * 30) return relativeFormat.format(Math.round(seconds / 86400), 'day')
  return formatDate(date)
}

/** Current time zone, e.g. "Asia/Kolkata", for "times are shown in …" hints. */
export function localTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'your local time zone'
}

export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

const currencyFormat = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 })

export function formatSalaryRange(min: number | null, max: number | null): string {
  if (min == null && max == null) return 'Salary not listed'
  if (min != null && max != null) return `${currencyFormat.format(min)} – ${currencyFormat.format(max)}`
  if (min != null) return `From ${currencyFormat.format(min)}`
  return `Up to ${currencyFormat.format(max ?? 0)}`
}

/** "2 h 05 min" / "4:09" style countdown text. */
export function formatDuration(totalSeconds: number): string {
  const seconds = Math.max(0, Math.floor(totalSeconds))
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${m}:${ss}`
}

/** Spoken form for screen readers: "12 minutes 5 seconds". */
export function describeDuration(totalSeconds: number): string {
  const seconds = Math.max(0, Math.floor(totalSeconds))
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  const parts: string[] = []
  if (h) parts.push(`${h} hour${h === 1 ? '' : 's'}`)
  if (m) parts.push(`${m} minute${m === 1 ? '' : 's'}`)
  if (!h && s) parts.push(`${s} second${s === 1 ? '' : 's'}`)
  return parts.length ? parts.join(' ') : '0 seconds'
}

export function initials(firstName?: string | null, lastName?: string | null): string {
  return `${firstName?.trim()[0] ?? ''}${lastName?.trim()[0] ?? ''}`.toUpperCase() || 'C'
}

/** Whole local calendar days from `now` to `date` (0 = today, 1 = tomorrow, -1 = yesterday), DST-safe. */
export function calendarDaysUntil(date: string | Date, now: number | Date = Date.now()): number {
  const target = new Date(date)
  const from = new Date(now)
  const a = Date.UTC(target.getFullYear(), target.getMonth(), target.getDate())
  const b = Date.UTC(from.getFullYear(), from.getMonth(), from.getDate())
  return Math.round((a - b) / 86_400_000)
}

/**
 * "Closes today" / "Closes in 3 days" / "Closes in 2 weeks" (long words, unlike the compact relative time). Counted in
 * the viewer's calendar days: a deadline at 18:00 today says "today", not "tomorrow" (rounding up 6 hours did).
 */
export function closesIn(deadline: string | Date, now: number | Date = Date.now()): string {
  if (new Date(deadline).getTime() <= new Date(now).getTime()) return 'Closed'
  const days = calendarDaysUntil(deadline, now)
  if (days < 0) return 'Closed'
  if (days === 0) return 'Closes today'
  if (days === 1) return 'Closes tomorrow'
  if (days < 14) return `Closes in ${days} days`
  return `Closes in ${Math.round(days / 7)} weeks`
}
