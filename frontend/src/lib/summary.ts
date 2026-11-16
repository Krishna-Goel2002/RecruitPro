/*
 * Summaries for the pages candidates open most (SITE-CAND pass): the Overview "at a glance" strip and the
 * Applications pipeline bar. Pure functions over the API shapes, so the numbers are unit-tested.
 */
import type { ApplicationStatus } from '../api/types'
import { tierOrdinal } from '../design/lib/labels'
import { interviewEnd } from './nextStep'

export interface Glance {
  /** Applications still open on the employer's side (submitted, shortlisted, interviewing). */
  inProgress: number
  /** Work samples to start or finish, plus interviews that have not ended. */
  toDo: number
  /** Verification results waiting for the candidate's share/hide decision. */
  toReview: number
  /** Claims with evidence beyond the candidate's word and resume text (tier Corroborated or higher). */
  strong: number
}

const OPEN: readonly string[] = ['SUBMITTED', 'SHORTLISTED', 'INTERVIEWING']

export function glanceCounts(input: {
  applications?: { status: string }[]
  samples?: { status: string }[]
  interviews?: { status: string; scheduledAt: string; durationMinutes: number }[]
  ledger?: { tier?: string | null; verdicts: { candidateStatus: string }[] }[]
  now?: number
}): Glance {
  const now = input.now ?? Date.now()
  const apps = input.applications ?? []
  const samples = input.samples ?? []
  const interviews = input.interviews ?? []
  const ledger = input.ledger ?? []
  return {
    inProgress: apps.filter(a => OPEN.includes(a.status)).length,
    toDo:
      samples.filter(s => s.status === 'ASSIGNED' || s.status === 'STARTED').length +
      interviews.filter(i => i.status === 'SCHEDULED' && interviewEnd(i) >= now).length,
    toReview: ledger.reduce((n, c) => n + c.verdicts.filter(v => v.candidateStatus === 'AWAITING_CANDIDATE').length, 0),
    strong: ledger.filter(c => tierOrdinal(c.tier) >= tierOrdinal('CORROBORATED')).length,
  }
}

export interface PipelineStage {
  key: 'review' | 'shortlisted' | 'interviewing' | 'selected' | 'closed'
  label: string
  statuses: readonly ApplicationStatus[]
  count: number
}

const STAGES: Omit<PipelineStage, 'count'>[] = [
  { key: 'review', label: 'In review', statuses: ['SUBMITTED'] },
  { key: 'shortlisted', label: 'Shortlisted', statuses: ['SHORTLISTED'] },
  { key: 'interviewing', label: 'Interviewing', statuses: ['INTERVIEWING'] },
  { key: 'selected', label: 'Selected', statuses: ['SELECTED'] },
  { key: 'closed', label: 'Closed', statuses: ['REJECTED', 'WITHDRAWN'] },
]

/** Applications per stage, in pipeline order. Unknown statuses are not counted. */
export function pipelineCounts(applications: { status: string }[]): PipelineStage[] {
  return STAGES.map(s => ({ ...s, count: applications.filter(a => (s.statuses as readonly string[]).includes(a.status)).length }))
}

/**
 * Segment widths in percent for the non-zero stages; they always add up to exactly 100 (the last visible segment
 * takes the rounding remainder), so the bar reaches the end of its track.
 */
export function stageWidths(stages: { count: number }[]): number[] {
  const total = stages.reduce((n, s) => n + Math.max(0, s.count), 0)
  if (total === 0) return stages.map(() => 0)
  const raw = stages.map(s => (Math.max(0, s.count) / total) * 100)
  const last = raw.reduce((idx, w, i) => (w > 0 ? i : idx), -1)
  raw[last] = 100 - raw.reduce((n, w, i) => (i === last ? n : n + w), 0)
  return raw
}
