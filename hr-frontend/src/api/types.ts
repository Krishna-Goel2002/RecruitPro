/**
 * Every API shape the HR portal relies on lives here, so reconciling with the backend is a one-file change.
 * Source of truth: docs/plan/IMPLEMENTATION_PLAN.md Section 5. Deviations already known from the backend
 * (WP-BE-A) are noted inline. Timestamps are ISO-8601 UTC strings ending in `Z`.
 */

// ---------------------------------------------------------------------------------------------------------------------
// Envelope and paging (Section 5)
// ---------------------------------------------------------------------------------------------------------------------

export type FieldError = { field: string; message: string }

export type Envelope<T> = {
  success: boolean
  message?: string
  data?: T
  errors?: FieldError[]
  timestamp?: string
}

export type Page<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

// ---------------------------------------------------------------------------------------------------------------------
// Shared enums (Section 5.1, 3.3)
// ---------------------------------------------------------------------------------------------------------------------

export type Role = 'ADMIN' | 'HR_MANAGER' | 'RECRUITER' | 'INTERVIEWER' | 'CANDIDATE'
export type StaffRole = Exclude<Role, 'CANDIDATE'>
export const STAFF_ROLES: StaffRole[] = ['ADMIN', 'HR_MANAGER', 'RECRUITER', 'INTERVIEWER']

export type EvidenceTier = 'SELF_CLAIMED' | 'RESUME_STATED' | 'CORROBORATED' | 'DEMONSTRATED' | 'INTERVIEW_VERIFIED'
export const EVIDENCE_TIERS: EvidenceTier[] = ['SELF_CLAIMED', 'RESUME_STATED', 'CORROBORATED', 'DEMONSTRATED', 'INTERVIEW_VERIFIED']

export type ApplicationStatus = 'SUBMITTED' | 'SHORTLISTED' | 'INTERVIEWING' | 'SELECTED' | 'REJECTED' | 'WITHDRAWN'
export const APPLICATION_STATUSES: ApplicationStatus[] = ['SUBMITTED', 'SHORTLISTED', 'INTERVIEWING', 'SELECTED', 'REJECTED', 'WITHDRAWN']

export type DecisionAction = 'SHORTLIST' | 'REJECT' | 'UNDO_SHORTLIST' | 'REOPEN' | 'MOVE_TO_INTERVIEW' | 'SELECT'

export type RequirementType = 'SKILL' | 'EDUCATION' | 'CERTIFICATION' | 'EXPERIENCE' | 'OTHER'
export const REQUIREMENT_TYPES: RequirementType[] = ['SKILL', 'EXPERIENCE', 'EDUCATION', 'CERTIFICATION', 'OTHER']

export type Proficiency = 'BASIC' | 'INTERMEDIATE' | 'ADVANCED' | 'EXPERT'
export const PROFICIENCIES: Proficiency[] = ['BASIC', 'INTERMEDIATE', 'ADVANCED', 'EXPERT']

export type JobStatus = 'DRAFT' | 'PUBLISHED' | 'ON_HOLD' | 'CLOSED' | 'CANCELLED'
export const JOB_STATUSES: JobStatus[] = ['DRAFT', 'PUBLISHED', 'ON_HOLD', 'CLOSED', 'CANCELLED']

export type JobType = 'FULL_TIME' | 'PART_TIME' | 'CONTRACT' | 'INTERNSHIP'
export const JOB_TYPES: JobType[] = ['FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERNSHIP']

export type ExperienceLevel = 'ENTRY' | 'MID' | 'SENIOR' | 'LEAD' | 'EXECUTIVE'
export const EXPERIENCE_LEVELS: ExperienceLevel[] = ['ENTRY', 'MID', 'SENIOR', 'LEAD', 'EXECUTIVE']

// ---------------------------------------------------------------------------------------------------------------------
// Auth and account (Section 5.2)
// ---------------------------------------------------------------------------------------------------------------------

/** `organizationId`/`organizationName` are omitted (not null) for candidates: the backend serialises non-null only. */
export type AuthUser = {
  id: number
  email: string
  firstName: string
  lastName: string
  role: Role
  organizationId?: number | null
  organizationName?: string | null
  emailVerified: boolean
}

export type AuthResponse = {
  accessToken: string
  tokenType: 'Bearer'
  expiresInSeconds: number
  user: AuthUser
}

export type LoginRequest = { email: string; password: string }
export type OrganizationSignupRequest = { organizationName: string; firstName: string; lastName: string; email: string; password: string }
export type InvitationPreview = { organizationName: string; email: string; role: StaffRole; expiresAt: string }
export type AcceptInviteRequest = { token: string; firstName: string; lastName: string; password: string }
export type ResetPasswordRequest = { token: string; newPassword: string }
export type ChangePasswordRequest = { currentPassword: string; newPassword: string }

