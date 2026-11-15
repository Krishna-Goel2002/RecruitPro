// HR / staff portal fixtures. Shapes follow hr-frontend/src/api/types.ts. Every value is fictional.
import { ago, ahead, delayed, fail, page, route, session } from '../lib/mock.mjs'

const manager = {
  id: 11, email: 'priya.raman@northwind.example', firstName: 'Priya', lastName: 'Raman', role: 'HR_MANAGER',
  organizationId: 1, organizationName: 'Northwind Labs', emailVerified: true,
}
const admin = { ...manager, id: 10, email: 'dev.kapoor@northwind.example', firstName: 'Dev', lastName: 'Kapoor', role: 'ADMIN' }
const interviewer = { ...manager, id: 14, email: 'sana.k@northwind.example', firstName: 'Sana', lastName: 'Khan', role: 'INTERVIEWER' }

const req = (id, type, description, mandatory, extra = {}) => ({
  id, type, description, mandatory, knockout: false, minimumYears: null, alternatives: [], proficiency: null, ...extra,
})
const requirements = [
  req(1, 'SKILL', 'Java 17 and Spring Boot', true, { knockout: true, minimumYears: 3, proficiency: 'ADVANCED' }),
  req(2, 'SKILL', 'PostgreSQL schema design and query tuning', true),
  req(3, 'EXPERIENCE', 'Backend engineering in production', true, { minimumYears: 5 }),
  req(4, 'SKILL', 'Kubernetes', false, { alternatives: ['Amazon ECS', 'Nomad'] }),
  req(5, 'CERTIFICATION', 'AWS Certified Developer – Associate', false),
  req(6, 'SKILL', 'Event streaming (Kafka or Pulsar)', false, { alternatives: ['Pulsar'] }),
]

const job = (id, title, status, department, location, applicationCount, extra = {}) => ({
  id, organizationId: 1, title, department, location, jobType: 'FULL_TIME', experienceLevel: 'SENIOR', minimumSalary: 2800000,
  maximumSalary: 4200000, vacancies: 2, status, publishedAt: status === 'DRAFT' ? null : ago(24 * 6), deadline: ahead(24 * 12),
  closedReason: null, requirementsVersion: 3, createdByEmail: manager.email, createdByName: 'Priya Raman', createdAt: ago(24 * 8),
  updatedAt: ago(30), requirements, applicationCount,
  description: 'Own the services behind our payments platform: design APIs, keep PostgreSQL fast, and help the team ship safely.',
  ...extra,
})
const jobs = [
  job(101, 'Senior Backend Engineer', 'PUBLISHED', 'Engineering', 'Bengaluru (hybrid)', 38),
  job(102, 'Product Designer, Growth', 'PUBLISHED', 'Design', 'Remote (India)', 21, { experienceLevel: 'MID' }),
  job(105, 'Site Reliability Engineer', 'ON_HOLD', 'Platform', 'Bengaluru', 9),
  job(106, 'Engineering Manager, Payments', 'DRAFT', 'Engineering', 'Bengaluru', 0, { experienceLevel: 'LEAD' }),
  job(107, 'QA Automation Engineer', 'CLOSED', 'Engineering', 'Pune', 54, { closedReason: 'Position filled' }),
  job(108, 'Customer Success Lead', 'PUBLISHED', 'Customer', 'Mumbai', 12, { jobType: 'CONTRACT', experienceLevel: 'MID' }),
]

