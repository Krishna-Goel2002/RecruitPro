// HR-UX fixtures: the assistant (panel, full page, every block kind, proposed actions), back navigation, the
// status-bar and textarea-scroll fixes, and the main-page pass. Loaded by snap.mjs next to hr.mjs / hr-a / hr-b.
// Assistant answers follow docs/plan/ASSISTANT_PLAN.md §3.2. Every value is fictional.
import { ago, ahead, page, route } from '../lib/mock.mjs'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const clickSel = (sel, wait = 600) => async (p) => {
  await p.evaluate((s) => document.querySelector(s)?.click(), sel)
  await sleep(wait)
}
const clickText = (text, sel = 'button, a', wait = 700) => async (p) => {
  await p.evaluate(({ t, s }) => {
    const el = [...document.querySelectorAll(s)].find((n) => n.textContent.trim().startsWith(t))
    el?.click()
  }, { t: text, s: sel })
  await sleep(wait)
}
const openPanel = clickSel('button.as-ask', 700)
const seq = (...steps) => async (p) => { for (const s of steps) await s(p) }

// ----------------------------------------------------------------------------------------------- conversations
const m = (id, role, text, blocks = [], minutesAgo = 5) => ({ id, role, text, blocks, planner: role === 'assistant' ? 'offline' : null, createdAt: ago(minutesAgo / 60) })

const insightsReply = [
  m(1, 'user', "Summarise this job's applicants", [], 6),
  m(2, 'assistant', '**Senior Backend Engineer** has **38 applicants**: 5 are ranked, 2 still have a must-have to verify and 2 are not scored yet.\n\nThe requirement missing most often is **Kubernetes** (9 of 38). Start with the top three by verified coverage:', [
    { kind: 'metrics', items: [{ label: 'Applicants', value: 38, hint: '4 new this week' }, { label: 'Ranked', value: 5 }, { label: 'Gates open', value: 2, hint: 'must-haves not verified' }, { label: 'Median verified', value: '62%' }] },
    { kind: 'candidates', items: [
      { applicationId: 3001, code: 'A-3001', revealedName: 'Meera Iyer', status: 'INTERVIEWING', verified: 86, claimed: 86, group: 'RANKED', gates: true, link: '/jobs/101/applicants/3001' },
      { applicationId: 3014, code: 'A-3014', revealedName: null, status: 'SUBMITTED', verified: 74, claimed: 82, group: 'RANKED', gates: true, link: '/jobs/101/applicants/3014' },
      { applicationId: 3022, code: 'A-3022', revealedName: null, status: 'SUBMITTED', verified: 68, claimed: 78, group: 'RANKED', gates: { open: 0, total: 1 }, link: '/jobs/101/applicants/3022' },
    ] },
    { kind: 'table', caption: 'Requirements most often without evidence', columns: ['Requirement', { label: 'Missing', numeric: true }, { label: 'Share', numeric: true }], rows: [['Kubernetes', 9, '24%'], ['Event streaming (Kafka or Pulsar)', 7, '18%'], ['AWS Certified Developer – Associate', 5, '13%']] },
    { kind: 'link', label: 'Open the applicant ranking', href: '/jobs/101?tab=applicants' },
  ], 5),
]

const postingReply = [
  m(11, 'user', 'Post a job for a senior data analyst in Pune', [], 14),
  m(12, 'assistant', 'Sure. A few details first. **What type of role is it?**', [
    { kind: 'question', field: 'jobType', text: 'Job type', options: ['Full time', 'Contract', 'Part time'] },
  ], 13),
  m(13, 'user', 'Full time', [], 12),
  m(14, 'assistant', 'Here is the posting. I suggested requirements from the description; check the wording note before you publish.', [
    { kind: 'job_preview', draft: {
      title: 'Senior Data Analyst', department: 'Analytics', location: 'Pune', jobType: 'FULL_TIME', experienceLevel: 'SENIOR', openings: 2,
      salaryMin: 1800000, salaryMax: 2600000, deadline: ahead(24 * 21),
      summary: 'Own the reporting layer for payments: metrics definitions, dashboards and the analyses product and finance rely on.',
      responsibilities: ['Define and maintain core payment metrics', 'Build dashboards finance uses every day', 'Run deep-dive analyses with product managers'],
      requirements: [
        { type: 'SKILL', description: 'SQL for analytics (window functions, CTEs)', mandatory: true, knockout: true, minimumYears: 4, alternatives: [], proficiency: 'ADVANCED' },
        { type: 'SKILL', description: 'A BI tool (Looker, Metabase or Power BI)', mandatory: true, knockout: false, minimumYears: null, alternatives: ['Tableau'], proficiency: null },
        { type: 'EXPERIENCE', description: 'Analytics in fintech or payments', mandatory: false, knockout: false, minimumYears: 2, alternatives: [], proficiency: null },
        { type: 'SKILL', description: 'dbt', mandatory: false, knockout: false, minimumYears: null, alternatives: ['Dataform'], proficiency: null },
      ],
      warnings: [{ phrase: 'rockstar', text: '“Rockstar analyst” can discourage some applicants.', suggestion: 'experienced analyst' }],
    } },
    { kind: 'action', actionId: 'act-create-1', action: 'create_job', summary: 'Create “Senior Data Analyst” (Analytics, Pune)', preview: null, status: 'PENDING', expiresAt: ahead(0.45) },
  ], 11),
]