/** In-app inbox item (`GET /me/notifications`). `readAt` is absent while unread (non_null serialisation). */
export type AppNotification = { id: number; template: string; subject: string; body: string; createdAt: string; readAt?: string | null }
export type UnreadCount = { count: number }

// ---------------------------------------------------------------------------------------------------------------------
// Jobs and requirements (Section 5.3)
// ---------------------------------------------------------------------------------------------------------------------

export type Requirement = {
  id: number
  type: RequirementType
  description: string
  mandatory: boolean
  knockout: boolean
  minimumYears: number | null
  alternatives: string[]
  proficiency: Proficiency | null
}

/**
 * knockout defaults to mandatory, alternatives to [] on the server. `id` is only read by the bulk replace
 * (`PUT /jobs/{id}/requirements`): an item with the id of an existing requirement of the job is updated in place
 * (links from work-sample rubrics, interview kits and evidence survive); items without id are created; requirements
 * not listed are deleted.
 */
export type RequirementInput = Omit<Requirement, 'id'> & { id?: number }

/**
 * JobStaff. The pre-WP-BE-B backend (`JobResponse`) lacks closedReason, requirementsVersion, createdByName and
 * applicationCount, and its requirements lack knockout/alternatives/proficiency; the UI tolerates their absence.
 */
export type JobStaff = {
  id: number
  organizationId: number
  title: string
  description: string
  department: string
  location: string
  jobType: JobType
  experienceLevel: ExperienceLevel
  minimumSalary: number | null
  maximumSalary: number | null
  vacancies: number
  status: JobStatus
  publishedAt: string | null
  deadline: string | null
  closedReason?: string | null
  requirementsVersion?: number
  createdByEmail: string
  createdByName?: string
  createdAt: string
  updatedAt: string
  requirements: Requirement[]
  applicationCount?: number
}

/** Body of POST /jobs and PUT /jobs/{id} ("job fields as today"). */
export type JobInput = {
  title: string
  description: string
  department: string
  location: string
  jobType: JobType
  experienceLevel: ExperienceLevel
  minimumSalary: number | null
  maximumSalary: number | null
  vacancies: number
  deadline: string | null
}

export type JobListParams = {
  page: number
  size: number
  status?: JobStatus
  search?: string
  sort?: string
  direction?: 'ASC' | 'DESC'
}

export type WarningKind = 'AGE_CODED' | 'GENDER_CODED' | 'DEGREE_INFLATION' | 'EXCLUSIONARY' | 'VAGUE' | 'EXCESSIVE_EXPERIENCE'

export type CompiledRequirement = RequirementInput & { rationale: string }

export type CompileResult = {
  engine: 'rules' | 'llm'
  requirements: CompiledRequirement[]
  warnings: { phrase: string; kind: WarningKind; suggestion: string }[]
}

export type CompileRequest = { title: string; description: string; experienceLevel?: ExperienceLevel }

// ---------------------------------------------------------------------------------------------------------------------
// HR applicant review (Section 5.4)
// ---------------------------------------------------------------------------------------------------------------------

export type MatchStatus = 'READY' | 'PENDING' | 'NO_REQUIREMENTS' | 'ANALYSIS_UNAVAILABLE' | 'FAILED'
export type RankGroup = 'RANKED' | 'MUST_HAVES_UNVERIFIED' | 'UNSCORED'

export type ApplicantSummary = {
  applicationId: number
  displayName: string
  identityRevealed: boolean
  applicationStatus: ApplicationStatus
  appliedAt: string
  statusChangedAt: string | null
  analysisStatus: string | null
  matchStatus: MatchStatus
  verifiedCoverage: number | null
  claimedCoverage: number | null
  knockoutsVerified: boolean | null
  unverifiedKnockouts: number[]
  rankGroup: RankGroup
  rank: number | null
  flags: string[]
  workSampleStatus: string | null
  interviewCount: number
}

export type ApplicantListParams = {
  page: number
  size: number
  status?: ApplicationStatus
  sort: 'rank' | 'appliedAt'
}

export type RequirementEvidence = {
  requirementId: number
  type: RequirementType
  description: string
  mandatory: boolean
  knockout: boolean
  minimumYears: number | null
  alternatives: string[]
  status: 'MATCHED' | 'UNCLEAR' | 'NOT_OBSERVED'
  tier: EvidenceTier | null
  evidence: string | null
  sourceSection: string | null
  explanation: string
  verificationHint: string | null
  /** WP-BE-B extra: RESUME | CANDIDATE_CLAIM | CORROBORATED | WORK_SAMPLE | INTERVIEW. */
  provenance?: string | null
}

/**
 * WP-BE-B adds hasEvidenceUrl (staff see the URL only after reveal), sourceFactId and
 * verificationStatus (UNVERIFIED | VERIFIED | REJECTED).
 */
