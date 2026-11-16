/**
 * Every API shape used by the candidate portal, written against IMPLEMENTATION_PLAN.md Section 5.
 *
 * Keep all request/response types here so that reconciling with the backend is a change to this file
 * (and, where a backend is still partial, to the small normalisers in `endpoints.ts`), not to the pages.
 * Timestamps are ISO-8601 UTC strings ending in `Z` (D15).
 *
 * The backend serialises with `default-property-inclusion: non_null`, so a field typed `T | null` here may be
 * ABSENT on the wire. The normalisers in `endpoints.ts` turn absent values into `null` where pages compare
 * against null; elsewhere pages only test truthiness.
 */

/** ISO-8601 timestamp in UTC, e.g. `2026-11-08T10:15:30Z`. */
export type IsoDateTime = string

// ---------------------------------------------------------------------------------------------------------------------
// Envelope and paging (Section 5, preamble)
// ---------------------------------------------------------------------------------------------------------------------

export interface ApiFieldError {
  field: string
  message: string
}

/** `{ success, message?, data?, errors?, timestamp }` - null fields are omitted by the backend. */
export interface ApiEnvelope<T> {
  success: boolean
  message?: string | null
  data?: T | null
  errors?: ApiFieldError[] | null
  timestamp?: IsoDateTime
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

// ---------------------------------------------------------------------------------------------------------------------
// Shared types (5.1)
// ---------------------------------------------------------------------------------------------------------------------

export type Role = 'ADMIN' | 'HR_MANAGER' | 'RECRUITER' | 'INTERVIEWER' | 'CANDIDATE'
export type EvidenceTier = 'SELF_CLAIMED' | 'RESUME_STATED' | 'CORROBORATED' | 'DEMONSTRATED' | 'INTERVIEW_VERIFIED'
export type ApplicationStatus = 'SUBMITTED' | 'SHORTLISTED' | 'INTERVIEWING' | 'SELECTED' | 'REJECTED' | 'WITHDRAWN'
export type RequirementType = 'SKILL' | 'EDUCATION' | 'CERTIFICATION' | 'EXPERIENCE' | 'OTHER'
export type Proficiency = 'BASIC' | 'INTERMEDIATE' | 'ADVANCED' | 'EXPERT'
export type JobType = 'FULL_TIME' | 'PART_TIME' | 'CONTRACT' | 'INTERNSHIP'
export type ExperienceLevel = 'ENTRY' | 'MID' | 'SENIOR' | 'LEAD' | 'EXECUTIVE'

/** AuthUser. `organizationId`/`organizationName` are absent for candidates (backend omits nulls). */
export interface AuthUser {
  id: number
  email: string
  firstName: string
  lastName: string
  role: Role
  organizationId?: number | null
  organizationName?: string | null
  emailVerified: boolean
}

export interface AuthResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresInSeconds: number
  user: AuthUser
}

export interface Requirement {
  id: number
  type: RequirementType
  description: string
  mandatory: boolean
  knockout: boolean
  minimumYears: number | null
  alternatives: string[]
  proficiency: Proficiency | null
  /** Position the employer set (0-based; integration pass). Lists arrive in this order; older servers omit it. */
  displayOrder?: number
}

/** What a candidate sees of a job (`GET /jobs`, `GET /jobs/{id}` as CANDIDATE). */
export interface JobPublic {
  id: number
  organizationName: string
  title: string
  description: string
  department: string
  location: string
  jobType: JobType | string
  experienceLevel: ExperienceLevel | string
  minimumSalary: number | null
  maximumSalary: number | null
  vacancies: number
  deadline: IsoDateTime | null
  publishedAt: IsoDateTime | null
  requirements: Requirement[]
}

export interface JobSearchParams {
  page: number
  size: number
  search?: string
  department?: string
  location?: string
  jobType?: JobType | ''
}

// ---------------------------------------------------------------------------------------------------------------------
// Public and account endpoints (5.2)
// ---------------------------------------------------------------------------------------------------------------------

export interface LoginRequest {
  email: string
  password: string
}

