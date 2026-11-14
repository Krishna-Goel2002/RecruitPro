import type {
  ApplicationStatus,
  ClaimType,
  EvidenceTier,
  RequirementType,
  RequirementVerification,
  ResumeAnalysisStatus,
  SurveyAgeBand,
  SurveyDisability,
  SurveyGender,
  SurveySocialCategory,
} from '../api/types'

export function humanize(value: string | null | undefined): string {
  if (!value) return ''
  const text = value.replace(/_/g, ' ').toLowerCase()
  return text.charAt(0).toUpperCase() + text.slice(1)
}

// ---------------------------------------------------------------------------------------------------------------------
// Applications
// ---------------------------------------------------------------------------------------------------------------------

export const APPLICATION_STATUS_LABEL: Record<ApplicationStatus, string> = {
  SUBMITTED: 'Submitted',
  SHORTLISTED: 'Shortlisted',
  INTERVIEWING: 'Interviewing',
  SELECTED: 'Selected',
  REJECTED: 'Not progressed',
  WITHDRAWN: 'Withdrawn',
}

export const APPLICATION_STATUS_DESCRIPTION: Record<ApplicationStatus, string> = {
  SUBMITTED: 'Application received by the employer.',
  SHORTLISTED: 'Shortlisted for further assessment.',
  INTERVIEWING: 'Moved to the interview stage.',
  SELECTED: 'Selected. The employer will contact you about next steps.',
  REJECTED: 'The employer decided not to progress this application.',
  WITHDRAWN: 'You withdrew this application.',
}

export const WITHDRAWABLE_STATUSES: readonly ApplicationStatus[] = ['SUBMITTED', 'SHORTLISTED', 'INTERVIEWING']

export function applicationStatusLabel(status: string): string {
  return status in APPLICATION_STATUS_LABEL ? APPLICATION_STATUS_LABEL[status as ApplicationStatus] : humanize(status)
}

// ---------------------------------------------------------------------------------------------------------------------
// Evidence ladder (D5)
// ---------------------------------------------------------------------------------------------------------------------

export const TIER_ORDER: readonly EvidenceTier[] = [
  'SELF_CLAIMED',
  'RESUME_STATED',
  'CORROBORATED',
  'DEMONSTRATED',
  'INTERVIEW_VERIFIED',
]

export const TIER_LABEL: Record<EvidenceTier, string> = {
  SELF_CLAIMED: 'Your claim',
  RESUME_STATED: 'On your resume',
  CORROBORATED: 'Corroborated',
  DEMONSTRATED: 'Demonstrated',
  INTERVIEW_VERIFIED: 'Verified in interview',
}

export const TIER_DESCRIPTION: Record<EvidenceTier, string> = {
  SELF_CLAIMED: 'Something you told us. Recruiters see it as unverified until they check it.',
  RESUME_STATED: 'Stated in a resume you uploaded.',
  CORROBORATED: 'Checked by a recruiter against your linked evidence or another source.',
  DEMONSTRATED: 'Shown in a work sample you completed.',
  INTERVIEW_VERIFIED: 'Confirmed by an interviewer.',
}

export const CLAIM_LABEL = 'Your claim — recruiters see it as unverified until they check it'

export const VERIFICATION_LABEL: Record<RequirementVerification, string> = {
  VERIFIED: 'Verified',
  CLAIMED: 'Your claim — unverified',
  NOT_VERIFIED: 'Not yet verified',
}

export const CLAIM_TYPES: readonly ClaimType[] = ['SKILL', 'EXPERIENCE', 'EDUCATION', 'CERTIFICATION', 'OTHER']

export const CLAIM_TYPE_LABEL: Record<ClaimType, string> = {
  SKILL: 'Skill',
  EXPERIENCE: 'Experience',
  EDUCATION: 'Education',
  CERTIFICATION: 'Certification',
  OTHER: 'Other',
}