export type CandidateClaim = {
  id: number
  claimType: string
  value: string
  evidenceUrl: string | null
  hasEvidenceUrl?: boolean
  sourceFactId?: number | null
  tier: EvidenceTier
  verificationStatus?: 'UNVERIFIED' | 'VERIFIED' | 'REJECTED' | string
  verifiedAt: string | null
  verificationNote: string | null
  createdAt: string
}

export type DecisionEvent = {
  id: number
  applicationId: number
  previousStatus: ApplicationStatus
  newStatus: ApplicationStatus
  actionType: string
  actorUserId: number
  actorName: string
  timestamp: string
  comment: string | null
  matchRunId?: number | null
}

export type CandidateDetails = {
  fullName: string
  email: string
  city: string | null
  country: string | null
  linkedinUrl: string | null
  portfolioUrl: string | null
  yearsOfExperience: number | null
  currentCompany: string | null
  currentDesignation: string | null
}

export type ApplicantDetail = ApplicantSummary & {
  requirements: RequirementEvidence[]
  matchRunId: number | null
  algorithmVersion: string | null
  calculatedAt: string | null
  claims: CandidateClaim[]
  candidate: CandidateDetails | null
  coverLetter: string | null
  resumeAvailable: boolean
  resumeFilename: string | null
  workSamples: WorkSampleAssignment[]
  interviews: Interview[]
  history: DecisionEvent[]
  allowedActions: DecisionAction[]
  /** WP-BE-B extra: the server's answer to "may this viewer reveal identity now" (handles blind review off). */
  revealAllowed?: boolean
}

export type DecisionRequest = { action: DecisionAction; comment?: string }
export type ClaimVerificationRequest = { verified: boolean; note?: string }
export type Interviewer = { userId: number; name: string; email: string; role: StaffRole }

// ---------------------------------------------------------------------------------------------------------------------
// Work samples and interviews (Section 5.6)
// ---------------------------------------------------------------------------------------------------------------------

export type AnchorLevel = '1' | '2' | '3' | '4'
export const ANCHOR_LEVELS: AnchorLevel[] = ['1', '2', '3', '4']
export type Anchors = Record<AnchorLevel, string>

export type RubricCriterion = { requirementId: number | null; criterion: string; anchors: Anchors }

export type WorkSampleStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'

export type WorkSample = {
  id: number
  jobId: number
  title: string
  instructions: string
  timeLimitMinutes: number
  rubric: RubricCriterion[]
  status: WorkSampleStatus
  createdAt: string
  updatedAt: string
}

export type WorkSampleInput = Pick<WorkSample, 'title' | 'instructions' | 'timeLimitMinutes' | 'rubric'>

export type AssignmentStatus = 'ASSIGNED' | 'STARTED' | 'SUBMITTED' | 'SCORED' | 'EXPIRED' | 'CANCELLED'
export const ASSIGNMENT_STATUSES: AssignmentStatus[] = ['ASSIGNED', 'STARTED', 'SUBMITTED', 'SCORED', 'EXPIRED', 'CANCELLED']

export type CriterionScore = { requirementId: number | null; criterion: string; score: number; note: string | null }

export type WorkSampleAssignment = {
  id: number
  workSampleId: number
  workSampleTitle: string
  applicationId: number
  status: AssignmentStatus
  assignedAt: string
  dueAt: string | null
  startedAt: string | null
  submittedAt: string | null
  responseText: string | null
  responseUrl: string | null
  scores: CriterionScore[]
  totalScore: number | null
  scoredAt: string | null
  scoredByName: string | null
}

export type AssignWorkSampleRequest = { workSampleId: number; dueAt?: string }
export type ScoreRequest = { scores: { requirementId: number | null; criterion: string; score: number; note?: string }[] }

export type InterviewQuestion = {
  id: string
  requirementId: number | null
  question: string
  followUps: string[]
  anchors: Anchors
}

export type InterviewKit = { jobId: number; questions: InterviewQuestion[]; generatedBy: string; updatedAt: string }

export type Recommendation = 'STRONG_NO' | 'NO' | 'YES' | 'STRONG_YES'
export const RECOMMENDATIONS: Recommendation[] = ['STRONG_NO', 'NO', 'YES', 'STRONG_YES']

export type Rating = 1 | 2 | 3 | 4

export type Scorecard = {
  ratings: { questionId: string; requirementId: number | null; rating: Rating; notes: string | null }[]
  recommendation: Recommendation
  summary: string | null
  submittedAt: string
}

export type ScorecardInput = Omit<Scorecard, 'submittedAt'>

export type InterviewStatus = 'SCHEDULED' | 'COMPLETED' | 'CANCELLED'