export interface RegisterRequest {
  firstName: string
  lastName: string
  email: string
  password: string
}

export interface ResetPasswordRequest {
  token: string
  newPassword: string
}

export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

export interface AppNotification {
  id: number
  template: string
  subject: string
  body: string
  createdAt: IsoDateTime
  readAt: IsoDateTime | null
}

export interface UnreadCount {
  count: number
}

// ---------------------------------------------------------------------------------------------------------------------
// Candidate (5.5)
// ---------------------------------------------------------------------------------------------------------------------

/** Candidate profile without date of birth or gender (D14). Extra fields the backend still sends are ignored. */
export interface CandidateProfile {
  id: number
  email: string
  firstName: string
  lastName: string
  address: string | null
  city: string | null
  state: string | null
  country: string | null
  zipCode: string | null
  linkedinUrl: string | null
  portfolioUrl: string | null
  yearsOfExperience: number | null
  currentCompany: string | null
  currentDesignation: string | null
  expectedSalary: number | null
  noticePeriodDays: number | null
  profileSummary: string | null
  createdAt: IsoDateTime
  updatedAt: IsoDateTime
}

export type CandidateProfileUpdate = Omit<CandidateProfile, 'id' | 'email' | 'firstName' | 'lastName' | 'createdAt' | 'updatedAt'>

export interface Resume {
  id: number
  originalFilename: string
  contentType: string
  fileSize: number
  uploadedAt: IsoDateTime
}

export type ResumeAnalysisStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'PARTIAL'
  | 'FAILED'
  | 'NEEDS_OCR'
  | 'UNSUPPORTED_FORMAT'

export type ResumeFactType = 'SKILL' | 'EDUCATION' | 'EXPERIENCE' | 'CERTIFICATION' | 'EMAIL' | 'PHONE' | 'LINK'

/**
 * A fact extracted from a resume. Plan 5.5: facts gain `tier` and `claimedValue`; the extracted value is never
 * overwritten - a candidate correction becomes a SELF_CLAIMED claim whose value is `claimedValue`.
 */
export interface ResumeFact {
  id: number
  type: ResumeFactType | string
  value: string
  extractedValue: string
  evidence: string | null
  sourceSection: string | null
  sourceReference?: string | null
  tier?: EvidenceTier | null
  /** The candidate's correction (a SELF_CLAIMED claim). May equal `extractedValue` if the fact was confirmed. */
  claimedValue?: string | null
  /** WP-BE-B addition: id of the claim created by the correction. */
  claimId?: number | null
}

export type ResumeFlagCode =
  | 'HIDDEN_TEXT'
  | 'PROMPT_INJECTION_SUSPECTED'
  | 'TWO_COLUMN_LAYOUT'
  | 'OCR_USED'
  | 'LEGACY_DOC'
  | 'LOW_TEXT_DENSITY'

/** Parser flag (plan 6.1 `ParseResponse.flags`, stored as `resume_analyses.flags_json` by V17). */
export interface ResumeFlag {
  code: ResumeFlagCode | string
  detail: string | null
}

export interface ResumeAnalysis {
  resumeId: number
  status: ResumeAnalysisStatus
  parserVersion: string | null
  errorCode: string | null
  attemptCount: number
  analyzedAt: IsoDateTime | null
  facts: ResumeFact[]
  /** Assumption: exposed by WP-BE-B from `flags_json`; absent today. */
  flags?: ResumeFlag[] | null
}

export interface FactCorrectionRequest {
  value: string
}

export type ClaimType = 'SKILL' | 'CERTIFICATION' | 'EDUCATION' | 'EXPERIENCE' | 'OTHER'

export type ClaimVerificationStatus = 'UNVERIFIED' | 'VERIFIED' | 'REJECTED'

export interface CandidateClaim {
  id: number
  claimType: ClaimType | string
  value: string
  evidenceUrl: string | null
  tier: EvidenceTier
  verifiedAt: IsoDateTime | null
  /** WP-BE-B: always omitted in the candidate's own view. */
  verificationNote: string | null
  createdAt: IsoDateTime
  /** WP-BE-B additions (not in plan 5.4). */
  verificationStatus?: ClaimVerificationStatus
  sourceFactId?: number | null
  updatedAt?: IsoDateTime
}