// Coverage is a percentage 0–100 on the wire (MatchingService), written here as 0–1 for readability.
const pct = (x) => (x == null ? null : Math.round(x * 100))
const a = (applicationId, extra) => {
  const row = base(applicationId, extra)
  return { ...row, verifiedCoverage: pct(row.verifiedCoverage), claimedCoverage: pct(row.claimedCoverage) }
}
const base = (applicationId, extra) => ({
  applicationId, displayName: `Candidate #A-${applicationId}`, identityRevealed: false, applicationStatus: 'SUBMITTED',
  appliedAt: ago(24 * 3), statusChangedAt: null, analysisStatus: 'COMPLETED', matchStatus: 'READY', verifiedCoverage: 0.5,
  claimedCoverage: 0.6, knockoutsVerified: true, unverifiedKnockouts: [], rankGroup: 'RANKED', rank: null, flags: [],
  workSampleStatus: null, interviewCount: 0, ...extra,
})
const applicants = [
  a(3001, { rank: 1, displayName: 'Meera Iyer', identityRevealed: true, applicationStatus: 'SHORTLISTED', statusChangedAt: ago(20), verifiedCoverage: 0.86, claimedCoverage: 0.93, workSampleStatus: 'ASSIGNED', interviewCount: 1, flags: ['TWO_COLUMN_LAYOUT'] }),
  a(3014, { rank: 2, verifiedCoverage: 0.79, claimedCoverage: 0.79 }),
  a(3022, { rank: 3, verifiedCoverage: 0.71, claimedCoverage: 0.86, appliedAt: ago(30) }),
  a(3009, { rank: 4, verifiedCoverage: 0.64, claimedCoverage: 0.64, applicationStatus: 'SHORTLISTED', displayName: 'Rohan Desai', identityRevealed: true }),
  a(3031, { rank: 5, verifiedCoverage: 0.5, claimedCoverage: 0.71, appliedAt: ago(8) }),
  a(3017, { rankGroup: 'MUST_HAVES_UNVERIFIED', knockoutsVerified: false, unverifiedKnockouts: [1], verifiedCoverage: 0.57, claimedCoverage: 0.86 }),
  a(3027, { rankGroup: 'MUST_HAVES_UNVERIFIED', knockoutsVerified: false, unverifiedKnockouts: [1], verifiedCoverage: 0.36, claimedCoverage: 0.5, flags: ['HIDDEN_TEXT'] }),
  a(3040, { rankGroup: 'UNSCORED', matchStatus: 'PENDING', verifiedCoverage: null, claimedCoverage: null, knockoutsVerified: null, appliedAt: ago(0.3) }),
  a(3005, { rankGroup: 'UNSCORED', matchStatus: 'ANALYSIS_UNAVAILABLE', analysisStatus: 'NEEDS_OCR', verifiedCoverage: null, claimedCoverage: null, knockoutsVerified: null, applicationStatus: 'REJECTED', statusChangedAt: ago(24) }),
]