export type Interview = {
  id: number
  applicationId: number
  jobId: number
  jobTitle: string
  candidateName: string
  interviewerUserId: number
  interviewerName: string
  scheduledAt: string
  durationMinutes: number
  location: string | null
  status: InterviewStatus
  scorecard: Scorecard | null
}

export type InterviewDetail = Interview & { kit: InterviewQuestion[]; focusRequirements: RequirementEvidence[] }

export type ScheduleInterviewRequest = { interviewerUserId: number; scheduledAt: string; durationMinutes: number; location?: string }
export type UpdateInterviewRequest = { scheduledAt?: string; durationMinutes?: number; location?: string; status?: 'CANCELLED' }

// ---------------------------------------------------------------------------------------------------------------------
// Fairness and administration (Section 5.7)
// ---------------------------------------------------------------------------------------------------------------------

export type FairnessDimension = 'gender' | 'ageBand' | 'disability' | 'socialCategory'
export type FairnessStage = 'APPLIED' | 'SHORTLISTED' | 'INTERVIEWING' | 'SELECTED'

export type FairnessReport = {
  scope: 'JOB' | 'ORGANIZATION'
  jobId: number | null
  minimumGroupSize: number
  surveyResponseRate: number
  dimensions: {
    dimension: FairnessDimension
    stages: {
      stage: FairnessStage
      groups: { group: string; count: number | null; selectionRate: number | null }[]
      adverseImpactRatio: number | null
      flagged: boolean
    }[]
  }[]
  note: string
}

export type Organization = { id: number; name: string; slug: string; blindReview: boolean; shareVerificationStatus: boolean }
export type OrganizationUpdate = { name?: string; blindReview?: boolean; shareVerificationStatus?: boolean }

export type HealthValue = 'UP' | 'DOWN' | 'UNCONFIGURED' | string

/** WP-BE-A keeps the old flat health fields and adds `health`. */
export type AdminDashboard = {
  totalUsers: number
  totalCandidates: number
  totalHrUsers: number
  totalJobs: number
  totalApplications: number
  applicationsByStatus: Record<string, number>
  jobsByStatus: Record<string, number>
  recentApplicationsCount: number
  backendHealth?: HealthValue
  databaseHealth?: HealthValue
  aiServiceHealth?: HealthValue
  health?: { backend: HealthValue; database: HealthValue; aiService: HealthValue; queue: { pending: number; failed: number; dead: number } }
}

export type AdminUser = {
  id: number
  email: string
  firstName: string
  lastName: string
  role: Role
  enabled: boolean
  createdAt: string
  updatedAt?: string
}

export type AdminUserParams = { page: number; size: number; search?: string; role?: StaffRole; enabled?: boolean }

export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED'

/** `status` is a WP-BE-A addition; `inviteUrl` only on the create response when app.invitations.expose-link=true. */
export type Invitation = {
  id: number
  email: string
  role: StaffRole
  status?: InvitationStatus
  expiresAt: string
  acceptedAt: string | null
  revokedAt: string | null
  createdAt: string
  invitedByName: string | null
  inviteUrl?: string | null
}

export type AdminJob = {
  id: number
  title: string
  department: string
  location: string
  jobType: string
  experienceLevel: string
  status: JobStatus
  vacancies: number
  createdByEmail: string
  createdAt: string
  applicationCount: number
}

export type AdminJobParams = { page: number; size: number; status?: JobStatus; search?: string }

export type AuditEvent = {
  id: number
  action: string
  actorEmail: string | null
  actorUserId: number | null
  targetType: string | null
  targetId: string | null
  details: string | null
  ipAddress: string | null
  createdAt: string
}

export type AuditParams = { page: number; size: number; action?: string }

/** Audit action names (plan Section 4 plus WP-BE-A extras). */
export const AUDIT_ACTIONS = [
  'APPLICATION_DECISION', 'IDENTITY_REVEALED', 'RESUME_DOWNLOADED_BY_STAFF', 'CLAIM_VERIFIED', 'WORK_SAMPLE_SCORED',
  'SCORECARD_SUBMITTED', 'JOB_REQUIREMENTS_CHANGED', 'JOB_AUTO_CLOSED', 'ADMIN_JOB_STATUS_OVERRIDE',
  'USER_STATUS_CHANGED', 'USER_ROLE_CHANGED', 'INVITATION_CREATED', 'INVITATION_REVOKED', 'INVITATION_ACCEPTED',
  'ORGANIZATION_CREATED', 'ORGANIZATION_UPDATED', 'AUDIT_EXPORTED', 'CANDIDATE_ACCOUNT_DELETED', 'LOGIN_LOCKED',
  'PASSWORD_CHANGED', 'PASSWORD_RESET', 'REFRESH_TOKEN_REUSE_DETECTED',
] as const
