/*
 * Dates for staff screens (Part B): interview slots, short day labels and the viewer's time-zone name, so times read
 * "Thu 19 Nov, 10:15–11:00" and the zone is stated once ("India Standard Time (UTC+5:30)"), never "Asia/Calcutta".
 */

const dayFmt = new Intl.DateTimeFormat('en-IN', { weekday: 'short', day: 'numeric', month: 'short' })
const dayYearFmt = new Intl.DateTimeFormat('en-IN', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
const timeFmt = new Intl.DateTimeFormat('en-IN', { hour: '2-digit', minute: '2-digit', hour12: false })
const fullFmt = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' })

function valid(value: string | number | Date | null | undefined): Date | null {
  if (value == null || value === '') return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

export function sameDay(a: Date, b: Date) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}

/** "Today", "Tomorrow", "Yesterday" or "Thu 19 Nov" (year added when it differs from now). */
export function dayLabel(value: string | Date, now = new Date()): string {
  const date = valid(value)
  if (!date) return '—'
  const tomorrow = new Date(now); tomorrow.setDate(now.getDate() + 1)
  const yesterday = new Date(now); yesterday.setDate(now.getDate() - 1)
  if (sameDay(date, now)) return 'Today'
  if (sameDay(date, tomorrow)) return 'Tomorrow'
  if (sameDay(date, yesterday)) return 'Yesterday'
  return (date.getFullYear() === now.getFullYear() ? dayFmt : dayYearFmt).format(date)
}

export function timeOf(value: string | Date): string {
  const date = valid(value)
  return date ? timeFmt.format(date) : '—'
}

/** "Thu 19 Nov, 10:15–11:00" (or "Today, 10:15–11:00"). */
export function slotLabel(start: string, minutes: number, now = new Date()): string {
  const begin = valid(start)
  if (!begin) return '—'
  const end = new Date(begin.getTime() + minutes * 60_000)
  return `${dayLabel(begin, now)}, ${timeFmt.format(begin)}–${timeFmt.format(end)}`
}

export function fullDateTime(value: string | null | undefined): string {
  const date = valid(value)
  return date ? fullFmt.format(date) : '—'
}

/** "India Standard Time (UTC+5:30)" for the viewer's zone. */
export function timeZoneLabel(date = new Date()): string {
  try {
    const long = new Intl.DateTimeFormat('en-IN', { timeZoneName: 'long' }).formatToParts(date).find(p => p.type === 'timeZoneName')?.value
    const offsetMin = -date.getTimezoneOffset()
    const sign = offsetMin >= 0 ? '+' : '−'
    const abs = Math.abs(offsetMin)
    const offset = `UTC${sign}${Math.floor(abs / 60)}${abs % 60 ? `:${String(abs % 60).padStart(2, '0')}` : ''}`
    return long ? `${long} (${offset})` : offset
  } catch {
    return 'your local time'
  }
}

/** A local `datetime-local` value `days` from now at `hour`:00. */
export function localInputInDays(days: number, hour = 18): string {
  const date = new Date()
  date.setDate(date.getDate() + days)
  date.setHours(hour, 0, 0, 0)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}