const ev = (r, status, tier, evidence, explanation, extra = {}) => ({
  requirementId: r.id, type: r.type, description: r.description, mandatory: r.mandatory, knockout: r.knockout,
  minimumYears: r.minimumYears, alternatives: r.alternatives, status, tier, evidence, sourceSection: evidence ? 'Experience' : null,
  explanation, verificationHint: null, provenance: 'RESUME', ...extra,
})
const applicantDetail = (id) => {
  const s = applicants.find((x) => x.applicationId === id) ?? applicants[1]
  const revealed = s.identityRevealed
  return {
    ...s,
    requirements: [
      ev(requirements[0], 'MATCHED', 'CORROBORATED', 'Built settlement services in Java 17 / Spring Boot 3', 'Stated in experience; confirmed by a reviewer against a shared GitHub result.', { provenance: 'CORROBORATED' }),
      ev(requirements[1], 'MATCHED', 'DEMONSTRATED', 'Cut p95 query time 38% by partitioning ledger tables', 'Work sample criterion "Schema & indexing" scored 4 of 4.', { provenance: 'WORK_SAMPLE' }),
      ev(requirements[2], 'MATCHED', 'RESUME_STATED', 'Backend Engineer · Finlytic · Jun 2019 – present', '7 years stated; minimum is 5.', { verificationHint: 'Ask a referee or check the employment dates in the interview.' }),
      ev(requirements[3], 'UNCLEAR', 'SELF_CLAIMED', 'Deployed on Kubernates (EKS)', 'Misspelt on the resume; the candidate added a claim.', { provenance: 'CANDIDATE_CLAIM', verificationHint: 'Ask about a deployment they ran end to end.' }),
      ev(requirements[4], 'MATCHED', 'CORROBORATED', null, 'Badge link checked by a recruiter.', { provenance: 'CORROBORATED' }),
      ev(requirements[5], 'NOT_OBSERVED', null, null, 'No mention in resume or claims.', { verificationHint: 'Probe in interview if it matters for the team.' }),
    ],
    matchRunId: 412, algorithmVersion: 'match-2.1', calculatedAt: ago(19),
    claims: [
      { id: 55, claimType: 'SKILL', value: 'Kubernetes', evidenceUrl: null, hasEvidenceUrl: false, tier: 'SELF_CLAIMED', verificationStatus: 'UNVERIFIED', verifiedAt: null, verificationNote: null, createdAt: ago(28) },
      { id: 56, claimType: 'CERTIFICATION', value: 'AWS Certified Developer – Associate (2025)', evidenceUrl: revealed ? 'https://www.credly.com/badges/example' : null, hasEvidenceUrl: true, tier: 'CORROBORATED', verificationStatus: 'VERIFIED', verifiedAt: ago(20), verificationNote: 'Badge ID matches issuer record.', createdAt: ago(26) },
    ],
    candidate: revealed ? { fullName: s.displayName, email: 'meera.iyer@example.com', city: 'Bengaluru', country: 'India', linkedinUrl: 'https://www.linkedin.com/in/example', portfolioUrl: 'https://meera.dev', yearsOfExperience: 7, currentCompany: 'Finlytic', currentDesignation: 'Backend Engineer' } : null,
    coverLetter: revealed ? 'I have spent six years on payment systems, most recently leading the move to partitioned ledger tables…' : null,
    resumeAvailable: true, resumeFilename: revealed ? 'Meera_Iyer_Backend_2026.pdf' : null,
    workSamples: [{ id: 801, workSampleId: 21, workSampleTitle: 'Design an idempotent refunds endpoint', applicationId: id, status: 'SCORED', assignedAt: ago(19), dueAt: ahead(52), startedAt: ago(10), submittedAt: ago(8.6), responseText: 'Use an Idempotency-Key header persisted with the request hash…', responseUrl: 'https://github.com/example/refunds-design', scores: [{ requirementId: 2, criterion: 'Schema & indexing', score: 4, note: null }, { requirementId: 1, criterion: 'API design', score: 3, note: 'Clear error model.' }], totalScore: 3.5, scoredAt: ago(6), scoredByName: 'Priya Raman', files: [] }],
    interviews: [{ id: 601, applicationId: id, jobId: 101, jobTitle: 'Senior Backend Engineer', candidateName: s.displayName, interviewerUserId: 14, interviewerName: 'Sana Khan', scheduledAt: ahead(72), durationMinutes: 45, location: 'https://meet.example.com/nwl-be-3001', status: 'SCHEDULED', scorecard: null }],
    history: [
      { id: 2, applicationId: id, previousStatus: 'SUBMITTED', newStatus: 'SHORTLISTED', actionType: 'SHORTLIST', actorUserId: 11, actorName: 'Priya Raman', timestamp: ago(20), comment: 'Strong verified coverage on both gates.', matchRunId: 410 },
      { id: 1, applicationId: id, previousStatus: 'SUBMITTED', newStatus: 'SUBMITTED', actionType: 'REVIEW_COMMENT', actorUserId: 12, actorName: 'Kabir Shah', timestamp: ago(22), comment: 'Kubernetes is claimed only — worth probing.', matchRunId: 410 },
    ],
    allowedActions: s.applicationStatus === 'SUBMITTED' ? ['SHORTLIST', 'REJECT'] : ['MOVE_TO_INTERVIEW', 'UNDO_SHORTLIST', 'REJECT'],
    revealAllowed: !revealed,
  }
}

