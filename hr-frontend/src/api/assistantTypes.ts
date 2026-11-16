/*
 * HR assistant wire types: docs/plan/ASSISTANT_PLAN.md §3.2 (backend `/api/v1/assistant/**`). Field names follow the
 * contract exactly; everything the contract leaves open (preview shapes, warnings) is typed loosely and rendered
 * defensively, because the backend and planner are built in parallel.
 */

/** Conversation and message ids are server-generated (numbers today; strings tolerated). */
export type AssistantId = number | string

/** `context.page` sent with every message (the contract's page types). */
export type AssistantPageType = 'job' | 'applicant' | 'jobs' | 'admin' | 'other'

export interface AssistantContext {
  page: AssistantPageType
  jobId?: number
  applicationId?: number
}

export type AssistantRole = 'user' | 'assistant'

export interface MetricsBlock {
  kind: 'metrics'
  items: { label: string; value: number | string; hint?: string | null }[]
}

/** A table column is a plain header or a described one. Cells are primitives or a `{label, href}` link. */
export type TableColumn = string | { key?: string; label?: string; header?: string; numeric?: boolean }
export type TableCell = string | number | boolean | null | { label: string; href?: string | null }

export interface TableBlock {
  kind: 'table'
  columns: TableColumn[]
  rows: TableCell[][]
  caption?: string | null
}

/** `gates` is open in the contract: a boolean (all verified), a count of open gates, `{open,total}` or a sentence. */
export type CandidateGates = boolean | number | string | { open?: number; verified?: number; total?: number } | null

export interface CandidateItem {
  applicationId: number
  code: string
  revealedName: string | null
  status: string
  /** Verified coverage, 0–100. */
  verified: number | null
  /** Coverage including claims, 0–100. */
  claimed: number | null
  /** Ranking group: RANKED, KNOCKOUT_UNVERIFIED (must-haves not yet verified), UNSCORED. */
  group: string | null
  gates?: CandidateGates
  link: string | null
}

export interface CandidatesBlock {
  kind: 'candidates'
  items: CandidateItem[]
}

export interface DraftRequirement {
  type?: string | null
  description: string
  mandatory?: boolean | null
  knockout?: boolean | null
  minimumYears?: number | null
  alternatives?: string[] | null
  proficiency?: string | null
}

/** Biased-wording or completeness warning on a job draft: a sentence, or the wording and a suggested rewrite. */
export type DraftWarning = string | { text?: string; message?: string; field?: string | null; phrase?: string | null; suggestion?: string | null }

export interface JobDraft {
  title?: string | null
  department?: string | null
  location?: string | null
  jobType?: string | null
  experienceLevel?: string | null
  salaryMin?: number | null
  salaryMax?: number | null
  openings?: number | null
  deadline?: string | null
  summary?: string | null
  /** Text or a list of lines. */
  responsibilities?: string | string[] | null
  description?: string | null
  requirements?: DraftRequirement[] | null
  warnings?: DraftWarning[] | null
  [extra: string]: unknown
}

export interface JobPreviewBlock {
  kind: 'job_preview'
  draft: JobDraft
  /** The create_job action this preview confirms (when the server links them; otherwise the message's action block). */
  actionId?: AssistantId
}

export type ActionStatus = 'PENDING' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED' | 'FAILED'

/** Result of a confirmed action (`result` of the confirm call), kept on the block by the client. */
export interface ActionResult {
  kind: string
  id?: number | string | null
  link?: string | null
}

export interface ActionBlock {
  kind: 'action'
  actionId: AssistantId
  /** Tool name: create_job, update_job_status, decide_application, assign_work_sample, schedule_interview, … */
  action: string
  summary: string
  /** What will change: a sentence, a list of `{label, value}` or a flat object. */
  preview?: unknown
  status: ActionStatus
  expiresAt?: string | null
  /** Client-side: the confirm result (link to the created or changed item). */
  result?: ActionResult | null
  /** Client-side or server: why it failed. */
  error?: string | null
}

export interface QuestionBlock {
  kind: 'question'
  field: string
  text: string
  options: string[]
}

export interface LinkBlock {
  kind: 'link'
  label: string
  href: string
}

export interface NoticeBlock {
  kind: 'notice'
  tone: 'info' | 'success' | 'warning' | 'danger' | string
  text: string
}

export type Block = MetricsBlock | TableBlock | CandidatesBlock | JobPreviewBlock | ActionBlock | QuestionBlock | LinkBlock | NoticeBlock

export interface AssistantMessage {
  id: AssistantId
  role: AssistantRole
  text: string
  blocks: Block[]
  status?: string | null
  /** offline | gemini | groq | anthropic | openai_compatible */
  planner?: string | null
  createdAt: string
}

export interface ConversationSummary {
  id: AssistantId
  title: string | null
  updatedAt: string
  createdAt?: string
}

export interface Conversation {
  id: AssistantId
  title: string | null
  messages: AssistantMessage[]
}

export interface SendMessageRequest {
  text: string
  context: AssistantContext
}

export interface SendMessageResponse {
  messages: AssistantMessage[]
}

/** create_job is confirmed as a draft or published, chosen on the preview card. */
export type CreateJobMode = 'DRAFT' | 'PUBLISH'

export interface ConfirmRequest {
  mode?: CreateJobMode
}

export interface ConfirmResponse {
  action: ActionBlock
  result: ActionResult
  message?: AssistantMessage | null
}

export interface CancelResponse {
  action: ActionBlock
}
