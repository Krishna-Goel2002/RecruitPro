// Completeness sweep fixtures (docs/audit/COMPLETENESS_SWEEP.md): work-sample lifecycle (draft delete, "cannot be
// deleted, archive instead", back to draft, duplicate, write by hand, discard guard), job duplicate, invitation resend,
// own name, interview-kit leave guard, earlier scorecard versions. Loaded by snap.mjs next to hr.mjs. Fictional data.
import { ago, ahead, route } from '../lib/mock.mjs'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const clickSel = (sel, wait = 600) => async (p) => {
  await p.evaluate((s) => document.querySelector(s)?.click(), sel)
  await sleep(wait)
}
const clickText = (text, sel = 'button, a, [role="menuitem"], [role="tab"]', wait = 600) => async (p) => {
  await p.evaluate(({ t, s }) => {
    const el = [...document.querySelectorAll(s)].find((n) => n.textContent.trim().startsWith(t))
    el?.click()
  }, { t: text, s: sel })
  await sleep(wait)
}
const typeInto = (sel, text, wait = 300) => async (p) => {
  await p.focus(sel)
  await p.keyboard.type(text)
  await sleep(wait)
}
const seq = (...steps) => async (p) => { for (const s of steps) await s(p) }

const anchors = (a, b, c, d) => ({ 1: a, 2: b, 3: c, 4: d })
const rubric = [
  { requirementId: 2, criterion: 'Schema and indexing', anchors: anchors('No persistent state for retries', 'Stores requests but no uniqueness guarantee', 'Unique key on the idempotency token', 'Also covers retention and the race between two identical requests') },
  { requirementId: null, criterion: 'Communication', anchors: anchors('Hard to follow', 'Partly clear', 'Clear', 'Clear and anticipates questions') },
]
const samples = [
  { id: 21, jobId: 101, title: 'Design an idempotent refunds endpoint', instructions: 'Design a POST /refunds endpoint that is safe to retry.', timeLimitMinutes: 90, rubric, status: 'ACTIVE', createdAt: ago(24 * 5), updatedAt: ago(24 * 5), assignmentCount: 3 },
  { id: 24, jobId: 101, title: 'Ledger reconciliation exercise (draft from the requirements)', instructions: 'Reconcile two ledgers that disagree.', timeLimitMinutes: 60, rubric, status: 'DRAFT', createdAt: ago(3), updatedAt: ago(2), assignmentCount: 0 },
  { id: 19, jobId: 101, title: 'Rate limiter design (2025 version)', instructions: 'Design a rate limiter.', timeLimitMinutes: 45, rubric, status: 'ARCHIVED', createdAt: ago(24 * 90), updatedAt: ago(24 * 30), assignmentCount: 0 },
]
const wsRoutes = [route('GET', '/hr/jobs/(\\d+)/work-samples', samples), route('GET', '/hr/jobs/(\\d+)/work-sample-assignments', [])]
const menuOf = (title) => clickSel(`button[aria-label="More actions for ${title}"]`, 500)

const kit = [
  { id: 'q1', requirementId: 1, question: 'Walk me through a service you designed end to end. What failed first in production?', followUps: ['How did you find out?'], anchors: anchors('Vague', 'Some detail', 'Clear design with trade-offs', 'Clear, measured and self-critical') },
  { id: 'q2', requirementId: 2, question: 'How would you index a table that is queried by customer and date range?', followUps: [], anchors: anchors('Guesses', 'Single index', 'Composite index, explains order', 'Also covers partitioning and write cost') },
]
const scorecard = {
  ratings: [{ questionId: 'q1', requirementId: 1, rating: 4, notes: 'Owned the settlement service design.' }, { questionId: 'q2', requirementId: 2, rating: 3, notes: null }],
  recommendation: 'STRONG_YES', summary: 'Strong systems thinking. Probe event streaming in the next round.', submittedAt: ago(28), updatedAt: ago(4), revisionCount: 1,
}
const interview = {
  id: 590, applicationId: 3001, jobId: 101, jobTitle: 'Senior Backend Engineer', candidateName: 'Meera Iyer', interviewerUserId: 14, interviewerName: 'Sana Khan',
  scheduledAt: ago(30), durationMinutes: 45, location: 'Room 4B', status: 'COMPLETED', scorecard, kit, focusRequirements: [], applicationStatus: 'INTERVIEWING', scorecardEditable: true,
}
const revisions = [{
  id: 1, revisedByUserId: 14, revisedByName: 'Sana Khan', revisedAt: ago(4),
  previous: { ratings: [{ questionId: 'q1', requirementId: 1, rating: 3, notes: null }, { questionId: 'q2', requirementId: 2, rating: 3, notes: null }], recommendation: 'YES', summary: 'Good systems thinking.', submittedAt: ago(28), revisionCount: 0 },
}]