const sv = (id, verdict, ownershipProven, sourceKind, extra = {}) => ({
  id, verifier: sourceKind === 'REFERENCE' ? 'reference-verifier' : 'code-verifier', verdict, ownershipProven, sourceKind,
  reasons: [{ text: 'Repository migrations create partitioned ledger tables.', evidenceKeys: ['e1'], quote: 'PARTITION BY RANGE (created_at)' }],
  integrityNotes: [], probeQuestion: 'How did you choose the partition key?', candidateNote: null, myOrgReview: null,
  evidence: null, evidenceCount: 2, createdAt: ago(26), ...extra,
})
const verification = {
  mode: 'VISIBLE', revealed: true,
  claims: [
    { subjectType: 'RESUME_FACT', subjectId: 901, claimType: 'SKILL', value: 'Java', tier: 'CORROBORATED', verdicts: [sv(7001, 'VERIFIED', true, 'GITHUB', { myOrgReview: { decision: 'CONFIRM', note: null, reviewerName: 'Kabir Shah', at: ago(21) }, evidence: [{ id: 1, key: 'e1', kind: 'REPO', url: 'https://github.com/meera-iyer/ledger-svc', title: 'ledger-svc', retrievedAt: ago(26), contentHash: 'sha256:9f2c4e1ab07d5c3e88f1a2b3', excerpt: 'Spring Boot 3.2 · Java 17 toolchain', attributes: null }] })] },
    { subjectType: 'RESUME_FACT', subjectId: 902, claimType: 'SKILL', value: 'PostgreSQL', tier: 'RESUME_STATED', verdicts: [sv(7002, 'PARTLY_VERIFIED', true, 'GITHUB', { candidateNote: 'Most tuning work was in a private repo at Finlytic.' })] },
    { subjectType: 'RESUME_FACT', subjectId: 904, claimType: 'EXPERIENCE', value: 'Backend Engineer, Finlytic (2019–2026)', tier: 'RESUME_STATED', verdicts: [sv(7010, 'VERIFIED', true, 'REFERENCE', { reasons: [{ text: 'Former manager confirmed role and dates.', evidenceKeys: [], quote: null }], integrityNotes: [{ code: 'DOMAIN_MATCH', text: 'Referee email domain matches the stated employer.', evidenceKeys: [] }] })] },
  ],
}

const interviews = [
  { id: 600, applicationId: 3009, jobId: 101, jobTitle: 'Senior Backend Engineer', candidateName: 'Rohan Desai', interviewerUserId: 14, interviewerName: 'Sana Khan', scheduledAt: ago(3), durationMinutes: 45, location: 'Room 4B', status: 'SCHEDULED', scorecard: null },
  { id: 601, applicationId: 3001, jobId: 101, jobTitle: 'Senior Backend Engineer', candidateName: 'Meera Iyer', interviewerUserId: 14, interviewerName: 'Sana Khan', scheduledAt: ahead(72), durationMinutes: 45, location: 'https://meet.example.com/nwl-be-3001', status: 'SCHEDULED', scorecard: null },
  { id: 590, applicationId: 2950, jobId: 102, jobTitle: 'Product Designer, Growth', candidateName: 'Ishaan Verma', interviewerUserId: 14, interviewerName: 'Sana Khan', scheduledAt: ago(24 * 4), durationMinutes: 60, location: 'Room 2A', status: 'COMPLETED', scorecard: { ratings: [], recommendation: 'YES', summary: 'Solid systems thinking.', submittedAt: ago(24 * 4 - 2) } },
]
const kit = [
  { id: 'q1', requirementId: 1, question: 'Tell me about a Spring Boot service you designed from scratch. What would you change now?', followUps: ['How did you handle configuration across environments?'], anchors: { 1: 'Vague, no ownership', 2: 'Some detail, little reasoning', 3: 'Clear design with trade-offs', 4: 'Clear trade-offs plus lessons applied' } },
  { id: 'q2', requirementId: 4, question: 'Describe a deployment you ran on Kubernetes end to end.', followUps: ['What went wrong, and how did you find out?'], anchors: { 1: 'Has not done it', 2: 'Followed a runbook', 3: 'Owned a rollout', 4: 'Designed the rollout and recovery' } },
]

const notifications = [
  { id: 1, template: 'APPLICATION_RECEIVED', subject: 'New application: Senior Backend Engineer', body: 'Candidate #A-3040 applied. Matching is running.', createdAt: ago(0.3), readAt: null },
  { id: 2, template: 'INTERVIEW_ASSIGNED', subject: 'You are interviewing Meera Iyer', body: 'Senior Backend Engineer · in 3 days · 45 minutes', createdAt: ago(18), readAt: null },
  { id: 3, template: 'APPLICATION_UPDATE', subject: 'Work sample submitted', body: 'Candidate submitted "Design an idempotent refunds endpoint".', createdAt: ago(8.6), readAt: ago(8) },
]

const countBy = (status) => (status ? jobs.filter((j) => j.status === status).length : jobs.length)