export interface ClaimCreateRequest {
  claimType: ClaimType
  value: string
  evidenceUrl?: string | null
}

export interface ApplyRequest {
  resumeId: number
  coverLetter?: string | null
}

export interface Application {
  id: number
  jobId: number
  jobTitle: string
  organizationName: string
  resumeId: number
  resumeFilename: string
  status: ApplicationStatus
  coverLetter: string | null
  appliedAt: IsoDateTime
  updatedAt: IsoDateTime
  statusChangedAt: IsoDateTime | null
}

export interface ApplicationTimelineEntry {
  status: ApplicationStatus
  at: IsoDateTime
}

export type RequirementVerification = 'VERIFIED' | 'CLAIMED' | 'NOT_VERIFIED'

export interface ApplicationRequirement {
  requirementId: number
  type: RequirementType
  description: string
  mandatory: boolean
  verification: RequirementVerification
}

export interface ApplicationInterview {
  id: number
  scheduledAt: IsoDateTime
  durationMinutes: number
  location: string | null
  status: string
}

export interface ApplicationDetail extends Application {
  timeline: ApplicationTimelineEntry[]
  /**
   * null when the organisation does not share verification status (plan), and - WP-BE-B - also once the
   * application is no longer SUBMITTED, SHORTLISTED or INTERVIEWING.
   */
  requirements: ApplicationRequirement[] | null
  workSamples: CandidateWorkSample[]
  interviews: ApplicationInterview[]
}

// ---------------------------------------------------------------------------------------------------------------------
// Work samples and interviews (5.5, 5.6)
// ---------------------------------------------------------------------------------------------------------------------

export type WorkSampleStatus = 'ASSIGNED' | 'STARTED' | 'SUBMITTED' | 'SCORED' | 'EXPIRED' | 'CANCELLED'

/** A file attached to a work-sample submission (WP-PLAT). `sha256` is plain hex. */
export interface WorkSampleFile {
  id: number
  originalName: string
  contentType: string
  sizeBytes: number
  sha256: string
  uploadedAt: IsoDateTime
}

export interface CandidateWorkSample {
  assignmentId: number
  jobTitle: string
  title: string
  /** Hidden (null) until the candidate presses Start. */
  instructions: string | null
  timeLimitMinutes: number
  status: WorkSampleStatus | string
  dueAt: IsoDateTime | null
  startedAt: IsoDateTime | null
  submittedAt: IsoDateTime | null
  deadlineAt: IsoDateTime | null
  /** WP-PLAT: submitted files (normalised to [] when absent). */
  files: WorkSampleFile[]
  /** WP-PLAT: set when the employer cancelled the assignment. */
  cancelledAt: IsoDateTime | null
  cancelReason: string | null
  /** The application this task belongs to (integration pass; older servers omit it). */
  applicationId?: number
}

/**
 * Body of the work-sample submission. Sent as multipart/form-data (WP-PLAT): `responseText` (optional when at least
 * one file is attached), `responseUrl` (optional) and up to three `files` parts.
 */
export interface WorkSampleSubmitRequest {
  responseText: string
  responseUrl?: string | null
  files?: File[]
}

export interface CandidateInterview {
  id: number
  jobTitle: string
  scheduledAt: IsoDateTime
  durationMinutes: number
  location: string | null
  status: 'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | string
  /** The application this interview belongs to (integration pass; older servers omit it). */
  applicationId?: number
}

// ---------------------------------------------------------------------------------------------------------------------
// Equal-opportunity survey and privacy (5.5)
// ---------------------------------------------------------------------------------------------------------------------

