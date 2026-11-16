/**
 * Plain-language labels for the verification flows (plan v2 Sections 1 and 7; WP-VBE notes 2).
 * Principle 3: "Not found" is neutral and is always worded as "not found in connected sources".
 */
import type {
  CandidateVerdictStatus,
  OwnershipCheckMethod,
  OwnershipMethod,
  OwnershipStatus,
  RefereeAnswer,
  ReferenceRelationship,
  ReferenceStatus,
  RunStatus,
  SourceKind,
  VerdictValue,
} from '../api/types'
import { humanize } from './labels'

// ---------------------------------------------------------------------------------------------------------------------
// Sources and ownership
// ---------------------------------------------------------------------------------------------------------------------

export const SOURCE_KIND_LABEL: Record<SourceKind | 'REFERENCE', string> = {
  GITHUB: 'GitHub',
  GITLAB: 'GitLab',
  WEBSITE: 'Website',
  DOCUMENT: 'Document',
  OPEN_BADGE: 'Open Badge',
  REFERENCE: 'Reference',
}

export function sourceKindLabel(kind: string | null | undefined): string {
  if (!kind) return 'Source'
  return kind in SOURCE_KIND_LABEL ? SOURCE_KIND_LABEL[kind as SourceKind] : humanize(kind)
}

export const OWNERSHIP_STATUS_LABEL: Record<OwnershipStatus, string> = {
  UNPROVEN: 'Ownership not proven yet',
  PENDING: 'Ownership check in progress',
  PROVEN: 'Ownership proven',
  FAILED: 'Ownership check did not succeed',
}

export function ownershipStatusLabel(status: string): string {
  return status in OWNERSHIP_STATUS_LABEL ? OWNERSHIP_STATUS_LABEL[status as OwnershipStatus] : humanize(status)
}

export const OWNERSHIP_METHOD_LABEL: Record<OwnershipMethod, string> = {
  OAUTH: 'GitHub sign-in',
  DNS_TXT: 'DNS TXT record',
  WELL_KNOWN_FILE: 'File on your website',
  PAGE_CODE: 'Code on a web page',
  PROFILE_BIO_CODE: 'Code in your profile bio',
  ISSUER_SIGNATURE: "Issuer's signature",
}

export function ownershipMethodLabel(method: string | null | undefined): string {
  if (!method) return ''
  return method in OWNERSHIP_METHOD_LABEL ? OWNERSHIP_METHOD_LABEL[method as OwnershipMethod] : humanize(method)
}

/**
 * Methods `POST /sources/{id}/ownership-check` accepts for each kind. Documents cannot be proven; badges are proven
 * by the issuer's signature during a run; GitHub can also be proven by signing in (the connect button).
 */
export function ownershipMethodsFor(kind: string): OwnershipCheckMethod[] {
  switch (kind) {
    case 'GITHUB':
    case 'GITLAB':
      return ['PROFILE_BIO_CODE']
    case 'WEBSITE':
      return ['DNS_TXT', 'WELL_KNOWN_FILE', 'PAGE_CODE']
    default:
      return []
  }
}

/** Why a kind has no ownership check, for the source list. */
export function noOwnershipCheckReason(kind: string): string | null {
  if (kind === 'DOCUMENT') return 'Ownership of an uploaded document cannot be proven, so its results are at most "Partly verified".'
  if (kind === 'OPEN_BADGE') return "A badge is proven by its issuer's signature when you run a verification."
  return null
}

export function hostOf(url: string): string | null {
  try {
    return new URL(url).hostname || null
  } catch {
    return null
  }
}

export interface ChallengeStep {
  method: OwnershipCheckMethod
  title: string
  steps: string[]
  /** Values with a copy button. */
  copy: { label: string; value: string }[]
}