const actionsReply = [
  m(21, 'user', 'Shortlist A-3014 and A-3022', [], 30),
  m(22, 'assistant', 'A-3022 was shortlisted. Nothing changes for A-3014 until you confirm:', [
    { kind: 'action', actionId: 'act-2', action: 'decide_application', summary: 'Shortlist A-3022 for Senior Backend Engineer', preview: { application: 'A-3022', from: 'Submitted', to: 'Shortlisted' }, status: 'CONFIRMED', expiresAt: ahead(0.2), result: { kind: 'application', id: 3022, link: '/jobs/101/applicants/3022' } },
    { kind: 'action', actionId: 'act-3', action: 'decide_application', summary: 'Shortlist A-3014 for Senior Backend Engineer', preview: [{ label: 'Application', value: 'A-3014' }, { label: 'Status', value: 'Submitted → Shortlisted' }, { label: 'Identity', value: 'Revealed to your team after shortlisting' }], status: 'PENDING', expiresAt: ahead(0.4) },
  ], 29),
  m(23, 'user', 'Schedule an interview with A-3001 tomorrow at 11', [], 20),
  m(24, 'assistant', 'That proposal expired before it was confirmed. Ask again for a fresh one.', [
    { kind: 'action', actionId: 'act-4', action: 'schedule_interview', summary: 'Interview A-3001 with Arjun Mehta, tomorrow 11:00–12:00', preview: { interviewer: 'Arjun Mehta', when: 'Tomorrow, 11:00 (IST)', duration: '60 min', location: 'Room 4B' }, status: 'EXPIRED', expiresAt: ago(0.1) },
    { kind: 'notice', tone: 'info', text: 'Actions the assistant proposes expire after 30 minutes. Each confirmed action is in the audit log as an assistant action.' },
  ], 19),
]

const conversations = {
  insights: { id: 'insights', title: 'Senior Backend Engineer applicants', messages: insightsReply },
  posting: { id: 'posting', title: 'Senior Data Analyst posting', messages: postingReply },
  actions: { id: 'actions', title: 'Shortlist and schedule', messages: actionsReply },
}
const history = [
  { id: 'insights', title: 'Senior Backend Engineer applicants', updatedAt: ago(0.1) },
  { id: 'posting', title: 'Senior Data Analyst posting', updatedAt: ago(0.25) },
  { id: 'actions', title: 'Shortlist and schedule', updatedAt: ago(26) },
  { id: 'old', title: 'What needs my attention?', updatedAt: ago(24 * 6) },
]

export function routes() {
  return [
    route('GET', '/assistant/conversations', page(history, { size: 20 })),
    route('POST', '/assistant/conversations', { id: 'insights', createdAt: ago(0) }),
    route('GET', '/assistant/conversations/([\\w-]+)', ({ match }) => conversations[match[1]] ?? { id: match[1], title: null, messages: [] }),
    route('POST', '/assistant/conversations/([\\w-]+)/messages', ({ body }) => ({ messages: body?.text?.startsWith('Post') ? postingReply.slice(0, 2) : insightsReply })),
    route('POST', '/assistant/actions/([\\w-]+)/confirm', ({ match }) => ({ action: { kind: 'action', actionId: match[1], action: 'decide_application', summary: 'Done', status: 'CONFIRMED' }, result: { kind: 'application', id: 3014, link: '/jobs/101/applicants/3014' }, message: null })),
    route('POST', '/assistant/actions/([\\w-]+)/cancel', ({ match }) => ({ action: { kind: 'action', actionId: match[1], action: 'decide_application', summary: 'Cancelled', status: 'CANCELLED' } })),
    route('DELETE', '/assistant/conversations/([\\w-]+)', null),
  ]
}

