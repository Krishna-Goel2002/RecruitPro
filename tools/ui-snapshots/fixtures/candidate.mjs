// Candidate portal fixtures. Shapes follow frontend/src/api/types.ts. Every value is fictional.
import { ago, ahead, delayed, fail, page, route, session } from '../lib/mock.mjs'

const user = {
  id: 501, email: 'meera.iyer@example.com', firstName: 'Meera', lastName: 'Iyer', role: 'CANDIDATE', emailVerified: false,
}

/**
 * The backend serialises most DTOs with `default-property-inclusion: non_null`: null fields are absent, not null.
 * Jobs, applications, notifications, claims, resume facts and the profile go through this so the screens are
 * checked against what the server really sends. (Verification, work-sample and interview records are ALWAYS.)
 */
const nn = (value) => JSON.parse(JSON.stringify(value, (_key, v) => (v === null ? undefined : v)))

const req = (id, type, description, mandatory, extra = {}) => ({
  id, type, description, mandatory, knockout: false, minimumYears: null, alternatives: [], proficiency: null, ...extra,
})

const jobs = [
  {
    id: 101, organizationName: 'Northwind Labs', title: 'Senior Backend Engineer', department: 'Engineering',
    location: 'Bengaluru (hybrid)', jobType: 'FULL_TIME', experienceLevel: 'SENIOR', minimumSalary: 2800000,
    maximumSalary: 4200000, vacancies: 2, deadline: ahead(24 * 12), publishedAt: ago(24 * 6),
    description: 'Own the services behind our payments platform: design APIs, keep PostgreSQL fast, and help the team ship safely with good tests and clear reviews. You will pair with product on scoping and mentor two mid-level engineers.\n\nWe care about calm on-call, readable code and measured rollouts.',
    requirements: [
      req(1, 'SKILL', 'Java 17 and Spring Boot', true, { knockout: true, minimumYears: 3, proficiency: 'ADVANCED' }),
      req(2, 'SKILL', 'PostgreSQL schema design and query tuning', true),
      req(3, 'EXPERIENCE', 'Backend engineering in production', true, { minimumYears: 5 }),
      req(4, 'SKILL', 'Kubernetes', false, { alternatives: ['Amazon ECS', 'Nomad'] }),
      req(5, 'CERTIFICATION', 'AWS Certified Developer – Associate', false),
    ],
  },
  {
    id: 102, organizationName: 'Northwind Labs', title: 'Product Designer, Growth', department: 'Design', location: 'Remote (India)',
    jobType: 'FULL_TIME', experienceLevel: 'MID', minimumSalary: 1800000, maximumSalary: 2600000, vacancies: 1,
    deadline: ahead(24 * 20), publishedAt: ago(24 * 2),
    description: 'Design onboarding and activation flows end to end, from research to shipped UI.', requirements: [
      req(11, 'SKILL', 'Interaction design for web products', true), req(12, 'SKILL', 'Figma prototyping', false),
    ],
  },
  {
    id: 103, organizationName: 'Kestrel Health', title: 'Data Analyst (Clinical Ops)', department: 'Analytics', location: 'Pune',
    jobType: 'CONTRACT', experienceLevel: 'MID', minimumSalary: null, maximumSalary: null, vacancies: 3, deadline: null,
    publishedAt: ago(24 * 9), description: 'Build dashboards and weekly reporting for clinical operations teams. SQL heavy.',
    requirements: [req(21, 'SKILL', 'SQL', true), req(22, 'SKILL', 'Python (pandas)', false)],
  },
  {
    id: 104, organizationName: 'Arcfield Energy', title: 'Graduate Software Engineer', department: 'Engineering',
    location: 'Hyderabad', jobType: 'INTERNSHIP', experienceLevel: 'ENTRY', minimumSalary: 600000, maximumSalary: 800000,
    vacancies: 6, deadline: ahead(24 * 4), publishedAt: ago(24 * 15),
    description: 'A six-month programme rotating across platform, field tools and data teams.', requirements: [req(31, 'EDUCATION', 'B.Tech / B.E. in a computing field', true)],
  },
]

