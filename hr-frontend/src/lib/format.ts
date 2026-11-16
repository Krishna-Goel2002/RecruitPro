import type { EvidenceTier, FairnessDimension, Role, WarningKind } from '../api/types'

/** "ON_HOLD" → "On hold". */
export function humanize(value: string | null | undefined): string {
  if (!value) return '—'
  const text = value.replace(/_/g, ' ').toLowerCase()
  return text.charAt(0).toUpperCase() + text.slice(1)
}

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })
const dateTimeFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })
const numberFormat = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 })

function parse(value: string | null | undefined): Date | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

/** Server times are UTC (`…Z`); Intl shows them in the viewer's local time. */
export function formatDate(value: string | null | undefined): string {
  const date = parse(value)
  return date ? dateFormat.format(date) : '—'
}

export function formatDateTime(value: string | null | undefined): string {
  const date = parse(value)
  return date ? dateTimeFormat.format(date) : '—'
}

export function formatNumber(value: number | null | undefined): string {
  return value == null ? '—' : numberFormat.format(value)
}

export function formatPercent(value: number | null | undefined, digits = 0): string {
  return value == null ? '—' : `${value.toFixed(digits)}%`
}

/** A 0–1 rate as a percentage. */
export function formatRate(value: number | null | undefined): string {
  return value == null ? '—' : `${(value * 100).toFixed(0)}%`
}

function pad(value: number) {
  return String(value).padStart(2, '0')
}

/** ISO (UTC) → `YYYY-MM-DDTHH:mm` in local time for `<input type="datetime-local">`. */
export function toLocalInput(value: string | null | undefined): string {
  const date = parse(value)
  if (!date) return ''
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** `datetime-local` value (local time) → ISO UTC string via toISOString(); empty → null. */
export function fromLocalInput(value: string): string | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}

/** `<input type="date">` value → ISO instant at the start or end of that local day. */
export function localDayBoundary(value: string, end: boolean): string | undefined {
  if (!value) return undefined
  const date = new Date(`${value}T${end ? '23:59:59.999' : '00:00:00'}`)
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString()
}

export function roleLabel(role: Role | string): string {
  switch (role) {
    case 'ADMIN': return 'Administrator'
    case 'HR_MANAGER': return 'HR manager'
    case 'RECRUITER': return 'Recruiter'
    case 'INTERVIEWER': return 'Interviewer'
    case 'CANDIDATE': return 'Candidate'
    default: return humanize(role)
  }
}

export const TIER_LABELS: Record<EvidenceTier, string> = {
  SELF_CLAIMED: 'Claimed',
  RESUME_STATED: 'Resume',
  CORROBORATED: 'Corroborated',
  DEMONSTRATED: 'Demonstrated',
  INTERVIEW_VERIFIED: 'Interview-verified',
}

export const TIER_DESCRIPTIONS: Record<EvidenceTier, string> = {
  SELF_CLAIMED: 'Only the candidate’s own claim supports this. Not counted as verified.',
  RESUME_STATED: 'Stated in the resume text.',
  CORROBORATED: 'Checked by a recruiter against supporting evidence.',
  DEMONSTRATED: 'Shown in a scored work sample (score 3 or 4).',
  INTERVIEW_VERIFIED: 'Confirmed in a structured interview (rating 3 or 4).',
}

export const WARNING_LABELS: Record<WarningKind, string> = {
  AGE_CODED: 'Age-coded',
  GENDER_CODED: 'Gender-coded',
  DEGREE_INFLATION: 'Degree inflation',
  EXCLUSIONARY: 'Exclusionary',
  VAGUE: 'Vague',
  EXCESSIVE_EXPERIENCE: 'Excessive experience',
}

/** Resume flags raised by the parser (WP-PY), in plain language. Unknown codes fall back to humanize(). */
const FLAG_LABELS: Record<string, { label: string; description: string }> = {
  HIDDEN_TEXT: { label: 'Hidden text removed', description: 'The resume contained white, tiny or off-page text. It was excluded from analysis.' },
  PROMPT_INJECTION_SUSPECTED: { label: 'Instructions to screener', description: 'The resume contains text that tries to instruct the screening system. It was treated as plain data.' },
  TWO_COLUMN_LAYOUT: { label: 'Two-column layout', description: 'The resume uses two columns; reading order was reconstructed. Check the evidence quotes.' },
  OCR_USED: { label: 'Scanned document', description: 'Text was recovered with OCR and may contain recognition errors.' },
  LEGACY_DOC: { label: 'Legacy .doc file', description: 'Older Word format; hidden formatting may not be detected.' },
  LOW_TEXT_DENSITY: { label: 'Little text found', description: 'Very little text could be read from the resume.' },
}

export function flagInfo(code: string): { label: string; description: string } {
  return FLAG_LABELS[code] ?? { label: humanize(code), description: humanize(code) }
}

export const DIMENSION_LABELS: Record<FairnessDimension, string> = {
  gender: 'Gender',
  ageBand: 'Age band',
  disability: 'Disability',
  socialCategory: 'Social category',
}

const GROUP_LABELS: Record<string, string> = {
  WOMAN: 'Woman', MAN: 'Man', NON_BINARY: 'Non-binary', SELF_DESCRIBE: 'Self-described',
  UNDER_25: 'Under 25', '25_34': '25–34', '35_44': '35–44', '45_54': '45–54', '55_PLUS': '55+',
  YES: 'Yes', NO: 'No', GENERAL: 'General', OBC: 'OBC', SC: 'SC', ST: 'ST', EWS: 'EWS', OTHER: 'Other',
}

export function groupLabel(group: string): string {
  return GROUP_LABELS[group] ?? humanize(group)
}

export function fullName(person: { firstName?: string | null; lastName?: string | null; email?: string }): string {
  return [person.firstName, person.lastName].filter(Boolean).join(' ').trim() || person.email || '—'
}

/** Only http(s) links are rendered as anchors. */
export function safeHttpUrl(value: string | null | undefined): string | null {
  if (!value) return null
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.toString() : null
  } catch {
    return null
  }
}

// passwordProblem (ASCII-only letters, contradicting the server's Unicode policy) was removed: use
// components/PasswordChecklist › passwordError.