// ----------------------------------------------------------------------------------------------- other data
const longInstructions = [
  'You are joining the payments team. The ledger table below has grown to 180 million rows and the nightly settlement query now takes 41 minutes.',
  '',
  'Schema (simplified):',
  '- ledger_entries (id, merchant_id, account_id, amount_minor, currency, created_at, settled_at, batch_id)',
  '- settlement_batches (id, merchant_id, status, created_at)',
  '',
  'Tasks:',
  '1. Read the EXPLAIN (ANALYZE, BUFFERS) output attached as plan.txt and explain in your own words where the time goes.',
  '2. Propose at most two changes (indexes, query rewrites or partitioning). For each, say what it costs: write amplification, storage, migration time.',
  '3. Describe how you would roll the change out on a live database without blocking writes.',
  '4. Say how you would know the change worked, and what you would monitor for a week afterwards.',
  '',
  'What we look for: reasoning from the plan rather than guesses; trade-offs stated plainly; a rollout that a tired on-call engineer could follow.',
  '',
  'Time limit: 45 minutes. You may use any documentation. Please do not paste company data into public tools.',
  '',
  'Submission: a short written answer (bullet points are fine) and, optionally, the SQL you would run.',
].join('\n')
const longSample = { id: 22, jobId: 101, title: 'Tune a slow ledger query', instructions: longInstructions, timeLimitMinutes: 45, status: 'DRAFT', createdAt: ago(20), updatedAt: ago(3), rubric: [{ requirementId: 2, criterion: 'Query tuning', anchors: { 1: 'No improvement', 2: 'Adds an index blindly', 3: 'Reads the plan and fixes the cause', 4: 'Fixes the cause and explains the trade-off' } }] }

const allGoodDashboard = {
  totalUsers: 14, totalCandidates: 412, totalHrUsers: 14, totalJobs: 4, totalApplications: 63,
  applicationsByStatus: { SUBMITTED: 0, SHORTLISTED: 0, INTERVIEWING: 0, SELECTED: 63, REJECTED: 0, WITHDRAWN: 0 },
  jobsByStatus: { PUBLISHED: 4 }, recentApplicationsCount: 0,
  health: { backend: 'UP', database: 'UP', aiService: 'UP', agentService: 'UP', queue: { pending: 0, failed: 0, dead: 0 } },
}

export const screens = [
  // Assistant: panel
  { name: 'ux-assistant-panel', path: '/jobs/101', action: openPanel },
  { name: 'ux-assistant-panel-insights', path: '/jobs/101', action: seq(openPanel, clickText("Summarise this job's applicants", 'button.as-chip', 1400)) },
  { name: 'ux-assistant-panel-applicant', path: '/jobs/101/applicants/3014', action: openPanel },
  { name: 'ux-assistant-panel-interviewer', path: '/interviews', role: 'INTERVIEWER', action: openPanel },
  // Assistant: full page
  { name: 'ux-assistant-page', path: '/assistant', override: [route('GET', '/assistant/conversations', page([], { size: 20 }))] },
  { name: 'ux-assistant-insights', path: '/assistant?c=insights', settle: 1200 },
  { name: 'ux-assistant-posting', path: '/assistant?c=posting', settle: 1200 },
  { name: 'ux-assistant-posting-publish', path: '/assistant?c=posting', settle: 1200, action: clickText('Publish', 'button') },
  { name: 'ux-assistant-actions', path: '/assistant?c=actions', settle: 1200 },
  { name: 'ux-assistant-delete', path: '/assistant?c=actions', settle: 900, action: clickSel('.as-history-item[data-active] .as-history-del', 700) },
  // Back navigation and the main pages
  { name: 'ux-admin-overview', path: '/admin', role: 'ADMIN', settle: 900 },
  { name: 'ux-admin-overview-calm', path: '/admin', role: 'ADMIN', settle: 900, override: [route('GET', '/admin/dashboard', allGoodDashboard)] },
  { name: 'ux-admin-users', path: '/admin/users', role: 'ADMIN' },
  { name: 'ux-jobs', path: '/jobs' },
  { name: 'ux-jobs-empty', path: '/jobs', override: [route('GET', '/jobs', ({ query }) => page([], { size: Number(query.get('size') || 20), total: 0 }))] },
  { name: 'ux-job-applicants', path: '/jobs/101', settle: 900 },
  { name: 'ux-applicant', path: '/jobs/101/applicants/3001', settle: 900 },
  { name: 'ux-interviews', path: '/interviews', role: 'INTERVIEWER' },
  { name: 'ux-interview-detail', path: '/interviews/601', role: 'INTERVIEWER' },
  { name: 'ux-password', path: '/account/password' },
  { name: 'ux-not-found', path: '/nowhere' },
  // Bugs
  {
    name: 'ux-work-sample-long',
    path: '/jobs/101?tab=work-samples',
    override: [route('GET', '/hr/jobs/(\\d+)/work-samples', [longSample]), route('GET', '/hr/jobs/(\\d+)/work-sample-assignments', [])],
    action: async (p) => {
      await p.click('.hra-ws-actions button[aria-label^="Edit"]').catch(() => undefined)
      await sleep(800)
    },
  },
]