const resumes = [
  { id: 71, originalFilename: 'Meera_Iyer_Backend_2026.pdf', contentType: 'application/pdf', fileSize: 184_320, uploadedAt: ago(30) },
  { id: 70, originalFilename: 'Meera_Iyer_CV_old.docx', contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', fileSize: 96_000, uploadedAt: ago(24 * 60) },
]

const analysis = {
  resumeId: 71, status: 'COMPLETED', parserVersion: 'parser-2.3.1', errorCode: null, attemptCount: 1, analyzedAt: ago(29),
  flags: [{ code: 'TWO_COLUMN_LAYOUT', detail: null }],
  facts: [
    { id: 901, type: 'SKILL', value: 'Java', extractedValue: 'Java', evidence: 'Built settlement services in Java 17 / Spring Boot 3', sourceSection: 'Experience', tier: 'RESUME_STATED' },
    { id: 902, type: 'SKILL', value: 'PostgreSQL', extractedValue: 'PostgreSQL', evidence: 'Cut p95 query time 38% by partitioning ledger tables', sourceSection: 'Experience', tier: 'RESUME_STATED' },
    { id: 903, type: 'SKILL', value: 'Kubernetes', extractedValue: 'Kubernates', evidence: 'Deployed on Kubernates (EKS)', sourceSection: 'Skills', tier: 'RESUME_STATED', claimedValue: 'Kubernetes', claimId: 55 },
    { id: 904, type: 'EXPERIENCE', value: 'Backend Engineer, Finlytic (2019–2026)', extractedValue: 'Backend Engineer, Finlytic (2019–2026)', evidence: 'Backend Engineer · Finlytic · Jun 2019 – present', sourceSection: 'Experience', tier: 'RESUME_STATED' },
    { id: 905, type: 'EDUCATION', value: 'B.Tech Computer Science, VIT (2019)', extractedValue: 'B.Tech Computer Science, VIT (2019)', evidence: 'B.Tech, Computer Science — VIT Vellore, 2019', sourceSection: 'Education', tier: 'RESUME_STATED' },
    { id: 906, type: 'EMAIL', value: 'meera.iyer@example.com', extractedValue: 'meera.iyer@example.com', evidence: null, sourceSection: 'Header', tier: null },
  ],
}

const claims = [
  { id: 55, claimType: 'SKILL', value: 'Kubernetes', evidenceUrl: null, tier: 'SELF_CLAIMED', verifiedAt: null, verificationNote: null, createdAt: ago(28), verificationStatus: 'UNVERIFIED', sourceFactId: 903 },
  { id: 56, claimType: 'CERTIFICATION', value: 'AWS Certified Developer – Associate (2025)', evidenceUrl: 'https://www.credly.com/badges/example', tier: 'CORROBORATED', verifiedAt: ago(20), verificationNote: null, createdAt: ago(26), verificationStatus: 'VERIFIED' },
  { id: 57, claimType: 'OTHER', value: 'Speaker, PyCon India 2025 — "Idempotent payment APIs"', evidenceUrl: 'https://in.pycon.org/2025/talks/example', tier: 'SELF_CLAIMED', verifiedAt: null, verificationNote: null, createdAt: ago(5), verificationStatus: 'UNVERIFIED' },
]

const applications = [
  { id: 3001, jobId: 101, jobTitle: 'Senior Backend Engineer', organizationName: 'Northwind Labs', resumeId: 71, resumeFilename: resumes[0].originalFilename, status: 'SHORTLISTED', coverLetter: 'I have spent six years on payment systems…', appliedAt: ago(24 * 5), updatedAt: ago(20), statusChangedAt: ago(20) },
  { id: 3002, jobId: 103, jobTitle: 'Data Analyst (Clinical Ops)', organizationName: 'Kestrel Health', resumeId: 71, resumeFilename: resumes[0].originalFilename, status: 'SUBMITTED', coverLetter: null, appliedAt: ago(24 * 2), updatedAt: ago(48), statusChangedAt: null },
  { id: 2990, jobId: 104, jobTitle: 'Graduate Software Engineer', organizationName: 'Arcfield Energy', resumeId: 70, resumeFilename: resumes[1].originalFilename, status: 'REJECTED', coverLetter: null, appliedAt: ago(24 * 40), updatedAt: ago(24 * 30), statusChangedAt: ago(24 * 30) },
]

const workSamples = [
  { assignmentId: 801, jobTitle: 'Senior Backend Engineer', title: 'Design an idempotent refunds endpoint', instructions: null, timeLimitMinutes: 90, status: 'ASSIGNED', dueAt: ahead(52), startedAt: null, submittedAt: null, deadlineAt: null, files: [], cancelledAt: null, cancelReason: null },
]

const applicationDetail = {
  ...applications[0],
  timeline: [{ status: 'SUBMITTED', at: ago(24 * 5) }, { status: 'SHORTLISTED', at: ago(20) }],
  requirements: [
    { requirementId: 1, type: 'SKILL', description: 'Java 17 and Spring Boot', mandatory: true, verification: 'VERIFIED' },
    { requirementId: 2, type: 'SKILL', description: 'PostgreSQL schema design and query tuning', mandatory: true, verification: 'VERIFIED' },
    { requirementId: 3, type: 'EXPERIENCE', description: 'Backend engineering in production', mandatory: true, verification: 'VERIFIED' },
    { requirementId: 4, type: 'SKILL', description: 'Kubernetes', mandatory: false, verification: 'CLAIMED' },
    { requirementId: 5, type: 'CERTIFICATION', description: 'AWS Certified Developer – Associate', mandatory: false, verification: 'NOT_VERIFIED' },
  ],
  workSamples,
  interviews: [{ id: 601, scheduledAt: ahead(72), durationMinutes: 45, location: 'https://meet.example.com/nwl-be-3001', status: 'SCHEDULED' }],
}

const sources = [
  { id: 41, kind: 'GITHUB', identifier: 'meera-iyer', displayLabel: 'github.com/meera-iyer', ownershipStatus: 'PROVEN', ownershipMethod: 'OAUTH', challengeCode: 'rp-3kq9XvB2mWc8LpT0aZ7yRf1N', ownershipInstructions: [], consentedAt: ago(27), lastRun: { id: 9001, status: 'COMPLETED', finishedAt: ago(26), errorCode: null }, ownershipCheckedAt: ago(27), ownershipDetail: 'Signed in with GitHub', createdAt: ago(27) },
  { id: 42, kind: 'WEBSITE', identifier: 'https://meera.dev', displayLabel: 'meera.dev', ownershipStatus: 'UNPROVEN', ownershipMethod: null, challengeCode: 'rp-Y2s8QpL0vX1dK7tB4nWc9HaE', ownershipInstructions: ['Add a DNS TXT record, a small text file, or the code on your home page.'], consentedAt: ago(3), lastRun: null, ownershipCheckedAt: null, ownershipDetail: null, createdAt: ago(3) },
]

const v = (id, verdict, candidateStatus, sourceLabel, hours) => ({ id, verifier: 'code-verifier', verdict, candidateStatus, sourceLabel, createdAt: ago(hours) })
const ledger = [
  { subjectType: 'RESUME_FACT', subjectId: 901, claimType: 'SKILL', value: 'Java', tier: 'RESUME_STATED', verdicts: [v(7001, 'VERIFIED', 'SHARED', 'github.com/meera-iyer', 26)] },
  { subjectType: 'RESUME_FACT', subjectId: 902, claimType: 'SKILL', value: 'PostgreSQL', tier: 'RESUME_STATED', verdicts: [v(7002, 'PARTLY_VERIFIED', 'AWAITING_CANDIDATE', 'github.com/meera-iyer', 26)] },
  { subjectType: 'CANDIDATE_CLAIM', subjectId: 55, claimType: 'SKILL', value: 'Kubernetes', tier: 'SELF_CLAIMED', verdicts: [v(7003, 'NOT_FOUND', 'AWAITING_CANDIDATE', 'github.com/meera-iyer', 26)] },
  { subjectType: 'RESUME_FACT', subjectId: 904, claimType: 'EXPERIENCE', value: 'Backend Engineer, Finlytic (2019–2026)', tier: 'RESUME_STATED', verdicts: [v(7004, 'NOT_CHECKABLE', 'HIDDEN', 'github.com/meera-iyer', 26)] },
  { subjectType: 'CANDIDATE_CLAIM', subjectId: 57, claimType: 'OTHER', value: 'Speaker, PyCon India 2025', tier: 'SELF_CLAIMED', verdicts: [] },
]

const verdictDetail = {
  id: 7002, subjectType: 'RESUME_FACT', subjectId: 902, claimType: 'SKILL', value: 'PostgreSQL', verifier: 'code-verifier', verifierVersion: '1.4.0',
  verdict: 'PARTLY_VERIFIED', ownershipProven: true, candidateStatus: 'AWAITING_CANDIDATE', candidateNote: null, candidateRespondedAt: null, current: true,
  sourceId: 41, sourceKind: 'GITHUB', sourceLabel: 'github.com/meera-iyer', referenceId: null, runId: 9001,
  summary: [{ text: 'Two repositories use PostgreSQL through migrations and SQL files.', evidenceKeys: ['e1'], quote: null }],
  reasons: [
    { text: 'Migration files create partitioned tables, matching the resume statement about partitioning.', evidenceKeys: ['e1'], quote: 'PARTITION BY RANGE (created_at)' },
    { text: 'No query-tuning artefacts (EXPLAIN plans, index changes) were found.', evidenceKeys: [], quote: null },
  ],
  integrityNotes: [],
  probeQuestion: 'Walk me through how you chose the partition key for the ledger tables.',
  evidence: [{ id: 1, key: 'e1', kind: 'REPOSITORY', url: 'https://github.com/meera-iyer/ledger-svc/blob/main/db/V7__partition.sql', title: 'ledger-svc · db/V7__partition.sql', retrievedAt: ago(26), contentHash: 'sha256:9f2c4e1ab07d5c3e88f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f7', excerpt: 'CREATE TABLE ledger_entries (...) PARTITION BY RANGE (created_at);', attributes: null }],
  createdAt: ago(26),
}

const references = [
  { id: 31, refereeName: 'Arjun Mehta', refereeEmail: 'arjun.mehta@finlytic.example', relationship: 'MANAGER', employerName: 'Finlytic', employerDomain: 'finlytic.example', claims: [{ subjectType: 'RESUME_FACT', subjectId: 904, claimType: 'EXPERIENCE', value: 'Backend Engineer, Finlytic (2019–2026)' }], status: 'SENT', expiresAt: ahead(24 * 13), answeredAt: null, createdAt: ago(24), verdictCount: 0, errorCode: null },
]

const notifications = [
  { id: 1, template: 'APPLICATION_STATUS_CHANGED', subject: 'Update on your application for Senior Backend Engineer', body: 'Northwind Labs moved your application forward. Next: a work sample.', createdAt: ago(20), readAt: null },
  { id: 2, template: 'WORK_SAMPLE_ASSIGNED', subject: 'A work sample is waiting for you: Design an idempotent refunds endpoint', body: 'Start by ' + new Date(Date.now() + 52 * 3600e3).toDateString() + '. 90 minutes once started.', createdAt: ago(19), readAt: null },
  { id: 3, template: 'VERIFICATION_READY', subject: 'Your verification results are ready', body: '4 results are waiting for your review.', createdAt: ago(26), readAt: ago(25) },
]

const profile = {
  id: 501, email: user.email, firstName: 'Meera', lastName: 'Iyer', address: null, city: 'Bengaluru', state: 'Karnataka', country: 'India', zipCode: '560034',
  linkedinUrl: 'https://www.linkedin.com/in/example', portfolioUrl: 'https://meera.dev', yearsOfExperience: 7, currentCompany: 'Finlytic',
  currentDesignation: 'Backend Engineer', expectedSalary: 3800000, noticePeriodDays: 60, profileSummary: 'Backend engineer focused on payments and data-heavy services.', createdAt: ago(24 * 90), updatedAt: ago(24 * 3),
}

export const routes = [
  route('POST', '/auth/refresh', session(user)),
  route('POST', '/auth/logout', null),
  route('GET', '/me/notifications/unread-count', { count: 2 }),
  route('GET', '/me/notifications', nn(page(notifications, { size: 20 }))),
  route('GET', '/jobs', ({ query }) => {
    const q = (query.get('search') || '').toLowerCase()
    const list = jobs.filter((j) => !q || j.title.toLowerCase().includes(q))
    const pageNo = Number(query.get('page') || 0)
    return nn({ ...page(list, { size: 10 }), page: pageNo, content: pageNo > 0 ? [] : list })
  }),
  route('GET', '/jobs/(\\d+)', ({ match }) => {
    const job = jobs.find((j) => j.id === Number(match[1]))
    return job ? nn(job) : fail(404, `Job not found: ${match[1]}`)
  }),
  route('GET', '/candidates/me', nn(profile)),
  route('GET', '/candidates/me/resumes', resumes),
  route('GET', '/candidates/me/resumes/(\\d+)/analysis', ({ match }) => nn(Number(match[1]) === 71 ? analysis : { ...analysis, resumeId: 70, status: 'NEEDS_OCR', facts: [], flags: [{ code: 'OCR_USED', detail: null }] })),
  route('GET', '/candidates/me/claims', nn(claims)),
  route('GET', '/candidates/me/applications', nn(page(applications, { size: 10 }))),
  route('GET', '/candidates/me/applications/(\\d+)', { ...nn(applicationDetail), workSamples, interviews: applicationDetail.interviews }),
  route('GET', '/candidates/me/work-samples', workSamples),
  route('GET', '/candidates/me/interviews', [{ id: 601, jobTitle: 'Senior Backend Engineer', scheduledAt: ahead(72), durationMinutes: 45, location: 'https://meet.example.com/nwl-be-3001', status: 'SCHEDULED' }]),
  route('GET', '/candidates/me/equal-opportunity', fail(404, 'No answers stored.')),
  route('GET', '/candidates/me/verification/sources', sources),
  route('GET', '/candidates/me/verification/ledger', ledger),
  route('GET', '/candidates/me/verification/verdicts/(\\d+)', verdictDetail),
  route('GET', '/candidates/me/verification/references', references),
  route('POST', '/jobs/(\\d+)/applications', ({ match }) => ({ ...applications[1], id: 3100, jobId: Number(match[1]), jobTitle: jobs.find((j) => j.id === Number(match[1]))?.title ?? 'Job', status: 'SUBMITTED', appliedAt: ago(0) })),
  route('POST', '/candidates/me/resumes', { ...resumes[0], id: 72, uploadedAt: ago(0) }),
  route('PATCH', '/me/notifications/(\\d+)/read', null),
  route('POST', '/me/notifications/read-all', null),
  route('GET', '/public/references/([^/]+)', ({ match }) => (match[1] === 'expired'
    ? fail(410, 'This request is closed.')
    : { candidateFirstName: 'Meera', relationship: 'MANAGER', employerName: 'Finlytic', expiresAt: ahead(24 * 13), claims: [
      { id: 'fact:904', claimType: 'EXPERIENCE', value: 'Backend Engineer, Finlytic (2019–2026)' },
      { id: 'fact:902', claimType: 'SKILL', value: 'PostgreSQL schema design and query tuning' },
      { id: 'claim:55', claimType: 'SKILL', value: 'Kubernetes' },
    ] })),
]

/** Signed-out variant: refresh refused, so guarded routes redirect to /login. */
export const anonymousRoutes = [route('POST', '/auth/refresh', fail(401, 'No session.')), ...routes.filter(([m, re]) => !(m === 'POST' && re.test('/auth/refresh')))]

const clickText = (text) => async (p) => {
  await p.evaluate((t) => {
    const el = [...document.querySelectorAll('button, a, summary')].find((n) => n.textContent.trim().startsWith(t))
    el?.click()
  }, text)
  await new Promise((r) => setTimeout(r, 700))
}

const clickSel = (sel) => async (p) => {
  await p.evaluate((q) => document.querySelector(q)?.click(), sel)
  await new Promise((r) => setTimeout(r, 500))
}


const typeInto = (sel, text) => async (p) => {
  await p.focus(sel)
  await p.keyboard.type(text)
  await new Promise((r) => setTimeout(r, 500))
}

// A brand-new account: nothing uploaded, nothing applied, email not confirmed.
const freshUser = [
  route('GET', '/candidates/me/resumes', []),
  route('GET', '/candidates/me/applications', page([], { size: 10 })),
  route('GET', '/candidates/me/verification/sources', []),
  route('GET', '/candidates/me/verification/ledger', []),
  route('GET', '/candidates/me/verification/references', []),
  route('GET', '/candidates/me/work-samples', []),
  route('GET', '/candidates/me/interviews', []),
  route('GET', '/candidates/me/claims', []),
  route('GET', '/me/notifications', page([], { size: 20 })),
  route('GET', '/me/notifications/unread-count', { count: 0 }),
]
const started = (minutesLeft) => [route('GET', '/candidates/me/work-samples', [{ ...workSamples[0], status: 'STARTED', startedAt: ago((90 - minutesLeft) / 60), deadlineAt: ahead(minutesLeft / 60), instructions: 'Design a POST /refunds endpoint that is safe to retry.\n\n1. Describe the request and response shapes.\n2. Explain how you make it idempotent (keys, storage, expiry).\n3. List the failure cases you would test.\n\nA short design note is enough; diagrams are welcome as attachments.' }])]
const runningSources = [route('GET', '/candidates/me/verification/sources', [{ ...sources[0], lastRun: { id: 9002, status: 'RUNNING', finishedAt: null, errorCode: null } }, sources[1]])]

export const screens = [
  // Living style guide (src/design/gallery): every design-system component in the current theme.
  { name: 'design-system', path: '/__design', anonymous: true, settle: 900 },
  { name: 'login', path: '/login', anonymous: true },
  { name: 'register', path: '/register', anonymous: true },
  { name: 'jobs', path: '/jobs' },
  { name: 'jobs-empty', path: '/jobs?search=zzzz' },
  { name: 'jobs-loading', path: '/jobs', waitUntil: 'domcontentloaded', settle: 1200, override: [route('GET', '/jobs', delayed(20000, page([], { size: 10 })))] },
  { name: 'job-detail', path: '/jobs/101' },
  { name: 'apply-dialog', path: '/jobs/102', action: clickText('Apply for this role') },
  { name: 'apply-review', path: '/jobs/102', action: async (p) => { await clickText('Apply for this role')(p); await clickText('Review application')(p) } },
  { name: 'apply-sent', path: '/jobs/102', action: async (p) => { await clickText('Apply for this role')(p); await clickText('Review application')(p); await clickText('Submit application')(p) } },
  { name: 'apply-no-resume', path: '/jobs/102', override: [route('GET', '/candidates/me/resumes', [])], action: clickText('Apply for this role') },
  { name: 'job-detail-new', path: '/jobs/102' },
  { name: 'job-detail-closed', path: '/jobs/999' },
  { name: 'jobs-filters-sheet', path: '/jobs', action: clickText('Filters') },
  { name: 'applications-empty', path: '/applications', override: [route('GET', '/candidates/me/applications', page([], { size: 10 })), route('GET', '/candidates/me/resumes', [])] },
  { name: 'application-interview', path: '/applications/3002', override: [route('GET', '/candidates/me/applications/(\\d+)', { ...applicationDetail, id: 3002, status: 'INTERVIEWING', workSamples: [{ ...workSamples[0], status: 'SUBMITTED', submittedAt: ago(30) }] })] },
  { name: 'application-withdrawn', path: '/applications/2990', override: [route('GET', '/candidates/me/applications/(\\d+)', { ...applicationDetail, id: 2990, status: 'WITHDRAWN', requirements: null, workSamples: [], interviews: [], timeline: [{ status: 'SUBMITTED', at: ago(24 * 40) }, { status: 'WITHDRAWN', at: ago(24 * 30) }] })] },
  { name: 'application-withdraw-confirm', path: '/applications/3001', action: async (p) => { await clickSel('[aria-label="More actions"]')(p); await clickSel('[role="menuitem"]')(p) } },
  { name: 'applications', path: '/applications' },
  { name: 'application-detail', path: '/applications/3001' },
  { name: 'resumes', path: '/resumes' },
  { name: 'evidence', path: '/evidence' },
  { name: 'verification', path: '/verification' },
  { name: 'verdict-detail', path: '/verification?review=7002', settle: 900 },
  { name: 'work-samples', path: '/work-samples' },
  { name: 'work-sample-start', path: '/work-samples/801' },
  { name: 'interviews', path: '/interviews' },
  { name: 'notifications', path: '/notifications' },
  { name: 'profile', path: '/profile' },
  { name: 'privacy', path: '/privacy' },
  { name: 'referee', path: '/reference/tok-demo', anonymous: true },
  { name: 'referee-closed', path: '/reference/expired', anonymous: true },
  // Overview
  { name: 'home', path: '/home', settle: 800 },
  { name: 'home-new', path: '/home', override: freshUser, settle: 800 },
  { name: 'home-reading', path: '/home', override: [route('GET', '/candidates/me/applications', page([], { size: 10 })), route('GET', '/candidates/me/verification/sources', []), route('GET', '/candidates/me/resumes/(\\d+)/analysis', { ...analysis, status: 'PROCESSING', facts: [] })], settle: 800 },
  // Strengthen
  { name: 'verification-empty', path: '/verification', override: freshUser },
  { name: 'verification-running', path: '/verification', override: runningSources },
  { name: 'add-source-choose', path: '/verification?add=source&step=choose', settle: 800 },
  { name: 'add-source-connect', path: '/verification?add=source&step=connect&kind=GITHUB', settle: 800 },
  { name: 'add-source-connect-website', path: '/verification?add=source&step=connect&kind=WEBSITE', action: clickText('Connect'), settle: 800 },
  { name: 'add-source-prove', path: '/verification?add=source&step=prove&kind=WEBSITE&source=42', settle: 800 },
  { name: 'add-source-check', path: '/verification?add=source&step=check&kind=GITHUB&source=41', settle: 800 },
  { name: 'add-source-running', path: '/verification?add=source&step=check&kind=GITHUB&source=41', override: runningSources, settle: 800 },
  { name: 'reference-drawer', path: '/verification?ask=reference', settle: 800 },
  // Resume
  { name: 'resumes-reading', path: '/resumes', override: [route('GET', '/candidates/me/resumes/(\\d+)/analysis', { ...analysis, status: 'PROCESSING', facts: [], flags: [] })] },
  { name: 'resumes-failed', path: '/resumes', override: [route('GET', '/candidates/me/resumes/(\\d+)/analysis', { ...analysis, status: 'FAILED', errorCode: 'ENCRYPTED_PDF', facts: [], flags: [] })] },
  { name: 'resumes-empty', path: '/resumes', override: [route('GET', '/candidates/me/resumes', [])] },
  { name: 'resume-correction', path: '/resumes', action: clickText('Suggest a correction') },
  // Claims
  { name: 'evidence-empty', path: '/evidence', override: [route('GET', '/candidates/me/claims', [])] },
  { name: 'evidence-add', path: '/evidence', action: clickText('Add a claim'), settle: 600 },
  // Work samples
  { name: 'work-sample-started', path: '/work-samples/801', override: started(42) },
  { name: 'work-sample-final', path: '/work-samples/801', override: started(0.8) },
  { name: 'work-sample-submitted', path: '/work-samples/801', override: [route('GET', '/candidates/me/work-samples', [{ ...workSamples[0], status: 'SUBMITTED', startedAt: ago(3), submittedAt: ago(2), files: [{ id: 1, originalName: 'refunds-design.pdf', contentType: 'application/pdf', sizeBytes: 248000, sha256: 'ab', uploadedAt: ago(2) }] }])] },
  { name: 'work-sample-expired', path: '/work-samples/801', override: [route('GET', '/candidates/me/work-samples', [{ ...workSamples[0], status: 'EXPIRED', dueAt: ago(5) }])] },
  // Completeness sweep: a running draft can be discarded; a closed task's unsent draft can be deleted.
  { name: 'work-sample-draft', path: '/work-samples/801', override: started(42), action: typeInto('textarea', 'Clients send an Idempotency-Key header; the server stores the key with the response for 24 hours.') },
  { name: 'work-sample-discard', path: '/work-samples/801', override: started(42), action: async (p) => { await typeInto('textarea', 'First attempt')(p); await clickText('Discard draft')(p) } },
  { name: 'work-sample-expired-draft', path: '/work-samples/801', override: [route('GET', '/candidates/me/work-samples', [{ ...workSamples[0], status: 'EXPIRED', startedAt: ago(3), dueAt: ago(5) }])],
    action: async (p) => {
      await p.evaluate(() => { localStorage.setItem('draft-work-sample-801', 'Clients send an Idempotency-Key header; the server stores it with the response.'); localStorage.setItem('draft-work-sample-url-801', 'https://docs.example.org/refunds-design') })
      await p.reload({ waitUntil: 'networkidle0' })
      await new Promise((r) => setTimeout(r, 500))
    } },
  { name: 'work-samples-empty', path: '/work-samples', override: [route('GET', '/candidates/me/work-samples', [])] },
  // Interviews, inbox, profile
  { name: 'interviews-empty', path: '/interviews', override: [route('GET', '/candidates/me/interviews', [])] },
  { name: 'notifications-empty', path: '/notifications', override: freshUser },
  { name: 'profile-dirty', path: '/profile', action: typeInto('input[autocomplete="address-level2"]', ' East') },
  { name: 'not-found', path: '/no-such-page' },
  // Referee
  { name: 'referee-invalid', path: '/reference/unknown-token', anonymous: true, override: [route('GET', '/public/references/unknown-token', fail(404, 'Not found.'))] },
  { name: 'referee-missing', path: '/reference/tok-demo', anonymous: true, action: clickText('Send answers') },
  // Auth states
  { name: 'login-validation', path: '/login', anonymous: true, action: async (p) => { await clickText('Sign in')(p) } },
  { name: 'forgot-password', path: '/forgot-password', anonymous: true },
  { name: 'reset-password', path: '/reset-password?token=abc', anonymous: true },
  { name: 'verify-email-failed', path: '/verify-email?token=used', anonymous: true, override: [route('POST', '/auth/verify-email', fail(400, 'This link has already been used.'))] },
  // Loading skeletons (answers held back)
  { name: 'home-loading', path: '/home', waitUntil: 'domcontentloaded', settle: 1400, override: [route('GET', '/candidates/me/verification/ledger', delayed(20000, []))] },
  { name: 'verification-loading', path: '/verification', waitUntil: 'domcontentloaded', settle: 1400, override: [route('GET', '/candidates/me/verification/ledger', delayed(20000, []))] },
  { name: 'application-loading', path: '/applications/3001', waitUntil: 'domcontentloaded', settle: 1400, override: [route('GET', '/candidates/me/applications/(\\d+)', delayed(20000, applicationDetail))] },
  { name: 'resumes-loading', path: '/resumes', waitUntil: 'domcontentloaded', settle: 1400, override: [route('GET', '/candidates/me/resumes', delayed(20000, resumes))] },
  { name: 'error-verification', path: '/verification', override: [route('GET', '/candidates/me/verification/ledger', fail(503, 'The service is unavailable.'))] },
  { name: 'error-jobs', path: '/jobs', override: [route('GET', '/jobs', fail(503, 'The service is unavailable.'))] },
  // R5 bug hunt: states added with the fixes (failed loads no longer look like a new account, URL edge cases).
  // 5xx answers are retried twice (1 s, 2 s) before the error shows, hence the long settle.
  { name: 'home-error', path: '/home', settle: 4500, override: [route('GET', '/candidates/me/resumes', fail(503, 'The service is unavailable.'))] },
  { name: 'error-sources', path: '/verification', settle: 4500, override: [route('GET', '/candidates/me/verification/sources', fail(503, 'The service is unavailable.'))] },
  { name: 'jobs-past-end', path: '/jobs?page=7' },
  { name: 'add-source-connect-nokind', path: '/verification?add=source&step=connect&kind=NOPE', settle: 800 },
  { name: 'reference-drawer-limit', path: '/verification?ask=reference', settle: 800, override: [route('GET', '/candidates/me/verification/references', [1, 2, 3, 4, 5].map((n) => ({ ...references[0], id: 30 + n, refereeName: `Referee ${n}` })))] },
  // Integration pass: resume delete (R2-U9), coded limits (409 SOURCE_LIMIT / 429 with Retry-After).
  { name: 'resumes-delete-confirm', path: '/resumes', action: clickSel('button[aria-label^="Delete "]') },
  { name: 'resumes-delete-in-use', path: '/resumes', override: [route('DELETE', '/candidates/me/resumes/(\\d+)', fail(409, 'This resume is used by an application and cannot be deleted'))], action: async (p) => { await clickSel('button[aria-label^="Delete "]')(p); await clickText('Delete resume')(p) } },
  { name: 'add-source-github-limited', path: '/verification?add=source&step=connect&kind=GITHUB', settle: 800, override: [route('GET', '/candidates/me/verification/oauth/github/start', { ...fail(429, 'You started connecting GitHub too often. Please try again later.'), __headers: { 'Retry-After': '1800' } })], action: async (p) => { await clickSel('.v-add-body input[type="checkbox"]')(p); await clickSel('.v-add-body button[type="submit"]')(p); await new Promise((r) => setTimeout(r, 700)) } },
]