export function routes({ role = 'HR_MANAGER' } = {}) {
  const user = role === 'ADMIN' ? admin : role === 'INTERVIEWER' ? interviewer : manager
  return [
    route('POST', '/auth/refresh', session(user)),
    route('POST', '/auth/logout', null),
    route('GET', '/auth/sso/providers', [{ id: 'okta', name: 'Okta' }]),
    route('GET', '/me/notifications/unread-count', { count: 2 }),
    route('GET', '/me/notifications', page(notifications, { size: 20 })),
    route('GET', '/me/mfa', { enabled: true, enabledAt: ago(24 * 40), pendingEnrollment: false, recoveryCodesRemaining: 2, requiredByOrganization: true, available: true }),
    route('GET', '/jobs', ({ query }) => {
      const size = Number(query.get('size') || 20)
      const status = query.get('status')
      if (size === 1) return page([], { size: 1, total: countBy(status) })
      const q = (query.get('search') || '').toLowerCase()
      const list = jobs.filter((j) => (!status || j.status === status) && (!q || j.title.toLowerCase().includes(q)))
      return page(list, { size })
    }),
    route('GET', '/jobs/(\\d+)', ({ match }) => jobs.find((j) => j.id === Number(match[1])) ?? fail(404, 'Not found')),
    route('GET', '/hr/jobs/(\\d+)/applicants', ({ query }) => {
      const byDate = query.get('sort') === 'appliedAt'
      const list = byDate ? [...applicants].sort((x, y) => y.appliedAt.localeCompare(x.appliedAt)) : applicants
      return page(list, { size: 25, total: 38 })
    }),
    route('GET', '/hr/jobs/(\\d+)/applicants/(\\d+)', ({ match }) => applicantDetail(Number(match[2]))),
    route('GET', '/hr/applications/(\\d+)/verification', verification),
    route('GET', '/hr/jobs/(\\d+)/work-samples', [{ id: 21, jobId: 101, title: 'Design an idempotent refunds endpoint', instructions: 'Design a POST /refunds endpoint that is safe to retry…', timeLimitMinutes: 90, rubric: [{ requirementId: 2, criterion: 'Schema & indexing', anchors: { 1: 'Missing', 2: 'Basic', 3: 'Sound', 4: 'Excellent' } }], status: 'ACTIVE', createdAt: ago(24 * 5), updatedAt: ago(24 * 5) }]),
    route('GET', '/hr/jobs/(\\d+)/work-sample-assignments', []),
    route('GET', '/hr/jobs/(\\d+)/interview-kit', { jobId: 101, questions: kit, generatedBy: 'rules', updatedAt: ago(24 * 4) }),
    route('GET', '/hr/interviewers', [{ userId: 14, name: 'Sana Khan', email: interviewer.email, role: 'INTERVIEWER' }]),
    route('GET', '/interviews/mine', interviews),
    route('GET', '/interviews/(\\d+)', ({ match }) => ({ ...(interviews.find((i) => i.id === Number(match[1])) ?? interviews[0]), kit, focusRequirements: applicantDetail(3001).requirements.slice(2, 4) })),
    route('GET', '/admin/dashboard', {
      totalUsers: 14, totalCandidates: 412, totalHrUsers: 14, totalJobs: 6, totalApplications: 134,
      applicationsByStatus: { SUBMITTED: 71, SHORTLISTED: 22, INTERVIEWING: 9, SELECTED: 4, REJECTED: 24, WITHDRAWN: 4 },
      jobsByStatus: { PUBLISHED: 3, DRAFT: 1, ON_HOLD: 1, CLOSED: 1 }, recentApplicationsCount: 17,
      health: { backend: 'UP', database: 'UP', aiService: 'UP', agentService: 'DOWN', queue: { pending: 3, failed: 1, dead: 0 } },
    }),
    route('GET', '/admin/users', page([
      { id: 10, email: admin.email, firstName: 'Dev', lastName: 'Kapoor', role: 'ADMIN', enabled: true, createdAt: ago(24 * 200) },
      { id: 11, email: manager.email, firstName: 'Priya', lastName: 'Raman', role: 'HR_MANAGER', enabled: true, createdAt: ago(24 * 150) },
      { id: 12, email: 'kabir.shah@northwind.example', firstName: 'Kabir', lastName: 'Shah', role: 'RECRUITER', enabled: true, createdAt: ago(24 * 90) },
      { id: 14, email: interviewer.email, firstName: 'Sana', lastName: 'Khan', role: 'INTERVIEWER', enabled: true, createdAt: ago(24 * 60) },
      { id: 15, email: 'old.contractor@northwind.example', firstName: 'Tarun', lastName: 'Bose', role: 'RECRUITER', enabled: false, createdAt: ago(24 * 300) },
    ], { size: 20 })),
    route('GET', '/admin/verification/settings', { mode: 'SHADOW', connectors: { GITHUB: true, GITLAB: true, WEBSITE: true, DOCUMENT: false, OPEN_BADGE: true, REFERENCE: true }, updatedAt: ago(24 * 3), updatedByName: 'Dev Kapoor' }),
    route('GET', '/admin/verification/metrics', { labelled: 118, agreement: 0.91, falseCorroborationRate: 0.017, kappa: 0.74, readyForVisible: false, byVerifier: [{ verifier: 'code-verifier', labelled: 80, agreement: 0.93, falseCorroborationRate: 0.012, kappa: 0.78 }, { verifier: 'web-verifier', labelled: 38, agreement: 0.87, falseCorroborationRate: 0.026, kappa: 0.66 }], byVerdict: [] }),
    route('GET', '/admin/verification/shadow-queue', page([{ verdictId: 7002, applicationId: 3001, revealed: false, subjectType: 'RESUME_FACT', subjectId: 902, claimType: 'SKILL', value: 'PostgreSQL', sourceKind: 'GITHUB', verifier: 'code-verifier', verdict: 'PARTLY_VERIFIED', ownershipProven: true, reasons: verification.claims[1].verdicts[0].reasons, integrityNotes: [], probeQuestion: 'How did you choose the partition key?', evidence: null, evidenceCount: 2, createdAt: ago(26), myLabel: null, labelCount: 1 }], { size: 10 })),
  ]
}