export type SurveyGender = 'WOMAN' | 'MAN' | 'NON_BINARY' | 'SELF_DESCRIBE' | 'PREFER_NOT'
export type SurveyAgeBand = 'UNDER_25' | '25_34' | '35_44' | '45_54' | '55_PLUS' | 'PREFER_NOT'
export type SurveyDisability = 'YES' | 'NO' | 'PREFER_NOT'
export type SurveySocialCategory = 'GENERAL' | 'OBC' | 'SC' | 'ST' | 'EWS' | 'OTHER' | 'PREFER_NOT'

export interface EqualOpportunityAnswers {
  gender?: SurveyGender | null
  ageBand?: SurveyAgeBand | null
  disability?: SurveyDisability | null
  socialCategory?: SurveySocialCategory | null
}

/** Body of `PUT /candidates/me/equal-opportunity`; `consent` must be true. */
export interface EqualOpportunityRequest extends EqualOpportunityAnswers {
  consent: true
}

/** Assumption: GET returns the stored answers plus `consentGivenAt`, or 404 / null data when none are stored. */
export interface EqualOpportunityResponse extends EqualOpportunityAnswers {
  consentGivenAt?: IsoDateTime | null
}

export interface DeleteAccountRequest {
  password: string
}

// ---------------------------------------------------------------------------------------------------------------------
// Verification: sources, runs, Claim Ledger, verdicts, references (plan v2 Section 5, WP-VBE notes Section 2)
// Every verification record is `@JsonInclude(ALWAYS)`: `x | null` fields arrive as explicit null.
// ---------------------------------------------------------------------------------------------------------------------

export type SourceKind = 'GITHUB' | 'GITLAB' | 'WEBSITE' | 'DOCUMENT' | 'OPEN_BADGE'
export type OwnershipStatus = 'UNPROVEN' | 'PENDING' | 'PROVEN' | 'FAILED'
export type OwnershipMethod = 'OAUTH' | 'DNS_TXT' | 'WELL_KNOWN_FILE' | 'PAGE_CODE' | 'PROFILE_BIO_CODE' | 'ISSUER_SIGNATURE'
/** Methods a candidate can ask `POST /sources/{id}/ownership-check` to test. */
export type OwnershipCheckMethod = 'DNS_TXT' | 'WELL_KNOWN_FILE' | 'PAGE_CODE' | 'PROFILE_BIO_CODE'
export type RunStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type VerdictValue = 'VERIFIED' | 'PARTLY_VERIFIED' | 'NOT_FOUND' | 'CONTRADICTED' | 'NOT_CHECKABLE'
export type CandidateVerdictStatus = 'AWAITING_CANDIDATE' | 'SHARED' | 'HIDDEN' | 'WITHDRAWN' | 'SUPERSEDED'
export type SubjectType = 'RESUME_FACT' | 'CANDIDATE_CLAIM'
export type VerdictAction = 'SHARE' | 'HIDE' | 'RECHECK'
export type ReferenceRelationship = 'MANAGER' | 'PEER' | 'REPORT' | 'CLIENT' | 'TEACHER' | 'OTHER'
export type ReferenceStatus = 'SENT' | 'ANSWERED' | 'DECLINED' | 'EXPIRED' | 'CANCELLED'
export type RefereeAnswer = 'CONFIRM' | 'PARTLY' | 'CANNOT_CONFIRM' | 'DISAGREE'

export interface LastRun {
  id: number
  status: RunStatus | string
  finishedAt: IsoDateTime | null
  errorCode: string | null
}

export interface VerificationSource {
  id: number
  kind: SourceKind | string
  identifier: string
  displayLabel: string
  ownershipStatus: OwnershipStatus | string
  ownershipMethod: OwnershipMethod | string | null
  /** "rp-" + 24 base64url characters; the code to publish for an ownership check. */
  challengeCode: string
  /** Plain sentences from the server; empty once ownership is proven. */
  ownershipInstructions: string[]
  consentedAt: IsoDateTime
  lastRun: LastRun | null
  /** WP-VBE extras. */
  ownershipCheckedAt: IsoDateTime | null
  ownershipDetail: string | null
  createdAt: IsoDateTime
}