/** Exact values to publish for each ownership method (agent contract, plan v2 Section 4). */
export function challengeSteps(kind: string, identifier: string, code: string): ChallengeStep[] {
  if (kind === 'GITHUB' || kind === 'GITLAB') {
    const site = kind === 'GITHUB' ? 'GitHub' : 'GitLab'
    return [
      {
        method: 'PROFILE_BIO_CODE',
        title: `Add a code to your ${site} profile bio`,
        steps: [
          `Open your ${site} profile settings and paste the code below anywhere in your bio.`,
          'Save your profile, then choose "Check now". You can remove the code once ownership is proven.',
        ],
        copy: [{ label: 'Code', value: code }],
      },
    ]
  }
  if (kind === 'WEBSITE') {
    const host = hostOf(identifier) ?? identifier
    return [
      {
        method: 'DNS_TXT',
        title: 'Add a DNS TXT record',
        steps: [`In your domain's DNS settings, add a TXT record on ${host} with the value below.`, 'DNS changes can take a while to appear.'],
        copy: [
          { label: 'Host', value: host },
          { label: 'TXT value', value: `recruitpro-verification=${code}` },
        ],
      },
      {
        method: 'WELL_KNOWN_FILE',
        title: 'Upload a small text file',
        steps: ['Publish a plain-text file at the address below that contains only the code.'],
        copy: [
          { label: 'File address', value: `https://${host}/.well-known/recruitpro-verification.txt` },
          { label: 'File content', value: code },
        ],
      },
      {
        method: 'PAGE_CODE',
        title: 'Put the code on your page',
        steps: [`Add the code to the page at ${identifier}, as visible text or in a meta tag in the page head.`],
        copy: [
          { label: 'Code', value: code },
          { label: 'Meta tag', value: `<meta name="recruitpro-verification" content="${code}">` },
        ],
      },
    ]
  }
  return []
}

// ---------------------------------------------------------------------------------------------------------------------
// Runs
// ---------------------------------------------------------------------------------------------------------------------

export function isRunActive(status: string | null | undefined): status is 'PENDING' | 'RUNNING' {
  return status === 'PENDING' || status === 'RUNNING'
}

export const RUN_STATUS_LABEL: Record<RunStatus, string> = {
  PENDING: 'Waiting to start',
  RUNNING: 'Checking now',
  COMPLETED: 'Finished',
  FAILED: 'Did not finish',
}

export function runStatusLabel(status: string | null | undefined): string {
  if (!status) return 'Not checked yet'
  return status in RUN_STATUS_LABEL ? RUN_STATUS_LABEL[status as RunStatus] : humanize(status)
}

const RUN_ERROR_TEXT: Record<string, string> = {
  AGENT_SERVICE_UNAVAILABLE: 'The verification service was unavailable. Please try again later.',
  AGENT_SERVICE_NOT_CONFIGURED: 'Verification is not set up on this server yet.',
  AGENT_SERVICE_REJECTED: 'The verification service could not process this source.',
  EVIDENCE_HASH_MISMATCH: 'The collected evidence could not be confirmed as intact, so nothing was stored. Please run the check again.',
  INVALID_EVIDENCE: 'The collected evidence was incomplete, so nothing was stored. Please run the check again.',
  FILE_UNAVAILABLE: 'The uploaded document is no longer available. Please upload it again.',
  SOURCE_REVOKED: 'The source was revoked while the check was running.',
}

export function runErrorText(code: string | null | undefined): string {
  if (!code) return 'The check did not finish. Please try again.'
  return RUN_ERROR_TEXT[code] ?? `The check did not finish (${code}). Please try again later.`
}

// ---------------------------------------------------------------------------------------------------------------------
// GitHub OAuth redirect (`/verification?connected=github` or `?error=<CODE>`)
// ---------------------------------------------------------------------------------------------------------------------

