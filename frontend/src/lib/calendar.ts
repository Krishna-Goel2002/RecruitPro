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
/** RFC 5545 TEXT escaping; CR/LF pairs become one escaped newline (a bare CR used to end up inside the value). */
export const escapeIcsText = (s: string) => s.replace(/\\/g, '\\\\').replace(/[,;]/g, m => `\\${m}`).replace(/\r\n|\r|\n/g, '\\n')

/** Folds a content line at 75 octets (RFC 5545 §3.1), continuing with CRLF + one space. */
export function foldIcsLine(line: string): string {
  const encoder = new TextEncoder()
  if (encoder.encode(line).length <= 75) return line
  const parts: string[] = []
  let current = ''
  let size = 0
  for (const char of line) {
    const bytes = encoder.encode(char).length
    if (size + bytes > (parts.length === 0 ? 75 : 74)) {
      parts.push(current)
      current = ''
      size = 0
    }
    current += char
    size += bytes
  }
  parts.push(current)
  return parts.join('\r\n ')
}

/** The .ics text for one interview (exported for tests). */
export function interviewIcs(interview: { id: number; scheduledAt: string; durationMinutes: number; location: string | null }, title: string, now = new Date()): string {
  const start = new Date(interview.scheduledAt)
  const end = new Date(start.getTime() + interview.durationMinutes * 60_000)
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//candidate-portal//interview//EN',
    'CALSCALE:GREGORIAN',
    'BEGIN:VEVENT',
    `UID:interview-${interview.id}@candidate-portal`,
    `DTSTAMP:${stamp(now)}`,
    `DTSTART:${stamp(start)}`,
    `DTEND:${stamp(end)}`,
    `SUMMARY:${escapeIcsText(`Interview: ${title}`)}`,
    interview.location ? `LOCATION:${escapeIcsText(interview.location)}` : '',
    'END:VEVENT',
    'END:VCALENDAR',
  ].filter(Boolean)
  return `${lines.map(foldIcsLine).join('\r\n')}\r\n`
}

/** Downloads an RFC 5545 event for one interview. */
export function downloadInterviewIcs(interview: { id: number; scheduledAt: string; durationMinutes: number; location: string | null }, title: string) {
  saveBlob(new Blob([interviewIcs(interview, title)], { type: 'text/calendar' }), `interview-${interview.id}.ics`)
}

