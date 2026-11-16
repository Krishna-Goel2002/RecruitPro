/**
 * Where a notification leads (UX X-11). Notifications carry `template`, `subject` and `body` only, so the subject is
 * matched against the candidate's applications and work samples by title (API gap, FLOWS-CANDIDATE §14.2).
 */
import type { AppNotification, Application, CandidateWorkSample } from '../api/types'
import { BadgeCheck, CalendarClock, FileText, Inbox, ListChecks, UserRoundCheck, type IconComponent } from '../design/icons'

export interface NotificationTarget {
  to: string | null
  icon: IconComponent
  /** Short label for the action ("Open application"). */
  label: string
}

function afterPrefix(subject: string, prefixes: string[]): string | null {
  for (const p of prefixes) if (subject.toLowerCase().startsWith(p.toLowerCase())) return subject.slice(p.length).trim()
  return null
}

export function notificationTarget(n: AppNotification, applications: Application[] = [], samples: CandidateWorkSample[] = []): NotificationTarget {
  const s = n.subject
  switch (n.template) {
    case 'APPLICATION_RECEIVED':
    case 'APPLICATION_STATUS_CHANGED':
    case 'APPLICATION_UPDATE': {
      const title = afterPrefix(s, ['We received your application for', 'Update on your application for']) ?? s
      const match = applications.find(a => title.includes(a.jobTitle))
      return { to: match ? `/applications/${match.id}` : '/applications', icon: FileText, label: 'Open application' }
    }
    case 'WORK_SAMPLE_ASSIGNED':
    case 'WORK_SAMPLE_CANCELLED': {
      const match = samples.find(w => s.includes(w.title))
      return { to: match ? `/work-samples/${match.assignmentId}` : '/work-samples', icon: ListChecks, label: 'Open work sample' }
    }
    case 'INTERVIEW_SCHEDULED':
    case 'INTERVIEW_CANCELLED':
      return { to: '/interviews', icon: CalendarClock, label: 'See interviews' }
    case 'VERIFICATION_READY':
      return { to: '/verification?review=first', icon: BadgeCheck, label: 'Review results' }
    case 'REFERENCE_ANSWERED':
      return { to: '/verification#references', icon: UserRoundCheck, label: 'See references' }
    default:
      return { to: null, icon: Inbox, label: '' }
  }
}