export const REQUIREMENT_TYPE_LABEL: Record<RequirementType, string> = {
  SKILL: 'Skill',
  EXPERIENCE: 'Experience',
  EDUCATION: 'Education',
  CERTIFICATION: 'Certification',
  OTHER: 'Other',
}

export function claimTypeFor(type: string): ClaimType {
  return (CLAIM_TYPES as readonly string[]).includes(type) ? (type as ClaimType) : 'OTHER'
}

// ---------------------------------------------------------------------------------------------------------------------
// Jobs
// ---------------------------------------------------------------------------------------------------------------------

export const JOB_TYPE_OPTIONS = [
  { value: 'FULL_TIME', label: 'Full time' },
  { value: 'PART_TIME', label: 'Part time' },
  { value: 'CONTRACT', label: 'Contract' },
  { value: 'INTERNSHIP', label: 'Internship' },
] as const

export function jobTypeLabel(value: string): string {
  return JOB_TYPE_OPTIONS.find(option => option.value === value)?.label ?? humanize(value)
}

const EXPERIENCE_LABEL: Record<string, string> = {
  ENTRY: 'Entry level',
  MID: 'Mid level',
  SENIOR: 'Senior',
  LEAD: 'Lead',
  EXECUTIVE: 'Executive',
}

export function experienceLabel(value: string): string {
  return EXPERIENCE_LABEL[value] ?? humanize(value)
}

// ---------------------------------------------------------------------------------------------------------------------
// Resume analysis
// ---------------------------------------------------------------------------------------------------------------------

export const ANALYSIS_STATUS_LABEL: Record<ResumeAnalysisStatus, string> = {
  PENDING: 'Queued for analysis',
  PROCESSING: 'Analysing',
  COMPLETED: 'Analysis ready',
  PARTIAL: 'Partly analysed',
  FAILED: 'Analysis failed',
  NEEDS_OCR: 'Scanned document',
  UNSUPPORTED_FORMAT: 'Format not supported',
}

export const FACT_GROUPS: readonly { key: string; label: string; types: readonly string[]; correctable: boolean }[] = [
  { key: 'skills', label: 'Skills', types: ['SKILL'], correctable: true },
  { key: 'experience', label: 'Experience', types: ['EXPERIENCE'], correctable: true },
  { key: 'education', label: 'Education', types: ['EDUCATION'], correctable: true },
  { key: 'certifications', label: 'Certifications', types: ['CERTIFICATION'], correctable: true },
  { key: 'contact', label: 'Contact details and links', types: ['EMAIL', 'PHONE', 'LINK'], correctable: false },
]

export interface FlagExplanation {
  title: string
  text: string
}

/** Neutral explanations of parser flags (plan 6.1). These describe the file; they are not accusations. */
export function explainFlag(code: string, detail: string | null): FlagExplanation {
  switch (code) {
    case 'HIDDEN_TEXT':
      return {
        title: 'Some text is not visible in the document',
        text:
          'Part of the file is not shown when the document is displayed (for example white, very small or off-page text). ' +
          'That text was left out of the analysis. If this was not intentional, uploading a clean copy avoids confusion.',
      }
    case 'PROMPT_INJECTION_SUSPECTED':
      return {
        title: 'Wording that looks like instructions to software',
        text:
          'The file contains phrases that resemble instructions to an automated system (for example "ignore previous instructions"). ' +
          'They were read as ordinary text and do not change how your resume is assessed. Recruiters may see this note; ' +
          'if the wording was not meant to be there, you can upload an edited copy.',
      }
    case 'TWO_COLUMN_LAYOUT':
      return {
        title: 'Two-column layout',
        text: 'The document was read one column at a time. Check that the details below were picked up correctly.',
      }
    case 'OCR_USED':
      return {
        title: 'Text recognised from an image',
        text: 'The page was read with text recognition (OCR), which can make mistakes. Check the details below.',
      }
    case 'LEGACY_DOC':
      return {
        title: 'Older Word format (.doc)',
        text: 'Text extraction from .doc files is best effort. A PDF or .docx copy usually gives more reliable results.',
      }
    case 'LOW_TEXT_DENSITY':
      return {
        title: 'Very little readable text',
        text: 'Only a small amount of text could be read from this file. A text-based PDF or .docx copy may work better.',
      }
    default:
      return { title: humanize(code), text: detail ?? 'The analysis recorded a note about this file.' }
  }
}

