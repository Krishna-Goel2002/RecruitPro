import { Badge, type BadgeProps } from '../design/components'

type Spec = { label: string; tone: BadgeProps['tone']; variant?: BadgeProps['variant'] }

/** DESIGN_SYSTEM §3.8: statuses get a word, a tone and (where it helps) a shape; never colour alone. */
const JOB: Record<string, Spec> = {
  DRAFT: { label: 'Draft', tone: 'neutral', variant: 'outline' },
  PUBLISHED: { label: 'Published', tone: 'success' },
  ON_HOLD: { label: 'On hold', tone: 'warning' },
  CLOSED: { label: 'Closed', tone: 'neutral' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral', variant: 'struck' },
}

const APPLICATION: Record<string, Spec> = {
  SUBMITTED: { label: 'Submitted', tone: 'neutral' },
  SHORTLISTED: { label: 'Shortlisted', tone: 'info' },
  INTERVIEWING: { label: 'Interviewing', tone: 'info' },
  SELECTED: { label: 'Selected', tone: 'success' },
  // A candidate's outcome, not an error: neutral, never danger.
  REJECTED: { label: 'Not progressed', tone: 'neutral' },
  WITHDRAWN: { label: 'Withdrawn', tone: 'neutral', variant: 'outline' },
}

const WORK_SAMPLE: Record<string, Spec> = {
  DRAFT: { label: 'Draft', tone: 'neutral', variant: 'outline' },
  ACTIVE: { label: 'Active', tone: 'success' },
  ARCHIVED: { label: 'Archived', tone: 'neutral' },
  ASSIGNED: { label: 'Not started', tone: 'neutral', variant: 'outline' },
  STARTED: { label: 'In progress', tone: 'info' },
  SUBMITTED: { label: 'Submitted', tone: 'info' },
  SCORED: { label: 'Scored', tone: 'success' },
  EXPIRED: { label: 'Expired', tone: 'neutral' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral', variant: 'outline' },
}

function fallback(status: string): Spec {
  const text = status.replace(/_/g, ' ').toLowerCase()
  return { label: text.charAt(0).toUpperCase() + text.slice(1), tone: 'neutral' }
}

function render(spec: Spec, className?: string) {
  return (
    <Badge tone={spec.tone} variant={spec.variant ?? 'solid'} className={className}>
      {spec.label}
    </Badge>
  )
}

export const jobStatusLabel = (status: string) => (JOB[status] ?? fallback(status)).label
export const applicationStatusLabel = (status: string) => (APPLICATION[status] ?? fallback(status)).label

export function JobStatusBadge({ status, className }: { status: string; className?: string }) {
  return render(JOB[status] ?? fallback(status), className)
}

export function ApplicationStatusBadge({ status, className }: { status: string; className?: string }) {
  return render(APPLICATION[status] ?? fallback(status), className)
}

export function WorkSampleStatusBadge({ status, className }: { status: string; className?: string }) {
  return render(WORK_SAMPLE[status] ?? fallback(status), className)
}