export const OAUTH_ERROR_TEXT: Record<string, string> = {
  OAUTH_NOT_CONFIGURED:
    'Signing in with GitHub is not available on this server. You can still connect your GitHub username and prove it is yours with a code in your profile bio.',
  STATE_INVALID: 'The GitHub connection could not be matched to your request. Please start again from this page.',
  STATE_EXPIRED: 'The GitHub connection took more than 10 minutes and expired. Please try again.',
  STATE_USED: 'This GitHub connection link was already used. If GitHub is not in your list, please try again.',
  BROWSER_MISMATCH:
    'This GitHub connection was not started in this browser (or its cookie was blocked), so nothing was connected. Please start again from this page.',
  ACCESS_DENIED: 'You cancelled the GitHub authorisation, so nothing was connected.',
  CODE_MISSING: 'GitHub did not send back an authorisation code. Please try again.',
  CODE_REJECTED: 'GitHub did not accept the authorisation. Please try again.',
  GITHUB_UNAVAILABLE: 'GitHub could not be reached. Please try again in a few minutes.',
  CONNECT_FAILED: 'Your GitHub account could not be connected. Please try again, or connect with your username instead.',
  RATE_LIMITED: 'There were too many connection attempts. Please wait a few minutes and try again.',
}

export function oauthErrorMessage(code: string): string {
  const known = OAUTH_ERROR_TEXT[code.trim().toUpperCase()]
  if (known) return known
  // Never echo an arbitrary query value at length.
  const safe = code.replace(/[^A-Za-z0-9_]/g, '').slice(0, 40)
  return `Connecting GitHub did not work${safe ? ` (${safe})` : ''}. Please try again.`
}

export const OAUTH_CONNECTED_TEXT = 'GitHub is connected and ownership is proven. You can now run a verification.'

/**
 * True when an API error is the 503 `OAUTH_NOT_CONFIGURED: …` for an unconfigured GitHub OAuth app. Any other 503
 * (gateway, backend restarting) is an ordinary outage and must not switch the candidate to the username route.
 */
export function isOAuthNotConfigured(error: { status: number; message: string; code?: string | null }): boolean {
  return error.code === 'OAUTH_NOT_CONFIGURED' || error.message.trim().startsWith('OAUTH_NOT_CONFIGURED')
}

// ---------------------------------------------------------------------------------------------------------------------
// Verdicts
// ---------------------------------------------------------------------------------------------------------------------

export type VerdictTone = 'positive' | 'partial' | 'neutral' | 'negative'

export interface VerdictInfo {
  label: string
  /** Text symbol shown with the label so meaning never depends on colour alone. */
  symbol: string
  tone: VerdictTone
  description: string
}

export const VERDICT_INFO: Record<VerdictValue, VerdictInfo> = {
  VERIFIED: {
    label: 'Verified',
    symbol: '✓',
    tone: 'positive',
    description: 'A source you proved you own clearly supports this claim.',
  },
  PARTLY_VERIFIED: {
    label: 'Partly verified',
    symbol: '◐',
    tone: 'partial',
    description: 'The source supports this claim in part, or ownership of the source is not proven.',
  },
  NOT_FOUND: {
    label: 'Not found in connected sources',
    symbol: '○',
    tone: 'neutral',
    description:
      'Nothing about this claim was found in the connected source. This is neutral: it never lowers your score or ranking.',
  },
  CONTRADICTED: {
    label: 'Contradicted',
    symbol: '✕',
    tone: 'negative',
    description:
      'The source states something different from this claim. You decide whether to share it; you can hide it or re-check with a note.',
  },
  NOT_CHECKABLE: {
    label: 'Not checkable by agents',
    symbol: '–',
    tone: 'neutral',
    description: 'This kind of claim cannot be checked automatically with this source. This is neutral.',
  },
}

export function verdictInfo(verdict: string | null | undefined): VerdictInfo {
  if (verdict && verdict in VERDICT_INFO) return VERDICT_INFO[verdict as VerdictValue]
  return { label: humanize(verdict) || 'Unknown result', symbol: '?', tone: 'neutral', description: '' }
}

export const NOT_FOUND_EXPLANATION =
  '"Not found in connected sources" is neutral. It only means the connected sources did not mention the claim; it never counts against you.'

export const CANDIDATE_STATUS_LABEL: Record<CandidateVerdictStatus, string> = {
  AWAITING_CANDIDATE: 'Awaiting your review',
  SHARED: 'Shared',
  HIDDEN: 'Hidden',
  WITHDRAWN: 'Withdrawn',
  SUPERSEDED: 'Replaced by a newer check',
}