export function analysisErrorText(errorCode: string | null): string {
  switch (errorCode) {
    case 'AI_SERVICE_UNAVAILABLE':
      return 'The analysis service was temporarily unavailable.'
    case 'ENCRYPTED_PDF':
    case 'ENCRYPTED_DOC':
      return 'The file is password-protected, so its text could not be read.'
    case 'EMPTY_DOCUMENT':
      return 'No text was found in the file.'
    case 'FILE_TOO_LARGE':
    case 'FILE_TOO_COMPLEX':
    case 'TEXT_LIMIT_EXCEEDED':
      return 'The file is too large or complex to analyse automatically.'
    case 'MALFORMED_PDF':
    case 'MALFORMED_DOCX':
    case 'MALFORMED_DOC':
      return 'The file appears to be damaged.'
    case 'PARSE_TIMEOUT':
    case 'PARSER_CRASHED':
      return 'The analysis took too long or stopped unexpectedly.'
    default:
      return 'The analysis could not be completed.'
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Work samples
// ---------------------------------------------------------------------------------------------------------------------

const WORK_SAMPLE_STATUS_LABEL: Record<string, string> = {
  ASSIGNED: 'Not started',
  STARTED: 'In progress',
  SUBMITTED: 'Submitted',
  SCORED: 'Reviewed',
  EXPIRED: 'Expired',
  CANCELLED: 'Cancelled',
}

export function workSampleStatusLabel(status: string): string {
  return WORK_SAMPLE_STATUS_LABEL[status] ?? humanize(status)
}

// ---------------------------------------------------------------------------------------------------------------------
// Equal-opportunity survey (5.5)
// ---------------------------------------------------------------------------------------------------------------------

export const GENDER_OPTIONS: readonly { value: SurveyGender; label: string }[] = [
  { value: 'WOMAN', label: 'Woman' },
  { value: 'MAN', label: 'Man' },
  { value: 'NON_BINARY', label: 'Non-binary' },
  { value: 'SELF_DESCRIBE', label: 'I describe myself another way' },
  { value: 'PREFER_NOT', label: 'Prefer not to say' },
]

export const AGE_BAND_OPTIONS: readonly { value: SurveyAgeBand; label: string }[] = [
  { value: 'UNDER_25', label: 'Under 25' },
  { value: '25_34', label: '25 to 34' },
  { value: '35_44', label: '35 to 44' },
  { value: '45_54', label: '45 to 54' },
  { value: '55_PLUS', label: '55 or over' },
  { value: 'PREFER_NOT', label: 'Prefer not to say' },
]

export const DISABILITY_OPTIONS: readonly { value: SurveyDisability; label: string }[] = [
  { value: 'YES', label: 'Yes' },
  { value: 'NO', label: 'No' },
  { value: 'PREFER_NOT', label: 'Prefer not to say' },
]

export const SOCIAL_CATEGORY_OPTIONS: readonly { value: SurveySocialCategory; label: string }[] = [
  { value: 'GENERAL', label: 'General' },
  { value: 'OBC', label: 'Other Backward Classes (OBC)' },
  { value: 'SC', label: 'Scheduled Caste (SC)' },
  { value: 'ST', label: 'Scheduled Tribe (ST)' },
  { value: 'EWS', label: 'Economically Weaker Section (EWS)' },
  { value: 'OTHER', label: 'Other' },
  { value: 'PREFER_NOT', label: 'Prefer not to say' },
]
