/* Formatting shared by both portals: numbers (en-IN), money (₹), plurals, relative time, file size. */

const nf = new Intl.NumberFormat('en-IN')
const pr = new Intl.PluralRules('en-IN')

export function formatNumber(n: number): string {
  return nf.format(n)
}

export function formatMoney(amount: number, currency = 'INR'): string {
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency, maximumFractionDigits: 0 }).format(amount)
}

/** plural(3, 'result') → "3 results"; plural(1, 'person', 'people') → "1 person". */
export function plural(n: number, one: string, other = `${one}s`): string {
  return `${formatNumber(n)} ${pr.select(n) === 'one' ? one : other}`
}

export function formatPercent(n: number, digits = 0): string {
  return `${n.toFixed(digits)}%`
}

const rtf = new Intl.RelativeTimeFormat('en', { numeric: 'auto', style: 'short' })
const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 365 * 86400], ['month', 30 * 86400], ['week', 7 * 86400], ['day', 86400], ['hour', 3600], ['minute', 60],
]

/** "3 days ago", "in 2 hr", "now". */
export function relativeTime(date: Date | string | number, now = Date.now()): string {
  const t = new Date(date).getTime()
  const s = Math.round((t - now) / 1000)
  for (const [unit, secs] of UNITS) {
    if (Math.abs(s) >= secs) return rtf.format(Math.round(s / secs), unit)
  }
  return 'now'
}

export function formatDate(date: Date | string | number, opts: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short', year: 'numeric' }): string {
  return new Intl.DateTimeFormat('en-IN', opts).format(new Date(date))
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (!parts.length) return '?'
  return ((parts[0][0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase()
}
