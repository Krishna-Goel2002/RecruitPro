/** Interview times in plain words, and an "Add to calendar" file generated in the browser (no server call). */
import { saveBlob } from './download'

/** "IST (UTC+5:30)" style label for the viewer's zone, instead of the raw IANA name (UX X-26). */
export function timeZoneLabel(date = new Date()): string {
  const short = new Intl.DateTimeFormat('en-IN', { timeZoneName: 'short' }).formatToParts(date).find(p => p.type === 'timeZoneName')?.value
  const offsetMin = -date.getTimezoneOffset()
  const sign = offsetMin >= 0 ? '+' : '−'
  const h = Math.floor(Math.abs(offsetMin) / 60)
  const m = Math.abs(offsetMin) % 60
  const utc = `UTC${sign}${h}${m ? `:${String(m).padStart(2, '0')}` : ''}`
  return short && !short.startsWith('GMT') && !short.startsWith('UTC') ? `${short} (${utc})` : utc
}

/** "Thu 19 Nov · 10:15 – 11:00" */
export function timeRange(startIso: string, minutes: number): { day: string; time: string } {
  const start = new Date(startIso)
  const end = new Date(start.getTime() + minutes * 60_000)
  const day = new Intl.DateTimeFormat('en-IN', { weekday: 'short', day: 'numeric', month: 'short' }).format(start)
  const t = new Intl.DateTimeFormat('en-IN', { hour: 'numeric', minute: '2-digit' })
  return { day, time: `${t.format(start)} – ${t.format(end)}` }
}

const stamp = (d: Date) => d.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '')
const escape = (s: string) => s.replace(/\\/g, '\\\\').replace(/[,;]/g, m => `\\${m}`).replace(/\n/g, '\\n')

/** Downloads an RFC 5545 event for one interview. */
export function downloadInterviewIcs(interview: { id: number; scheduledAt: string; durationMinutes: number; location: string | null }, title: string) {
  const start = new Date(interview.scheduledAt)
  const end = new Date(start.getTime() + interview.durationMinutes * 60_000)
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//candidate-portal//interview//EN',
    'CALSCALE:GREGORIAN',
    'BEGIN:VEVENT',
    `UID:interview-${interview.id}@candidate-portal`,
    `DTSTAMP:${stamp(new Date())}`,
    `DTSTART:${stamp(start)}`,
    `DTEND:${stamp(end)}`,
    `SUMMARY:${escape(`Interview: ${title}`)}`,
    interview.location ? `LOCATION:${escape(interview.location)}` : '',
    'END:VEVENT',
    'END:VCALENDAR',
  ].filter(Boolean)
  saveBlob(new Blob([lines.join('\r\n')], { type: 'text/calendar' }), `interview-${interview.id}.ics`)
}