export const anonymousRoutes = [route('POST', '/auth/refresh', fail(401, 'No session.')), route('GET', '/auth/sso/providers', [{ id: 'okta', name: 'Okta' }])]

const clickText = (text) => async (p) => {
  await p.evaluate((t) => {
    const el = [...document.querySelectorAll('button, a, [role="tab"], summary')].find((n) => n.textContent.trim().startsWith(t))
    el?.click()
  }, text)
  await new Promise((r) => setTimeout(r, 700))
}

export const screens = [
  // Living style guide (src/design/gallery): every design-system component in the current theme.
  { name: 'design-system', path: '/__design', anonymous: true, settle: 900 },
  { name: 'login', path: '/login', anonymous: true },
  { name: 'jobs', path: '/jobs' },
  { name: 'job-applicants', path: '/jobs/101?tab=applicants' },
  { name: 'job-applicants-loading', path: '/jobs/101?tab=applicants', waitUntil: 'domcontentloaded', settle: 1200, override: [route('GET', '/hr/jobs/(\\d+)/applicants', delayed(20000, page([], { size: 25 })))] },
  { name: 'job-applicants-empty', path: '/jobs/101?tab=applicants', override: [route('GET', '/hr/jobs/(\\d+)/applicants', page([], { size: 25 }))] },
  { name: 'job-details', path: '/jobs/101?tab=details' },
  { name: 'job-interview-kit', path: '/jobs/101?tab=interview-kit' },
  { name: 'job-form', path: '/jobs/101/edit' },
  { name: 'applicant-blind', path: '/jobs/101/applicants/3014' },
  { name: 'applicant-revealed', path: '/jobs/101/applicants/3001' },
  { name: 'applicant-verification', path: '/jobs/101/applicants/3001?tab=verification' },
  { name: 'shortlist-dialog', path: '/jobs/101/applicants/3014', action: clickText('Shortlist') },
  { name: 'interviews', path: '/interviews' },
  { name: 'interview-detail', path: '/interviews/600' },
  { name: 'notifications', path: '/notifications' },
  { name: 'security', path: '/account/security' },
  { name: 'admin-overview', path: '/admin', role: 'ADMIN' },
  { name: 'admin-users', path: '/admin/users', role: 'ADMIN' },
  { name: 'admin-verification', path: '/admin/verification', role: 'ADMIN' },
]