const invitations = [
  { id: 31, email: 'neha.joshi@northwind.example', role: 'RECRUITER', status: 'PENDING', expiresAt: ahead(24 * 5), acceptedAt: null, revokedAt: null, createdAt: ago(48), invitedByName: 'Dev Kapoor' },
  { id: 30, email: 'arjun.mehta@northwind.example', role: 'INTERVIEWER', status: 'ACCEPTED', expiresAt: ago(24 * 2), acceptedAt: ago(24 * 6), revokedAt: null, createdAt: ago(24 * 9), invitedByName: 'Priya Raman' },
  { id: 28, email: 'contractor@agency.example', role: 'RECRUITER', status: 'REVOKED', expiresAt: ago(24 * 10), acceptedAt: null, revokedAt: ago(24 * 15), createdAt: ago(24 * 17), invitedByName: 'Dev Kapoor' },
  { id: 25, email: 'old.hire@northwind.example', role: 'HR_MANAGER', status: 'EXPIRED', expiresAt: ago(24 * 20), acceptedAt: null, revokedAt: null, createdAt: ago(24 * 27), invitedByName: 'Dev Kapoor' },
]

export const routes = () => [
  route('GET', '/interviews/(\\d+)/scorecard/revisions', revisions),
]

export const screens = [
  // Work samples: one row per status; the draft's menu (Edit, Duplicate, Archive, Delete draft…).
  { name: 'sweep-ws-draft-menu', path: '/jobs/101?tab=work-samples', override: wsRoutes, action: menuOf('Ledger reconciliation exercise (draft from the requirements)') },
  // Active and assigned: no "Move back to draft"; Delete… explains.
  { name: 'sweep-ws-active-menu', path: '/jobs/101?tab=work-samples', override: wsRoutes, action: menuOf('Design an idempotent refunds endpoint') },
  { name: 'sweep-ws-delete-draft', path: '/jobs/101?tab=work-samples', override: wsRoutes, action: seq(menuOf('Ledger reconciliation exercise (draft from the requirements)'), clickText('Delete draft')) },
  { name: 'sweep-ws-cannot-delete', path: '/jobs/101?tab=work-samples', override: wsRoutes, action: seq(menuOf('Design an idempotent refunds endpoint'), clickText('Delete…')) },
  { name: 'sweep-ws-back-to-draft', path: '/jobs/101?tab=work-samples', override: wsRoutes, action: seq(menuOf('Rate limiter design (2025 version)'), clickText('Move back to draft')) },
  { name: 'sweep-ws-new', path: '/jobs/101?tab=work-samples', override: wsRoutes, action: clickText('Write one yourself', 'button', 800) },
  { name: 'sweep-ws-discard', path: '/jobs/101?tab=work-samples', override: wsRoutes, action: seq(clickText('Write one yourself', 'button', 800), typeInto('.ui-drawer input', 'Rate limiter'), clickText('Cancel', '.ui-drawer .hra-drawer-actions button', 700)) },
  { name: 'sweep-ws-empty', path: '/jobs/101?tab=work-samples', override: [route('GET', '/hr/jobs/(\\d+)/work-samples', []), route('GET', '/hr/jobs/(\\d+)/work-sample-assignments', [])] },
  // Job "⋯" menu with Duplicate as draft.
  { name: 'sweep-job-menu', path: '/jobs/101', action: clickSel('button[aria-label="More job actions"]', 500) },
  // Invitations: Resend (pending), Invite again (expired, revoked).
  { name: 'sweep-invitations', path: '/admin/invitations?show=all', role: 'ADMIN', override: [route('GET', '/admin/invitations', invitations)] },
  // Own name.
  { name: 'sweep-account-name', path: '/account/name' },
  // Interview kit: edit a question, then press another tab: "Leave without saving?".
  { name: 'sweep-kit-leave', path: '/jobs/101?tab=interview-kit', action: seq(clickText('Edit questions', 'button', 700), typeInto('.hra-kit-editor textarea', ' (edited)'), clickText('Details', '[role="tab"]', 700)) },
  // Scorecard with an earlier version, opened.
  { name: 'sweep-scorecard-versions', path: '/interviews/590', role: 'INTERVIEWER', override: [route('GET', '/interviews/590', interview)], action: clickText('Show the earlier version', 'button', 800) },
]