/** `POST /sources`. OPEN_BADGE: `identifier` is the assertion URL, or `credential` holds a pasted JWT / JSON object. */
export interface SourceCreateRequest {
  kind: 'GITHUB' | 'GITLAB' | 'WEBSITE' | 'OPEN_BADGE'
  identifier?: string
  credential?: string | Record<string, unknown>
  consent: true
}

export interface VerificationRun {
  id: number
  sourceId: number
  status: RunStatus | string
  requestedBy: 'CANDIDATE' | 'SYSTEM' | string
  verifier: string | null
  createdAt: IsoDateTime
  startedAt: IsoDateTime | null
  finishedAt: IsoDateTime | null
  errorCode: string | null
}

export interface OAuthStartResponse {
  authorizeUrl: string
}

export interface VerdictSummary {
  id: number
  verifier: string
  verdict: VerdictValue | string
  candidateStatus: CandidateVerdictStatus | string
  sourceLabel: string | null
  createdAt: IsoDateTime
}

export interface LedgerClaim {
  subjectType: SubjectType | string
  subjectId: number
  claimType: string
  value: string
  /** The subject's own tier (RESUME_STATED for resume facts). */
  tier: EvidenceTier | string
  /** Current verdicts only, newest first. */
  verdicts: VerdictSummary[]
}

export interface VerdictReason {
  text: string
  evidenceKeys: string[]
  quote: string | null
}

export interface IntegrityNote {
  code: string
  text: string
  evidenceKeys: string[]
}

export interface EvidenceItem {
  id: number
  key: string
  kind: string
  url: string | null
  title: string | null
  retrievedAt: IsoDateTime
  /** `sha256:<hex>` */
  contentHash: string
  excerpt: string | null
  attributes: Record<string, unknown> | null
}

export interface VerdictDetail {
  id: number
  subjectType: SubjectType | string
  subjectId: number
  claimType: string
  value: string
  verifier: string
  verifierVersion: string | null
  verdict: VerdictValue | string
  ownershipProven: boolean
  candidateStatus: CandidateVerdictStatus | string
  candidateNote: string | null
  candidateRespondedAt: IsoDateTime | null
  current: boolean
  sourceId: number | null
  sourceKind: SourceKind | 'REFERENCE' | string | null
  sourceLabel: string | null
  referenceId: number | null
  runId: number | null
  /** Run narrative (cited sentences). */
  summary: VerdictReason[]
  reasons: VerdictReason[]
  integrityNotes: IntegrityNote[]
  probeQuestion: string | null
  /** Cited items, in citation order. */
  evidence: EvidenceItem[]
  createdAt: IsoDateTime
}

export interface VerdictResponseRequest {
  action: VerdictAction
  note?: string | null
}

export interface SubjectRef {
  subjectType: SubjectType | string
  subjectId: number
}

export interface ReferenceCreateRequest {
  refereeName: string
  refereeEmail: string
  relationship: ReferenceRelationship
  employerName?: string | null
  employerDomain?: string | null
  claims: SubjectRef[]
  consent: true
}

export interface ReferenceClaim extends SubjectRef {
  claimType: string
  value: string
}

export interface ReferenceRequest {
  id: number
  refereeName: string
  refereeEmail: string
  relationship: ReferenceRelationship | string
  employerName: string | null
  employerDomain: string | null
  claims: ReferenceClaim[]
  status: ReferenceStatus | string
  expiresAt: IsoDateTime
  answeredAt: IsoDateTime | null
  createdAt: IsoDateTime
  verdictCount: number
  errorCode: string | null
}

/** `GET /public/references/{token}` (no sign-in). Claim ids look like "fact:12" or "claim:7". */
export interface PublicReference {
  candidateFirstName: string
  relationship: ReferenceRelationship | string
  employerName: string | null
  claims: { id: string; claimType: string; value: string }[]
  expiresAt: IsoDateTime
}

export interface PublicReferenceAnswer {
  claimId: string
  answer: RefereeAnswer
  comment?: string | null
}

export type PublicReferenceSubmit =
  | { answers: PublicReferenceAnswer[]; refereeComment?: string | null; confirmIdentity: true }
  | { decline: true }
