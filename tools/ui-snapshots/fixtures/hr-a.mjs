// HR part A fixture extension: sign-in states, first run, jobs, ranking (peek, compare, triage), wizard, job tabs.
// Loaded automatically by snap.mjs (fixtures/<portal>-*.mjs). Every value is fictional.
import { ago, ahead, delayed, fail, page, route, session } from '../lib/mock.mjs'

const recruiter = {
  id: 12, email: 'kabir.shah@northwind.example', firstName: 'Kabir', lastName: 'Shah', role: 'RECRUITER',
  organizationId: 1, organizationName: 'Northwind Labs', emailVerified: true,
}
const newAdmin = {
  id: 30, email: 'asha.menon@lumen.example', firstName: 'Asha', lastName: 'Menon', role: 'ADMIN',
  organizationId: 9, organizationName: 'Lumen Health', emailVerified: false,
}

/** Only role-specific answers live here, so the shared and part-B routes are never shadowed. */
export function routes({ role } = {}) {
  if (role === 'RECRUITER') return [route('POST', '/auth/refresh', session(recruiter))]
  if (role === 'NEW_ADMIN') {
    return [
      route('POST', '/auth/refresh', session(newAdmin)),
      route('GET', '/me/mfa', { enabled: false, enabledAt: null, pendingEnrollment: false, recoveryCodesRemaining: 0, requiredByOrganization: false, available: true }),
      route('GET', '/admin/invitations', []),
      route('GET', '/admin/users', page([{ id: 30, email: newAdmin.email, firstName: 'Asha', lastName: 'Menon', role: 'ADMIN', enabled: true, createdAt: ago(1) }], { size: 1 })),
      route('GET', '/admin/organization', { id: 9, name: 'Lumen Health', slug: 'lumen-health', blindReview: true, shareVerificationStatus: true, requireStaffMfa: false, ssoAllowedDomains: [] }),
      route('GET', '/jobs', ({ query }) => page([], { size: Number(query.get('size') || 20), total: 0 })),
    ]
  }
  return []
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** Fill the sign-in form and submit it. */
const signIn = (extra) => async (p) => {
  await p.type('input[type="email"]', 'priya.raman@northwind.example')
  await p.type('input[type="password"]', 'correct horse 42')
  await p.click('form button[type="submit"]')
  await sleep(700)
  if (extra) await extra(p)
}
const clickText = (text) => async (p) => {
  await p.evaluate((t) => {
    const el = [...document.querySelectorAll('button, a, [role="tab"], [role="menuitem"], summary, label')].find((n) => n.textContent.trim().startsWith(t))
    el?.click()
  }, text)
  await sleep(600)
}
const press = (...keys) => async (p) => {
  for (const key of keys) {
    await p.keyboard.press(key)
    await sleep(250)
  }
  await sleep(500)
}

const mfaChallenge = { mfaRequired: true, mfaEnrollmentRequired: false, mfaToken: 'mfa-token', expiresInSeconds: 300 }
const enrolChallenge = { ...mfaChallenge, mfaEnrollmentRequired: true }
const enrolment = { secret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/Workspace:priya.raman%40northwind.example?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=Workspace' }
const codes = ['k7rq2-m9xtd', 'p4vwn-8hcze', 'f2lsa-t6ybq', 'w9gdk-3mnrx', 'b5hjt-q8pcl', 'z3ncu-7evkw', 'r6tyb-2fmsa', 'd8qxe-5wlgh', 'h1vzm-9rkcp', 'n4ptf-6sjdu']

const reqs = [
  { id: 1, type: 'SKILL', description: 'Java 17 and Spring Boot', mandatory: true, knockout: true, minimumYears: 3, alternatives: [], proficiency: 'ADVANCED' },
  { id: 2, type: 'SKILL', description: 'PostgreSQL schema design and query tuning', mandatory: true, knockout: false, minimumYears: null, alternatives: [], proficiency: null },
  { id: 3, type: 'EXPERIENCE', description: 'Backend engineering in production', mandatory: true, knockout: false, minimumYears: 5, alternatives: [], proficiency: null },
  { id: 4, type: 'SKILL', description: 'Kubernetes', mandatory: false, knockout: false, minimumYears: null, alternatives: ['Amazon ECS', 'Nomad'], proficiency: null },
]
const draftJob = {
  id: 106, organizationId: 1, title: 'Engineering Manager, Payments', department: 'Engineering', location: 'Bengaluru', jobType: 'FULL_TIME',
  experienceLevel: 'LEAD', minimumSalary: 4500000, maximumSalary: 6200000, vacancies: 1, status: 'DRAFT', publishedAt: null, deadline: ahead(24 * 21),
  closedReason: null, requirementsVersion: 1, createdByEmail: 'priya.raman@northwind.example', createdByName: 'Priya Raman', createdAt: ago(26), updatedAt: ago(2),
  applicationCount: 0, requirements: [],
  description: 'Lead the payments platform team of seven engineers.\n\nWhat you will do\n- Run planning and delivery for settlement and refunds services\n- Coach engineers and hire for the team\n\nWhat you need\n- At least 3 years managing backend engineers\n- Strong knowledge of Java and Spring Boot\n- Experience with PostgreSQL or MySQL at scale\n- Young and energetic rockstar, culture fit\n\nNice to have\n- Kubernetes\n- AWS Certified Solutions Architect',
}
const compiled = {
  engine: 'rules',
  warnings: [
    { phrase: 'Young', kind: 'AGE_CODED', suggestion: 'Describe the work instead of the person, e.g. "comfortable with a fast release cadence".' },
    { phrase: 'rockstar', kind: 'GENDER_CODED', suggestion: 'Use a plain job-related description such as "skilled engineer".' },
    { phrase: 'culture fit', kind: 'EXCLUSIONARY', suggestion: 'Name the specific behaviours you need, e.g. "writes clear design documents".' },
  ],
  requirements: [
    { type: 'EXPERIENCE', description: 'Managing backend engineers', mandatory: true, knockout: true, minimumYears: 3, alternatives: [], proficiency: null, rationale: '"At least 3 years managing backend engineers"' },
    { type: 'SKILL', description: 'Java and Spring Boot', mandatory: true, knockout: false, minimumYears: null, alternatives: [], proficiency: 'ADVANCED', rationale: '"Strong knowledge of Java and Spring Boot"' },
    { type: 'SKILL', description: 'PostgreSQL', mandatory: true, knockout: false, minimumYears: null, alternatives: ['MySQL'], proficiency: null, rationale: '"Experience with PostgreSQL or MySQL at scale"' },
    { type: 'OTHER', description: 'Young and energetic rockstar, culture fit', mandatory: true, knockout: false, minimumYears: null, alternatives: [], proficiency: null, rationale: '"Young and energetic rockstar, culture fit"' },
    { type: 'SKILL', description: 'Kubernetes', mandatory: false, knockout: false, minimumYears: null, alternatives: [], proficiency: null, rationale: 'Listed under "Nice to have"' },
    { type: 'CERTIFICATION', description: 'AWS Certified Solutions Architect', mandatory: false, knockout: false, minimumYears: null, alternatives: [], proficiency: null, rationale: 'Listed under "Nice to have"' },
  ],
}
const fairness = {
  scope: 'JOB', jobId: 101, minimumGroupSize: 5, surveyResponseRate: 0.68, note: 'A flag is a reason to examine how decisions were made, not proof of discrimination.',
  dimensions: [
    { dimension: 'gender', stages: [
      { stage: 'APPLIED', groups: [{ group: 'WOMAN', count: 14, selectionRate: 1 }, { group: 'MAN', count: 19, selectionRate: 1 }, { group: 'NON_BINARY', count: null, selectionRate: null }], adverseImpactRatio: 1, flagged: false },
      { stage: 'SHORTLISTED', groups: [{ group: 'WOMAN', count: 4, selectionRate: 0.29 }, { group: 'MAN', count: 8, selectionRate: 0.42 }, { group: 'NON_BINARY', count: null, selectionRate: null }], adverseImpactRatio: 0.68, flagged: true },
      { stage: 'INTERVIEWING', groups: [{ group: 'WOMAN', count: 3, selectionRate: 0.21 }, { group: 'MAN', count: 4, selectionRate: 0.21 }, { group: 'NON_BINARY', count: null, selectionRate: null }], adverseImpactRatio: 1, flagged: false },
    ] },
    { dimension: 'ageBand', stages: [
      { stage: 'APPLIED', groups: [{ group: '25_34', count: 18, selectionRate: 1 }, { group: '35_44', count: 11, selectionRate: 1 }], adverseImpactRatio: 1, flagged: false },
      { stage: 'SHORTLISTED', groups: [{ group: '25_34', count: 7, selectionRate: 0.39 }, { group: '35_44', count: 4, selectionRate: 0.36 }], adverseImpactRatio: 0.93, flagged: false },
    ] },
  ],
}
const assignments = [
  { id: 801, workSampleId: 21, workSampleTitle: 'Design an idempotent refunds endpoint', applicationId: 3001, status: 'SUBMITTED', assignedAt: ago(30), dueAt: ahead(20), startedAt: ago(10), submittedAt: ago(8.6), responseText: null, responseUrl: null, scores: [], totalScore: null, scoredAt: null, scoredByName: null,
    files: [{ id: 61, originalName: 'refunds-sequence.pdf', contentType: 'application/pdf', sizeBytes: 482113, sha256: 'ab', uploadedAt: ago(8.7) }, { id: 62, originalName: 'schema.sql', contentType: 'text/plain', sizeBytes: 2210, sha256: 'cd', uploadedAt: ago(8.7) }] },
  { id: 802, workSampleId: 21, workSampleTitle: 'Design an idempotent refunds endpoint', applicationId: 3009, status: 'SUBMITTED', assignedAt: ago(28), dueAt: ahead(30), startedAt: ago(5), submittedAt: ago(3), responseText: null, responseUrl: null, scores: [], totalScore: null, scoredAt: null, scoredByName: null, files: [] },
]
const samples = [
  { id: 21, jobId: 101, title: 'Design an idempotent refunds endpoint', instructions: 'Design a POST /refunds endpoint that is safe to retry. Describe the request, the stored state, and how a repeated request is detected.\n\nWrite two tests you would add first.', timeLimitMinutes: 90, status: 'ACTIVE', createdAt: ago(24 * 5), updatedAt: ago(24 * 5),
    rubric: [
      { requirementId: 2, criterion: 'Schema and indexing', anchors: { 1: 'No persistent state for retries', 2: 'Stores requests but no uniqueness guarantee', 3: 'Unique key on the idempotency token with a sensible index', 4: 'Also explains retention and the race between two identical requests' } },
      { requirementId: 1, criterion: 'API design', anchors: { 1: 'Endpoint unclear', 2: 'Endpoint works for the happy path only', 3: 'Clear error model and status codes', 4: 'Clear error model, versioning and examples' } },
    ] },
  { id: 22, jobId: 101, title: 'Tune a slow ledger query', instructions: 'Given the schema and EXPLAIN output, propose changes.', timeLimitMinutes: 45, status: 'DRAFT', createdAt: ago(20), updatedAt: ago(3), rubric: [{ requirementId: 2, criterion: 'Query tuning', anchors: { 1: 'No improvement', 2: 'Adds an index blindly', 3: 'Reads the plan and fixes the cause', 4: 'Fixes the cause and explains the trade-off' } }] },
]

// ---------------------------------------------------------------- ranking data (screen-level overrides only)
const T = { C: 'SELF_CLAIMED', R: 'RESUME_STATED', K: 'CORROBORATED', D: 'DEMONSTRATED', I: 'INTERVIEW_VERIFIED' }
const reqText = ['Java 17 and Spring Boot', 'PostgreSQL schema design and query tuning', 'Backend engineering in production', 'Kubernetes', 'AWS Certified Developer – Associate', 'Event streaming (Kafka or Pulsar)']
const people = [
  // id, name|null (blind), status, group, rank, verified, claimed, tiers (6 letters, '-' = not observed), extra
  [3001, 'Meera Iyer', 'SHORTLISTED', 'RANKED', 1, 86, 93, 'IDRCK-', { workSampleStatus: 'SCORED', interviewCount: 1, flags: ['TWO_COLUMN_LAYOUT'] }],
  [3014, null, 'SUBMITTED', 'RANKED', 2, 79, 79, 'KRRR-R', {}],
  [3022, null, 'SUBMITTED', 'RANKED', 3, 71, 86, 'RRRC-C', { appliedAt: 0.5 }],
  [3009, 'Rohan Desai', 'INTERVIEWING', 'RANKED', 4, 64, 64, 'RDR--R', { interviewCount: 1 }],
  [3031, null, 'SUBMITTED', 'RANKED', 5, 57, 71, 'RRRC--', { appliedAt: 0.2 }],
  [3044, null, 'SUBMITTED', 'RANKED', 6, 50, 50, 'RR-R--', {}],
  [3017, null, 'SUBMITTED', 'MUST_HAVES_UNVERIFIED', null, 57, 86, 'CRR-RC', { unverifiedKnockouts: [1] }],
  [3027, null, 'SUBMITTED', 'MUST_HAVES_UNVERIFIED', null, 36, 50, 'C-R---', { unverifiedKnockouts: [1], flags: ['HIDDEN_TEXT'] }],
  [3040, null, 'SUBMITTED', 'UNSCORED', null, null, null, '------', { matchStatus: 'PENDING', appliedAt: 0.1 }],
  [3005, null, 'REJECTED', 'UNSCORED', null, null, null, '------', { matchStatus: 'ANALYSIS_UNAVAILABLE', analysisStatus: 'NEEDS_OCR' }],
]
const summary = ([id, name, status, group, rank, v, c, , extra]) => ({
  applicationId: id, displayName: name ?? `Candidate #A-${id}`, identityRevealed: !!name, applicationStatus: status,
  appliedAt: ago(extra.appliedAt != null ? extra.appliedAt * 24 : 24 * 4), statusChangedAt: null, analysisStatus: extra.analysisStatus ?? 'COMPLETED',
  matchStatus: extra.matchStatus ?? 'READY', verifiedCoverage: v, claimedCoverage: c, knockoutsVerified: group === 'RANKED' ? true : group === 'UNSCORED' ? null : false,
  unverifiedKnockouts: extra.unverifiedKnockouts ?? [], rankGroup: group, rank, flags: extra.flags ?? [], workSampleStatus: extra.workSampleStatus ?? null, interviewCount: extra.interviewCount ?? 0,
})
const summaries = people.map(summary)
const rankDetail = (id) => {
  const row = people.find((x) => x[0] === id) ?? people[1]
  const s = summary(row)
  const tiers = row[7]
  return {
    ...s,
    requirements: reqText.map((text, i) => {
      const t = tiers[i]
      const status = t === '-' ? 'NOT_OBSERVED' : t === 'C' ? 'UNCLEAR' : 'MATCHED'
      return {
        requirementId: i + 1, type: i === 2 ? 'EXPERIENCE' : i === 4 ? 'CERTIFICATION' : 'SKILL', description: text, mandatory: i < 3, knockout: i === 0, minimumYears: i === 2 ? 5 : null, alternatives: [],
        status, tier: t === '-' ? null : T[t], evidence: t === '-' ? null : t === 'C' ? `Candidate claim: ${text}` : `Resume: ${text} in a production payments team`,
        sourceSection: 'Experience', explanation: t === '-' ? `No evidence of ${text.toLowerCase()} in the resume or claims; this does not mean the candidate lacks it.` : t === 'C' ? 'Only the candidate’s own claim supports this.' : 'The resume states this directly.',
        verificationHint: t === 'C' || t === '-' ? 'Ask for a recent, concrete example, or assign a work sample.' : null, provenance: t === 'C' ? 'CANDIDATE_CLAIM' : 'RESUME',
      }
    }),
    matchRunId: 412, algorithmVersion: 'evidence-ladder-3', calculatedAt: ago(5), claims: [], candidate: null, coverLetter: null, resumeAvailable: true, resumeFilename: null,
    workSamples: [], interviews: [], history: [], allowedActions: s.applicationStatus === 'SUBMITTED' ? ['SHORTLIST', 'REJECT'] : ['REJECT'], revealAllowed: !s.identityRevealed,
  }
}
const ranking = [
  route('GET', '/hr/jobs/(\\d+)/applicants', ({ query }) => {
    const status = query.get('status')
    const byDate = query.get('sort') === 'appliedAt'
    let list = status ? summaries.filter((x) => x.applicationStatus === status) : summaries
    if (byDate) list = [...list].sort((x, y) => y.appliedAt.localeCompare(x.appliedAt))
    return page(list, { size: 25, total: status ? list.length : 38 })
  }),
  route('GET', '/hr/jobs/(\\d+)/applicants/(\\d+)', ({ match }) => rankDetail(Number(match[2]))),
]

const first = async (p) => {
  await p.evaluate(() => document.querySelector('tr[data-row]')?.focus())
  await sleep(200)
}

const inviteRoutes = [route('GET', '/auth/invitations/(.+)', { organizationName: 'Northwind Labs', email: 'new.recruiter@northwind.example', role: 'RECRUITER', expiresAt: ahead(24 * 6) })]
/** Fill the accept-invitation form and submit it. */
const acceptInvite = (extra) => async (p) => {
  await p.waitForSelector('input[autocomplete="given-name"]')
  await p.type('input[autocomplete="given-name"]', 'Neha')
  await p.type('input[autocomplete="family-name"]', 'Rao')
  await p.type('input[autocomplete="new-password"]', 'hiring desk 42')
  await p.click('form button[type="submit"]')
  await sleep(700)
  if (extra) await extra(p)
}

export const screens = [
  // ---------------------------------------------------------------- sign-in and account entry
  { name: 'login-error', path: '/login', anonymous: true, override: [route('POST', '/auth/login', fail(401, 'Bad credentials'))], action: signIn() },
  { name: 'login-mfa', path: '/login', anonymous: true, override: [route('POST', '/auth/login', mfaChallenge)], action: signIn(async (p) => { await p.keyboard.type('482'); await sleep(300) }) },
  { name: 'login-mfa-error', path: '/login', anonymous: true, override: [route('POST', '/auth/login', mfaChallenge), route('POST', '/auth/mfa/verify', fail(401, 'Invalid code'))], action: signIn(async (p) => { await p.keyboard.type('123456'); await sleep(900) }) },
  { name: 'login-recovery', path: '/login', anonymous: true, override: [route('POST', '/auth/login', mfaChallenge)], action: signIn(clickText('Use a recovery code')) },
  { name: 'login-enrol', path: '/login', anonymous: true, settle: 600, override: [route('POST', '/auth/login', enrolChallenge), route('POST', '/me/mfa/enroll', enrolment)], action: signIn(async () => sleep(900)) },
  { name: 'login-recovery-codes', path: '/login', anonymous: true, override: [route('POST', '/auth/login', enrolChallenge), route('POST', '/me/mfa/enroll', enrolment), route('POST', '/me/mfa/confirm', { recoveryCodes: codes, session: session({ id: 11, email: 'priya.raman@northwind.example', firstName: 'Priya', lastName: 'Raman', role: 'HR_MANAGER', organizationId: 1, organizationName: 'Northwind Labs', emailVerified: true }) })], action: signIn(async (p) => { await sleep(700); await p.click('.hra-otp-input'); await p.keyboard.type('654321'); await sleep(900) }) },
  { name: 'login-sso-error', path: '/login?ssoError=domain_not_allowed', anonymous: true },
  { name: 'signup', path: '/signup', anonymous: true, action: async (p) => { await p.type('input[autocomplete="new-password"]', 'hiring42'); await sleep(300) } },
  { name: 'accept-invite', path: '/accept-invite?token=inv-123', anonymous: true, override: [route('GET', '/auth/invitations/(.+)', { organizationName: 'Northwind Labs', email: 'new.recruiter@northwind.example', role: 'RECRUITER', expiresAt: ahead(24 * 6) })] },
  // Integration pass: MFA lockout is a 429 MFA_LOCKED with Retry-After; accepting an invitation can need MFA (R1-M4).
  { name: 'login-mfa-locked', path: '/login', anonymous: true, override: [route('POST', '/auth/login', mfaChallenge), route('POST', '/auth/mfa/verify', { ...fail(429, 'MFA_LOCKED: Too many failed attempts. Try again in 5 minutes.'), __headers: { 'Retry-After': '300' } })], action: signIn(async (p) => { await p.keyboard.type('123456'); await sleep(900) }) },
  { name: 'accept-invite-enrol', path: '/accept-invite?token=inv-123', anonymous: true, settle: 600, override: [...inviteRoutes, route('POST', '/auth/accept-invite', enrolChallenge), route('POST', '/me/mfa/enroll', enrolment)], action: acceptInvite(async () => sleep(900)) },
  { name: 'accept-invite-codes', path: '/accept-invite?token=inv-123', anonymous: true, override: [...inviteRoutes, route('POST', '/auth/accept-invite', enrolChallenge), route('POST', '/me/mfa/enroll', enrolment), route('POST', '/me/mfa/confirm', { recoveryCodes: codes, session: session({ id: 31, email: 'new.recruiter@northwind.example', firstName: 'Neha', lastName: 'Rao', role: 'RECRUITER', organizationId: 1, organizationName: 'Northwind Labs', emailVerified: true }) })], action: acceptInvite(async (p) => { await sleep(700); await p.click('.hra-otp-input'); await p.keyboard.type('654321'); await sleep(900) }) },
  { name: 'accept-invite-locked', path: '/accept-invite?token=inv-123', anonymous: true, override: [...inviteRoutes, route('POST', '/auth/accept-invite', enrolChallenge), route('POST', '/me/mfa/enroll', enrolment), route('POST', '/me/mfa/confirm', { ...fail(429, 'MFA_LOCKED: Too many failed attempts. Try again in 10 minutes.'), __headers: { 'Retry-After': '600' } })], action: acceptInvite(async (p) => { await sleep(700); await p.click('.hra-otp-input'); await p.keyboard.type('654321'); await sleep(900) }) },
  { name: 'accept-invite-invalid', path: '/accept-invite?token=used', anonymous: true, override: [route('GET', '/auth/invitations/(.+)', fail(404, 'Invitation not found'))] },
  { name: 'forgot-password', path: '/forgot-password', anonymous: true },
  { name: 'reset-password', path: '/reset-password?token=abc', anonymous: true },

  // ---------------------------------------------------------------- first run, status pages
  { name: 'welcome', path: '/welcome', role: 'NEW_ADMIN', settle: 900 },
  { name: 'not-found', path: '/nothing-here' },
  { name: 'forbidden', path: '/admin/users' },

  // ---------------------------------------------------------------- jobs list
  { name: 'jobs-recruiter', path: '/jobs', role: 'RECRUITER' },
  { name: 'jobs-empty', path: '/jobs', override: [route('GET', '/jobs', ({ query }) => page([], { size: Number(query.get('size') || 20), total: 0 }))] },
  { name: 'jobs-filtered-empty', path: '/jobs?q=zzzz' },
  { name: 'jobs-row-menu', path: '/jobs?status=PUBLISHED', action: async (p) => { await p.click('tbody tr[data-row] button[aria-haspopup="menu"]'); await sleep(500) } },
  { name: 'jobs-loading', path: '/jobs', waitUntil: 'domcontentloaded', settle: 1200, override: [route('GET', '/jobs', delayed(20000, page([], { size: 20 })))] },

  // ---------------------------------------------------------------- job page and ranking
  { name: 'job-ranking', path: '/jobs/101?tab=applicants', settle: 900, override: ranking },
  { name: 'job-ranking-comfortable', path: '/jobs/101?tab=applicants', settle: 900, override: ranking, action: async (p) => { await p.evaluate(() => document.querySelector('input[value="comfortable"]')?.click()); await sleep(500) } },
  { name: 'job-applicants-peek', path: '/jobs/101?tab=applicants&peek=3017', settle: 900, override: ranking },
  { name: 'job-applicants-compare', path: '/jobs/101?tab=applicants&compare=3001,3014,3017', settle: 900, override: ranking },
  { name: 'job-applicants-selected', path: '/jobs/101?tab=applicants', override: ranking, action: async (p) => { await first(p); await p.keyboard.press('x'); await p.keyboard.press('j'); await p.keyboard.press('x'); await sleep(400) } },
  { name: 'job-applicants-shortlist', path: '/jobs/101?tab=applicants', override: ranking, action: async (p) => { await first(p); await p.keyboard.press('j'); await sleep(150); await p.keyboard.press('s'); await sleep(600) } },
  { name: 'job-applicants-shortcuts', path: '/jobs/101?tab=applicants', override: ranking, action: press('?') },
  { name: 'job-applicants-reviewing', path: '/jobs/101?tab=applicants&status=SUBMITTED&sort=appliedAt', override: ranking },
  { name: 'job-applicants-error', path: '/jobs/101?tab=applicants', override: [route('GET', '/hr/jobs/(\\d+)/applicants', fail(503, 'The service is temporarily unavailable.'))] },
  { name: 'job-draft', path: '/jobs/106', override: [route('GET', '/jobs/106', { ...draftJob, requirements: reqs }), route('GET', '/hr/jobs/(\\d+)/applicants', page([], { size: 25 }))] },
  { name: 'job-menu', path: '/jobs/101', action: async (p) => { await p.click('.hra-header-actions button[aria-haspopup="menu"]'); await sleep(500) } },
  { name: 'job-work-samples', path: '/jobs/101?tab=work-samples', override: [route('GET', '/hr/jobs/(\\d+)/work-samples', samples), route('GET', '/hr/jobs/(\\d+)/work-sample-assignments', assignments)], action: clickText('Design an idempotent') },
  { name: 'job-work-sample-editor', path: '/jobs/101?tab=work-samples', override: [route('GET', '/hr/jobs/(\\d+)/work-samples', samples), route('GET', '/hr/jobs/(\\d+)/work-sample-assignments', assignments)], action: async (p) => { await p.click('.hra-ws-actions button[aria-label^="Edit"]'); await sleep(700) } },
  // R6: download (audited) and cancel (reason required) from the job's assignments table.
  { name: 'job-ws-assignment-menu', path: '/jobs/101?tab=work-samples', override: [route('GET', '/hr/jobs/(\\d+)/work-samples', samples), route('GET', '/hr/jobs/(\\d+)/work-sample-assignments', assignments)], action: async (p) => { await p.click('button[aria-label="Actions for application #A-3001"]'); await sleep(500) } },
  { name: 'job-ws-assignment-menu-recruiter', path: '/jobs/101?tab=work-samples', role: 'RECRUITER', override: [route('GET', '/hr/jobs/(\\d+)/work-samples', samples), route('GET', '/hr/jobs/(\\d+)/work-sample-assignments', assignments)], action: async (p) => { await p.click('button[aria-label="Actions for application #A-3001"]'); await sleep(500) } },
  { name: 'job-ws-cancel-dialog', path: '/jobs/101?tab=work-samples', override: [route('GET', '/hr/jobs/(\\d+)/work-samples', samples), route('GET', '/hr/jobs/(\\d+)/work-sample-assignments', assignments)], action: async (p) => { await p.click('button[aria-label="Actions for application #A-3001"]'); await sleep(400); await clickText('Cancel assignment…')(p); await sleep(400); await clickText('Cancel assignment')(p); await sleep(500) } },
  { name: 'job-fairness', path: '/jobs/101?tab=fairness', override: [route('GET', '/hr/jobs/(\\d+)/fairness', fairness)] },

  // ---------------------------------------------------------------- wizard
  { name: 'job-new', path: '/jobs/new' },
  { name: 'job-new-errors', path: '/jobs/new', action: clickText('Continue to requirements') },
  { name: 'job-new-requirements', path: '/jobs/106/edit?step=requirements', settle: 900, override: [route('GET', '/jobs/106', draftJob), route('POST', '/jobs/compile-requirements', compiled)] },
  { name: 'job-new-requirements-added', path: '/jobs/106/edit?step=requirements', settle: 900, override: [route('GET', '/jobs/106', draftJob), route('POST', '/jobs/compile-requirements', compiled), route('PUT', '/jobs/106/requirements', ({ body }) => ({ ...draftJob, requirements: (body?.requirements ?? []).map((r, i) => ({ ...r, id: 900 + i })) }))], action: async (p) => { await clickText('Use 6 suggestions')(p); await p.click('.hra-req-more'); await sleep(500) } },
  { name: 'job-new-review', path: '/jobs/106/edit?step=review', settle: 900, override: [route('GET', '/jobs/106', { ...draftJob, requirements: reqs })] },
  { name: 'job-form-requirements', path: '/jobs/101/edit?step=requirements' },
]