export const CANDIDATE_STATUS_DESCRIPTION: Record<CandidateVerdictStatus, string> = {
  AWAITING_CANDIDATE: 'Only you can see this result until you share it.',
  SHARED: 'Employers you applied to can see this result.',
  HIDDEN: 'Employers cannot see this result.',
  WITHDRAWN: 'The source was revoked, so this result was withdrawn.',
  SUPERSEDED: 'A newer check of the same source replaced this result.',
}

export function candidateStatusLabel(status: string): string {
  return status in CANDIDATE_STATUS_LABEL ? CANDIDATE_STATUS_LABEL[status as CandidateVerdictStatus] : humanize(status)
}

export function candidateStatusDescription(status: string): string {
  return status in CANDIDATE_STATUS_DESCRIPTION ? CANDIDATE_STATUS_DESCRIPTION[status as CandidateVerdictStatus] : ''
}

/** "sha256:abcdef…" → "abcdef12…" (first 12 hex characters) for display; the full hash is in the title attribute. */
export function shortHash(hash: string | null | undefined): string {
  if (!hash) return ''
  const hex = hash.replace(/^sha256:/i, '')
  return hex.length > 12 ? `${hex.slice(0, 12)}…` : hex
}

export const INTEGRITY_NOTE_INTRO =
  'Notes for context. These describe patterns the checker noticed in the source. They are not accusations and do not change the result; an interviewer may simply ask about them.'

// ---------------------------------------------------------------------------------------------------------------------
// References
// ---------------------------------------------------------------------------------------------------------------------

export const MAX_OPEN_REFERENCES = 5
/** Server rules (ReferenceService.MAX_PER_DAY, VerificationSourceService.MAX_ACTIVE_SOURCES). */
export const MAX_REFERENCES_PER_DAY = 10
export const MAX_ACTIVE_SOURCES = 30

export const RELATIONSHIP_OPTIONS: { value: ReferenceRelationship; label: string }[] = [
  { value: 'MANAGER', label: 'Manager' },
  { value: 'PEER', label: 'Colleague' },
  { value: 'REPORT', label: 'Someone who reported to you' },
  { value: 'CLIENT', label: 'Client' },
  { value: 'TEACHER', label: 'Teacher or lecturer' },
  { value: 'OTHER', label: 'Other' },
]

export function relationshipLabel(value: string): string {
  return RELATIONSHIP_OPTIONS.find(option => option.value === value)?.label ?? humanize(value)
}

/** How the referee relates to the candidate, worded from the referee's side ("their manager"). */
export function refereeRelationshipLabel(value: string): string {
  switch (value) {
    case 'MANAGER':
      return 'their manager'
    case 'PEER':
      return 'a colleague'
    case 'REPORT':
      return 'someone who reported to them'
    case 'CLIENT':
      return 'a client'
    case 'TEACHER':
      return 'their teacher or lecturer'
    default:
      return 'someone who knows their work'
  }
}

export const REFERENCE_STATUS_LABEL: Record<ReferenceStatus, string> = {
  SENT: 'Waiting for an answer',
  ANSWERED: 'Answered',
  DECLINED: 'Declined',
  EXPIRED: 'Expired',
  CANCELLED: 'Cancelled',
}

export function referenceStatusLabel(status: string): string {
  return status in REFERENCE_STATUS_LABEL ? REFERENCE_STATUS_LABEL[status as ReferenceStatus] : humanize(status)
}

export const REFEREE_ANSWER_OPTIONS: { value: RefereeAnswer; label: string; description: string }[] = [
  { value: 'CONFIRM', label: 'Confirm', description: 'I know this is true.' },
  { value: 'PARTLY', label: 'Partly', description: 'It is partly true, or true with some limits.' },
  { value: 'CANNOT_CONFIRM', label: 'Cannot confirm', description: 'I do not know enough to say.' },
  { value: 'DISAGREE', label: 'Disagree', description: 'I believe this is not accurate.' },
]
