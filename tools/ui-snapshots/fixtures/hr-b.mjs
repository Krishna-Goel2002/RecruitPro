// HR Part B fixtures (applicant review, verification, interviews, inbox, account, administration).
// Loaded by snap.mjs next to fixtures/hr.mjs; these routes are tried first. Every value is fictional.
import { ago, ahead, delayed, fail, page, route, session } from '../lib/mock.mjs'

const org = { organizationId: 1, organizationName: 'Northwind Labs', emailVerified: true }
const users = {
  HR_MANAGER: { id: 11, email: 'priya.raman@northwind.example', firstName: 'Priya', lastName: 'Raman', role: 'HR_MANAGER', ...org },
  ADMIN: { id: 10, email: 'dev.kapoor@northwind.example', firstName: 'Dev', lastName: 'Kapoor', role: 'ADMIN', ...org },
  RECRUITER: { id: 12, email: 'kabir.shah@northwind.example', firstName: 'Kabir', lastName: 'Shah', role: 'RECRUITER', ...org },
  INTERVIEWER: { id: 14, email: 'sana.k@northwind.example', firstName: 'Sana', lastName: 'Khan', role: 'INTERVIEWER', ...org },
}

const R = (id, type, description, mandatory, extra = {}) => ({ id, type, description, mandatory, knockout: false, minimumYears: null, alternatives: [], proficiency: null, ...extra })
const reqs = [
  R(1, 'SKILL', 'Java 17 and Spring Boot', true, { knockout: true, minimumYears: 3 }),
  R(2, 'SKILL', 'PostgreSQL schema design and query tuning', true),
  R(3, 'EXPERIENCE', 'Backend engineering in production', true, { minimumYears: 5 }),
  R(4, 'SKILL', 'Kubernetes', false, { alternatives: ['Amazon ECS', 'Nomad'] }),
  R(5, 'CERTIFICATION', 'AWS Certified Developer – Associate', false),
  R(6, 'SKILL', 'Event streaming (Kafka or Pulsar)', false),
]
const ev = (r, status, tier, evidence, explanation, extra = {}) => ({
  requirementId: r.id, type: r.type, description: r.description, mandatory: r.mandatory, knockout: r.knockout, minimumYears: r.minimumYears,
  alternatives: r.alternatives, status, tier, evidence, sourceSection: evidence ? 'EXPERIENCE' : null, explanation, verificationHint: null, provenance: 'RESUME', ...extra,
})

const kit = [
  { id: 'q1', requirementId: 1, question: 'Tell me about a Spring Boot service you designed from scratch. What would you change now?', followUps: ['How did you handle configuration across environments?', 'What broke first under load?'], anchors: { 1: 'Vague; no ownership of the design', 2: 'Some detail, little reasoning', 3: 'Clear design with trade-offs', 4: 'Trade-offs plus lessons applied since' } },
  { id: 'q2', requirementId: 2, question: 'Walk me through the slowest query you fixed. How did you find it?', followUps: ['What did the plan look like before and after?'], anchors: { 1: 'Guesswork', 2: 'Used EXPLAIN without interpreting it', 3: 'Measured, changed one thing, re-measured', 4: 'Systematic, and prevented regressions' } },
  { id: 'q3', requirementId: 4, question: 'Describe a deployment you ran on Kubernetes end to end.', followUps: ['What went wrong, and how did you find out?'], anchors: { 1: 'Has not done it', 2: 'Followed a runbook', 3: 'Owned a rollout', 4: 'Designed the rollout and the recovery' } },
  { id: 'q4', requirementId: null, question: 'Tell me about a time you disagreed with a technical decision. What happened?', followUps: [], anchors: { 1: 'Avoided it or escalated badly', 2: 'Raised it, did not follow through', 3: 'Argued with evidence, accepted the outcome', 4: 'Changed the outcome or the process' } },
]

const meeraRequirements = [
  ev(reqs[0], 'MATCHED', 'INTERVIEW_VERIFIED', 'Built settlement services in Java 17 / Spring Boot 3 handling 4M transactions a day', 'Stated in experience; rated 4 of 4 in the structured interview.', { provenance: 'INTERVIEW' }),
  ev(reqs[1], 'MATCHED', 'DEMONSTRATED', 'Cut p95 query time 38% by partitioning ledger tables', 'Work sample criterion “Schema and indexing” scored 4 of 4.', { provenance: 'WORK_SAMPLE' }),
  ev(reqs[2], 'MATCHED', 'RESUME_STATED', 'Backend Engineer · Finlytic · Jun 2019 – present', '7 years stated; the minimum is 5.', { verificationHint: 'Ask a referee or check the employment dates in the interview.' }),
  ev(reqs[3], 'MATCHED', 'CORROBORATED', 'Ran blue-green deploys on EKS for the ledger service', 'Claim checked by a reviewer against a shared GitHub result.', { provenance: 'CORROBORATED' }),
  ev(reqs[4], 'MATCHED', 'CORROBORATED', 'AWS Certified Developer – Associate (2025)', 'Badge link checked by a recruiter.', { provenance: 'CORROBORATED', sourceSection: 'CERTIFICATIONS' }),
  ev(reqs[5], 'NOT_OBSERVED', null, null, 'No mention in the resume or claims. This does not mean the candidate lacks it.', { verificationHint: 'Probe in the interview if it matters for the team.', provenance: null }),
]
const blindRequirements = [
  ev(reqs[0], 'UNCLEAR', 'SELF_CLAIMED', 'Candidate claim: Spring Boot 3 microservices (3 years)', 'Only claimed by the candidate; the resume mentions Java 11 and Micronaut. The ranking gate stays open until it is evidenced.', { provenance: 'CANDIDATE_CLAIM', verificationHint: 'Check the linked repository, or ask for a short Spring Boot work sample.' }),
  ev(reqs[1], 'MATCHED', 'RESUME_STATED', 'Designed the schema for a multi-tenant billing ledger (PostgreSQL 15)', 'Stated in experience.'),
  ev(reqs[2], 'MATCHED', 'RESUME_STATED', 'Software Engineer · Kestrel Pay · 2018 – present', '6 years stated; the minimum is 5.', { verificationHint: 'Confirm production ownership in the interview.' }),
  ev(reqs[3], 'UNCLEAR', 'SELF_CLAIMED', 'Deployed on Kubernates (EKS)', 'Misspelt on the resume; the candidate added a claim.', { provenance: 'CANDIDATE_CLAIM', verificationHint: 'Ask about a deployment they ran end to end.' }),
  ev(reqs[4], 'NOT_OBSERVED', null, null, 'No certification found in the resume or claims. This does not mean the candidate lacks it.', { provenance: null }),
  ev(reqs[5], 'MATCHED', 'RESUME_STATED', 'Kafka consumers for payment events, exactly-once with idempotent writes', 'Stated in experience.'),
]

const base = {
  identityRevealed: false, appliedAt: ago(52), statusChangedAt: null, analysisStatus: 'COMPLETED', matchStatus: 'READY',
  knockoutsVerified: true, unverifiedKnockouts: [], rankGroup: 'RANKED', rank: null, flags: [], workSampleStatus: null, interviewCount: 0,
  matchRunId: 418, algorithmVersion: 'evidence-ladder-3', calculatedAt: ago(3), resumeAvailable: true,
}

const scoredSample = { id: 801, workSampleId: 21, workSampleTitle: 'Design an idempotent refunds endpoint', applicationId: 3001, status: 'SCORED', assignedAt: ago(96), dueAt: ahead(20), startedAt: ago(70), submittedAt: ago(68.6), responseText: 'Use an Idempotency-Key header persisted with a hash of the request body. A unique index on (merchant_id, idempotency_key) makes retries safe; the second request returns the stored response.\n\nRefund rows are written in the same transaction as the ledger entry…', responseUrl: 'https://github.com/example/refunds-design', scores: [{ requirementId: 2, criterion: 'Schema and indexing', score: 4, note: 'Unique index on the idempotency key is exactly right.' }, { requirementId: 1, criterion: 'API design', score: 3, note: 'Clear error model; pagination unspecified.' }, { requirementId: null, criterion: 'Written reasoning', score: 4, note: null }], totalScore: 3.67, scoredAt: ago(60), scoredByName: 'Priya Raman', files: [{ id: 1, originalName: 'refunds-sequence.pdf', contentType: 'application/pdf', sizeBytes: 482113, sha256: 'ab', uploadedAt: ago(68.7) }] }
const submittedSample = { ...scoredSample, id: 802, applicationId: 3009, status: 'SUBMITTED', scores: [], totalScore: null, scoredAt: null, scoredByName: null }
const rubric = [
  { requirementId: 2, criterion: 'Schema and indexing', anchors: { 1: 'No persistence plan for keys', 2: 'Stores keys without a uniqueness guarantee', 3: 'Unique key with a sensible scope', 4: 'Scoped key, expiry and replay of the stored response' } },
  { requirementId: 1, criterion: 'API design', anchors: { 1: 'Endpoint unclear', 2: 'Happy path only', 3: 'Errors and retries specified', 4: 'Errors, retries, versioning and limits' } },
  { requirementId: null, criterion: 'Written reasoning', anchors: { 1: 'Hard to follow', 2: 'Some reasoning', 3: 'Clear trade-offs', 4: 'Clear, concise, anticipates questions' } },
]

const interviewDone = { id: 590, applicationId: 3001, jobId: 101, jobTitle: 'Senior Backend Engineer', candidateName: 'Meera Iyer', interviewerUserId: 14, interviewerName: 'Sana Khan', scheduledAt: ago(30), durationMinutes: 45, location: 'https://meet.example.com/nwl-be-3001', status: 'COMPLETED', scorecard: { ratings: [{ questionId: 'q1', requirementId: 1, rating: 4, notes: 'Owned the settlement service design; good on failure modes.' }, { questionId: 'q2', requirementId: 2, rating: 3, notes: null }, { questionId: 'q3', requirementId: 4, rating: 3, notes: 'Ran the EKS migration with one other engineer.' }, { questionId: 'q4', requirementId: null, rating: 4, notes: null }], recommendation: 'STRONG_YES', summary: 'Strong systems thinking and calm under questioning. Probe event streaming in the next round.', submittedAt: ago(28), revisionCount: 1 } }
const interviewNext = { id: 601, applicationId: 3001, jobId: 101, jobTitle: 'Senior Backend Engineer', candidateName: 'Meera Iyer', interviewerUserId: 13, interviewerName: 'Arjun Mehta', scheduledAt: ahead(26), durationMinutes: 60, location: 'Room 4B, Indiranagar office', status: 'SCHEDULED', scorecard: null }

const history = (name) => [
  { id: 5, applicationId: 0, previousStatus: 'SHORTLISTED', newStatus: 'INTERVIEWING', actionType: 'MOVE_TO_INTERVIEW', actorUserId: 11, actorName: 'Priya Raman', timestamp: ago(40), comment: null, matchRunId: 414 },
  { id: 4, applicationId: 0, previousStatus: 'SHORTLISTED', newStatus: 'SHORTLISTED', actionType: 'COMMENT_UPDATED', actorUserId: 12, actorName: 'Kabir Shah', timestamp: ago(70), comment: `Work sample is excellent. ${name} should meet Arjun next.`, matchRunId: 413 },
  { id: 2, applicationId: 0, previousStatus: 'SUBMITTED', newStatus: 'SHORTLISTED', actionType: 'SHORTLIST', actorUserId: 11, actorName: 'Priya Raman', timestamp: ago(100), comment: 'Strong verified coverage on both gates.', matchRunId: 410 },
]

function detail(id) {
  if (id === 3001 || id === 3009) {
    const meera = id === 3001
    return {
      ...base, applicationId: id, displayName: meera ? 'Meera Iyer' : 'Rohan Desai', identityRevealed: true,
      applicationStatus: meera ? 'INTERVIEWING' : 'SHORTLISTED', statusChangedAt: ago(40), verifiedCoverage: meera ? 86 : 64, claimedCoverage: meera ? 86 : 71,
      rank: meera ? 1 : 4, flags: meera ? ['TWO_COLUMN_LAYOUT'] : [], requirements: meera ? meeraRequirements : blindRequirements.map(r => (r.requirementId === 1 ? { ...r, status: 'MATCHED', tier: 'RESUME_STATED', provenance: 'RESUME' } : r)),
      claims: [
        { id: 55, claimType: 'SKILL', value: 'Kubernetes', evidenceUrl: 'https://github.com/example/ledger-deploy', hasEvidenceUrl: true, tier: 'CORROBORATED', verificationStatus: 'VERIFIED', verifiedAt: ago(90), verificationNote: 'Helm charts and rollout scripts in the shared repository.', createdAt: ago(110) },
        { id: 56, claimType: 'CERTIFICATION', value: 'AWS Certified Developer – Associate (2025)', evidenceUrl: 'https://www.credly.com/badges/example', hasEvidenceUrl: true, tier: 'CORROBORATED', verificationStatus: 'VERIFIED', verifiedAt: ago(88), verificationNote: 'Badge ID matches the issuer record.', createdAt: ago(108) },
      ],
      candidate: { fullName: meera ? 'Meera Iyer' : 'Rohan Desai', email: meera ? 'meera.iyer@example.com' : 'rohan.desai@example.com', city: 'Bengaluru', country: 'India', linkedinUrl: 'https://www.linkedin.com/in/example', portfolioUrl: 'https://meera.example.dev', yearsOfExperience: 7, currentCompany: 'Finlytic', currentDesignation: 'Senior Backend Engineer' },
      coverLetter: 'I have spent the last six years on payment systems, most recently leading the move to partitioned ledger tables at Finlytic, which cut our p95 settlement queries by more than a third.\n\nWhat draws me to Northwind is the chance to own the refunds platform end to end: the API, the data model and the on-call that comes with it. I enjoy the unglamorous parts — idempotency, reconciliation and making failures boring.\n\nI would love to talk about how your team ships safely.',
      resumeFilename: meera ? 'Meera_Iyer_Backend_2026.pdf' : 'Rohan_Desai_CV.pdf',
      workSamples: meera ? [scoredSample] : [submittedSample],
      interviews: meera ? [interviewDone, interviewNext] : [],
      history: history(meera ? 'Meera' : 'Rohan').slice(meera ? 0 : 2),
      allowedActions: meera ? ['SELECT', 'REJECT'] : ['MOVE_TO_INTERVIEW', 'UNDO_SHORTLIST', 'REJECT'],
      revealAllowed: false,
    }
  }
  // Blind, submitted, ranking gate open (Java 17 only claimed).
  return {
    ...base, applicationId: id, displayName: `Candidate #A-${id}`, applicationStatus: 'SUBMITTED', verifiedCoverage: 57, claimedCoverage: 86,
    knockoutsVerified: false, unverifiedKnockouts: [1], rankGroup: 'MUST_HAVES_UNVERIFIED', flags: ['TWO_COLUMN_LAYOUT', 'HIDDEN_TEXT'], requirements: blindRequirements,
    claims: [
      { id: 61, claimType: 'SKILL', value: 'Spring Boot 3', evidenceUrl: null, hasEvidenceUrl: true, tier: 'SELF_CLAIMED', verificationStatus: 'UNVERIFIED', verifiedAt: null, verificationNote: null, createdAt: ago(50) },
      { id: 62, claimType: 'SKILL', value: 'Kubernetes', evidenceUrl: null, hasEvidenceUrl: false, tier: 'SELF_CLAIMED', verificationStatus: 'UNVERIFIED', verifiedAt: null, verificationNote: null, createdAt: ago(49) },
      { id: 63, claimType: 'EXPERIENCE', value: 'On-call lead for payments (2 years)', evidenceUrl: null, hasEvidenceUrl: false, tier: 'SELF_CLAIMED', verificationStatus: 'REJECTED', verifiedAt: ago(20), verificationNote: 'Could not find any record; ask in the interview.', createdAt: ago(49) },
    ],
    candidate: null, coverLetter: null, resumeFilename: null, workSamples: [], interviews: [],
    history: [{ id: 9, applicationId: id, previousStatus: 'SUBMITTED', newStatus: 'SUBMITTED', actionType: 'COMMENT_UPDATED', actorUserId: 12, actorName: 'Kabir Shah', timestamp: ago(20), comment: 'Java 17 is only claimed. Worth a work sample before deciding.', matchRunId: 418 }],
    allowedActions: ['SHORTLIST', 'REJECT'], revealAllowed: true,
  }
}

const listOrder = [3001, 3014, 3022, 3009, 3031, 3017, 3027, 3040, 3005]
const summaries = listOrder.map((id, i) => ({ ...base, applicationId: id, displayName: `Candidate #A-${id}`, applicationStatus: 'SUBMITTED', verifiedCoverage: 80 - i * 6, claimedCoverage: 86 - i * 4, rank: i < 5 ? i + 1 : null, rankGroup: i < 5 ? 'RANKED' : i < 7 ? 'MUST_HAVES_UNVERIFIED' : 'UNSCORED' }))

const sv = (id, verdict, ownershipProven, sourceKind, extra = {}) => ({
  id, verifier: sourceKind === 'REFERENCE' ? 'reference-1.1' : sourceKind === 'OPEN_BADGE' ? 'badge-1.1' : 'code-2.3', verdict, ownershipProven, sourceKind,
  reasons: [{ text: 'Repository migrations create partitioned ledger tables.', evidenceKeys: ['e1'], quote: 'PARTITION BY RANGE (created_at)' }],
  integrityNotes: [], probeQuestion: null, candidateNote: null, myOrgReview: null, evidence: null, evidenceCount: 2, createdAt: ago(26), domainTrust: null, ...extra,
})
const evidenceItems = [
  { id: 1, key: 'e1', kind: 'REPOSITORY_FILE', url: 'https://github.com/meera-iyer/ledger-svc/blob/main/db/V12__partition.sql', title: 'ledger-svc · db/V12__partition.sql', retrievedAt: ago(26), contentHash: 'sha256:9f2c4e1ab07d5c3e88f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f7', excerpt: 'CREATE TABLE ledger_entries (...) PARTITION BY RANGE (created_at);', attributes: null },
  { id: 2, key: 'e2', kind: 'COMMIT', url: 'https://github.com/meera-iyer/ledger-svc/commit/4be1', title: 'Commit 4be1 · “Partition ledger by month”', retrievedAt: ago(26), contentHash: 'sha256:1b2c3d4e5f60718293a4b5c6d7e8f9011223344556677889900aabbccddeeff0', excerpt: null, attributes: null },
]
function verification(revealed) {
  const hide = (v) => (revealed ? v : { ...v, evidence: null, candidateNote: null, domainTrust: v.domainTrust ? { ...v.domainTrust, domain: null } : null })
  return {
    mode: 'VISIBLE', revealed,
    claims: [
      { subjectType: 'RESUME_FACT', subjectId: 901, claimType: 'SKILL', value: 'Java 17 and Spring Boot', tier: revealed ? 'CORROBORATED' : 'SELF_CLAIMED', verdicts: [hide(sv(7001, 'VERIFIED', true, 'GITHUB', { evidence: evidenceItems, myOrgReview: revealed ? { decision: 'CONFIRM', note: null, reviewerName: 'Kabir Shah', at: ago(21) } : null, probeQuestion: 'How did you choose the partition key, and what would make you change it?' }))] },
      { subjectType: 'CANDIDATE_CLAIM', subjectId: 61, claimType: 'SKILL', value: 'PostgreSQL query tuning', tier: 'RESUME_STATED', verdicts: [hide(sv(7002, 'PARTLY_VERIFIED', false, 'WEBSITE', { verifier: 'web-1.4', reasons: [{ text: 'The article describes query tuning but the site is not proven to belong to the candidate.', evidenceKeys: ['e1'], quote: 'We cut p95 by partitioning the ledger table…' }], candidateNote: 'Most tuning work was in a private repository at my employer.', evidence: [evidenceItems[0]] }))] },
      { subjectType: 'RESUME_FACT', subjectId: 904, claimType: 'EXPERIENCE', value: 'Backend Engineer, Finlytic (2019–2026)', tier: 'RESUME_STATED', verdicts: [hide(sv(7010, 'PARTLY_VERIFIED', true, 'REFERENCE', { reasons: [{ text: 'A former manager confirmed the role and the dates.', evidenceKeys: [], quote: null }], integrityNotes: [{ code: 'EMPLOYER_DOMAIN_NEW', text: 'The referee’s email domain was registered 40 days before the check.', evidenceKeys: [] }], domainTrust: { check: 'EMPLOYER_DOMAIN', domain: 'finlytic-team.example', status: 'WEAK', trustedBy: null, agentStatus: 'WEAK', reasons: ['The domain was registered 40 days before the check.', 'The home page does not name the employer.'], candidateControlled: false, liftedByOrganization: false }, evidence: [] })), hide(sv(7011, 'NOT_FOUND', true, 'GITHUB', { reasons: [{ text: 'No repository mentions Finlytic.', evidenceKeys: [], quote: null }], evidenceCount: 0, evidence: [] }))] },
      { subjectType: 'CANDIDATE_CLAIM', subjectId: 56, claimType: 'CERTIFICATION', value: 'AWS Certified Developer – Associate (2025)', tier: 'CORROBORATED', verdicts: [hide(sv(7020, 'VERIFIED', true, 'OPEN_BADGE', { reasons: [{ text: 'The badge signature is valid and the recipient matches the candidate.', evidenceKeys: ['e1'], quote: null }], domainTrust: { check: 'BADGE_ISSUER', domain: 'credly.com', status: 'TRUSTED', trustedBy: 'AGENT', agentStatus: 'TRUSTED', reasons: ['Listed as an established badge platform.'], candidateControlled: false, liftedByOrganization: false }, myOrgReview: { decision: 'CONFIRM', note: 'Checked the badge ID on the issuer page.', reviewerName: 'Priya Raman', at: ago(44) }, evidence: [{ ...evidenceItems[0], key: 'e1', kind: 'BADGE', url: 'https://www.credly.com/badges/example', title: 'AWS Certified Developer – Associate', excerpt: 'Issued 12 Mar 2025 · expires 12 Mar 2028' }] }))] },
    ],
  }
}

const notifications = [
  { id: 11, template: 'WORK_SAMPLE_SUBMITTED', subject: 'Work sample submitted: Rohan Desai', body: '“Design an idempotent refunds endpoint” is ready to score.', createdAt: ago(0.4), readAt: null, link: '/jobs/101/applicants/3009?tab=work-samples' },
  { id: 12, template: 'INTERVIEW_ASSIGNED', subject: 'You are interviewing Meera Iyer', body: 'Senior Backend Engineer · tomorrow · 60 minutes · Room 4B', createdAt: ago(3), readAt: null, link: '/interviews/601' },
  { id: 13, template: 'APPLICATION_RECEIVED', subject: 'New application: Senior Backend Engineer', body: 'Candidate #A-3040 applied. Matching is running.', createdAt: ago(7), readAt: null },
  { id: 14, template: 'INTERVIEW_CANCELLED', subject: 'Interview cancelled: Ishaan Verma', body: 'Product Designer, Growth · Thu 13 Nov, 15:00. Nothing for you to do.', createdAt: ago(30), readAt: ago(29) },
  { id: 15, template: 'APPLICATION_STATUS_CHANGED', subject: 'Selected: Ananya Bose', body: 'QA Automation Engineer · handed off to the offer process.', createdAt: ago(80), readAt: ago(70) },
]

const queue = [
  { verdictId: 7002, applicationId: 3017, revealed: false, subjectType: 'CANDIDATE_CLAIM', subjectId: 61, claimType: 'SKILL', value: 'PostgreSQL query tuning', sourceKind: 'WEBSITE', verifier: 'web-1.4', verdict: 'PARTLY_VERIFIED', ownershipProven: false, reasons: [{ text: 'The article describes query tuning but the site is not proven to belong to the candidate.', evidenceKeys: ['e1'], quote: 'We cut p95 by partitioning the ledger table…' }], integrityNotes: [], probeQuestion: 'Which query did you tune, and how did you measure it?', evidence: null, evidenceCount: 1, createdAt: ago(5), myLabel: null, labelCount: 0 },
  { verdictId: 7031, applicationId: 3022, revealed: false, subjectType: 'RESUME_FACT', subjectId: 931, claimType: 'SKILL', value: 'Kafka', sourceKind: 'GITHUB', verifier: 'code-2.3', verdict: 'VERIFIED', ownershipProven: true, reasons: [{ text: 'Three repositories configure Kafka consumers with manual offset commits.', evidenceKeys: ['e1', 'e2'], quote: 'enable.auto.commit=false' }], integrityNotes: [], probeQuestion: null, evidence: null, evidenceCount: 3, createdAt: ago(9), myLabel: null, labelCount: 1 },
  { verdictId: 7040, applicationId: 3031, revealed: false, subjectType: 'RESUME_FACT', subjectId: 940, claimType: 'EXPERIENCE', value: 'Platform Engineer, Corvid (2021–2024)', sourceKind: 'REFERENCE', verifier: 'reference-1.1', verdict: 'CONTRADICTED', ownershipProven: true, reasons: [{ text: 'The referee says the role ended in 2022, not 2024.', evidenceKeys: [], quote: null }], integrityNotes: [{ code: 'EMPLOYER_DOMAIN_UNPROVEN', text: 'Nothing independent shows that the employer domain belongs to the named employer.', evidenceKeys: [] }], probeQuestion: 'What did you work on in 2023?', evidence: null, evidenceCount: 1, createdAt: ago(12), myLabel: null, labelCount: 0 },
  { verdictId: 7044, applicationId: 3005, revealed: false, subjectType: 'RESUME_FACT', subjectId: 944, claimType: 'SKILL', value: 'Terraform', sourceKind: 'GITLAB', verifier: 'code-2.3', verdict: 'NOT_FOUND', ownershipProven: true, reasons: [{ text: 'No Terraform files in the connected projects.', evidenceKeys: [], quote: null }], integrityNotes: [], probeQuestion: null, evidence: null, evidenceCount: 0, createdAt: ago(20), myLabel: { verdictId: 7044, labelVerdict: 'NOT_FOUND', note: null, at: ago(2) }, labelCount: 2 },
  { verdictId: 7050, applicationId: 3001, revealed: true, subjectType: 'CANDIDATE_CLAIM', subjectId: 56, claimType: 'CERTIFICATION', value: 'AWS Certified Developer – Associate (2025)', sourceKind: 'OPEN_BADGE', verifier: 'badge-1.1', verdict: 'VERIFIED', ownershipProven: true, reasons: [{ text: 'The badge signature is valid and the recipient matches the candidate.', evidenceKeys: ['e1'], quote: null }], integrityNotes: [], probeQuestion: null, evidence: [{ ...evidenceItems[0], kind: 'BADGE', title: 'AWS Certified Developer – Associate', url: 'https://www.credly.com/badges/example' }], evidenceCount: 1, createdAt: ago(30), myLabel: { verdictId: 7050, labelVerdict: 'VERIFIED', note: null, at: ago(1) }, labelCount: 3 },
]
const metrics = (ready) => ({
  labelled: ready ? 162 : 118, agreement: ready ? 0.93 : 0.91, falseCorroborationRate: ready ? 0.012 : 0.017, kappa: ready ? 0.78 : 0.64, readyForVisible: ready,
  byVerifier: [
    { verifier: 'code-2.3', labelled: 64, agreement: 0.94, falseCorroborationRate: 0.01, kappa: 0.79 },
    { verifier: 'web-1.4', labelled: 31, agreement: 0.84, falseCorroborationRate: 0.035, kappa: 0.58 },
    { verifier: 'reference-1.1', labelled: 15, agreement: 0.93, falseCorroborationRate: 0, kappa: 0.81 },
    { verifier: 'badge-1.1', labelled: 8, agreement: 1, falseCorroborationRate: null, kappa: null },
  ],
  byVerdict: [
    { verdict: 'VERIFIED', predicted: 44, gold: 41, agreed: 40, precision: 0.909, recall: 0.976 },
    { verdict: 'PARTLY_VERIFIED', predicted: 30, gold: 33, agreed: 27, precision: 0.9, recall: 0.818 },
    { verdict: 'NOT_FOUND', predicted: 28, gold: 27, agreed: 26, precision: 0.929, recall: 0.963 },
    { verdict: 'CONTRADICTED', predicted: 6, gold: 7, agreed: 5, precision: 0.833, recall: 0.714 },
    { verdict: 'NOT_CHECKABLE', predicted: 10, gold: 10, agreed: 9, precision: 0.9, recall: 0.9 },
  ],
})
const settings = { mode: 'SHADOW', connectors: { GITHUB: true, GITLAB: true, WEBSITE: true, DOCUMENT: false, OPEN_BADGE: true, REFERENCE: true }, updatedAt: ago(72), updatedByName: 'Dev Kapoor', trustedEmployerDomains: ['northwind.example', 'finlytic.example'], trustedBadgeIssuers: ['badges.iisc.example'] }

const mfa = (over) => ({ enabled: false, enabledAt: null, pendingEnrollment: false, recoveryCodesRemaining: 0, requiredByOrganization: false, available: true, ...over })
const recovery = ['8f3k-2m9q', 'p4ze-7r1w', 'x2nb-5t8h', 'k9cd-3v6j', 'w7ma-1q4s', 'e5hy-8u2p', 'r3gt-6l9d', 'b1vx-4n7c', 'j6qp-9e3z', 'm8wr-2k5f']

const invitations = [
  { id: 31, email: 'neha.joshi@northwind.example', role: 'RECRUITER', status: 'PENDING', expiresAt: ahead(24 * 5), acceptedAt: null, revokedAt: null, createdAt: ago(48), invitedByName: 'Dev Kapoor' },
  { id: 30, email: 'arjun.mehta@northwind.example', role: 'INTERVIEWER', status: 'ACCEPTED', expiresAt: ago(24 * 2), acceptedAt: ago(24 * 6), revokedAt: null, createdAt: ago(24 * 9), invitedByName: 'Priya Raman' },
  { id: 28, email: 'contractor@agency.example', role: 'RECRUITER', status: 'REVOKED', expiresAt: ago(24 * 10), acceptedAt: null, revokedAt: ago(24 * 15), createdAt: ago(24 * 17), invitedByName: 'Dev Kapoor' },
  { id: 25, email: 'old.hire@northwind.example', role: 'HR_MANAGER', status: 'EXPIRED', expiresAt: ago(24 * 20), acceptedAt: null, revokedAt: null, createdAt: ago(24 * 27), invitedByName: 'Dev Kapoor' },
]
const adminJobs = [
  ['Senior Backend Engineer', 'PUBLISHED', 38, 'Engineering', 'Bengaluru (hybrid)'], ['Product Designer, Growth', 'PUBLISHED', 21, 'Design', 'Remote (India)'],
  ['Site Reliability Engineer', 'ON_HOLD', 9, 'Platform', 'Bengaluru'], ['Engineering Manager, Payments', 'DRAFT', 0, 'Engineering', 'Bengaluru'],
  ['QA Automation Engineer', 'CLOSED', 54, 'Engineering', 'Pune'], ['Customer Success Lead', 'PUBLISHED', 12, 'Customer', 'Mumbai'],
].map(([title, status, applicationCount, department, location], i) => ({ id: 101 + i, title, status, applicationCount, department, location, jobType: 'FULL_TIME', experienceLevel: 'SENIOR', vacancies: 2, createdByEmail: 'priya.raman@northwind.example', createdAt: ago(24 * (8 + i * 5)) }))
const audit = [
  ['APPLICATION_DECISION', 'priya.raman@northwind.example', 'APPLICATION', '3001', 'SHORTLISTED → INTERVIEWING · match run 414', 0.6],
  ['IDENTITY_REVEALED', 'priya.raman@northwind.example', 'APPLICATION', '3001', 'Revealed by shortlisting', 3],
  ['CLAIM_VERIFIED', 'kabir.shah@northwind.example', 'CANDIDATE_CLAIM', '55', 'verified=true · “Helm charts and rollout scripts in the shared repository.”', 5],
  ['WORK_SAMPLE_SCORED', 'priya.raman@northwind.example', 'WORK_SAMPLE_ASSIGNMENT', '801', 'total 3.67', 9],
  ['RESUME_DOWNLOADED_BY_STAFF', 'kabir.shah@northwind.example', 'APPLICATION', '3001', 'Meera_Iyer_Backend_2026.pdf', 12],
  ['USER_ROLE_CHANGED', 'dev.kapoor@northwind.example', 'USER', '15', 'RECRUITER → INTERVIEWER', 26],
  ['ORGANIZATION_UPDATED', 'dev.kapoor@northwind.example', 'ORGANIZATION', '1', 'requireStaffMfa: false → true', 50],
  ['LOGIN_LOCKED', null, 'USER', '18', '5 failed attempts', 70],
].map(([action, actorEmail, targetType, targetId, details, h], i) => ({ id: 900 - i, action, actorEmail, actorUserId: actorEmail ? 10 : null, targetType, targetId, details, ipAddress: actorEmail ? '10.20.4.17' : null, createdAt: ago(h) }))

export function routes({ role = 'HR_MANAGER' } = {}) {
  const user = users[role] ?? users.HR_MANAGER
  return [
    route('POST', '/auth/refresh', session(user)),
    route('GET', '/hr/jobs/(\\d+)/applicants', page(summaries, { size: 25, total: 9 })),
    route('GET', '/hr/jobs/(\\d+)/applicants/(\\d+)', ({ match }) => detail(Number(match[2]))),
    route('GET', '/hr/applications/(\\d+)/verification', ({ match }) => verification([3001, 3009].includes(Number(match[1])))),
    route('POST', '/hr/verdicts/(\\d+)/review', ({ match, body }) => ({ ...sv(Number(match[1]), 'PARTLY_VERIFIED', true, 'GITHUB'), myOrgReview: { decision: body?.decision ?? 'CONFIRM', note: body?.note ?? null, reviewerName: 'Priya Raman', at: new Date().toISOString() } })),
    route('GET', '/hr/jobs/(\\d+)/work-samples', [{ id: 21, jobId: 101, title: 'Design an idempotent refunds endpoint', instructions: 'Design a POST /refunds endpoint that is safe to retry…', timeLimitMinutes: 90, rubric, status: 'ACTIVE', createdAt: ago(24 * 5), updatedAt: ago(24 * 5) }, { id: 22, jobId: 101, title: 'Review a slow query', instructions: '…', timeLimitMinutes: 45, rubric: rubric.slice(0, 2), status: 'ACTIVE', createdAt: ago(24 * 5), updatedAt: ago(24 * 5) }]),
    route('GET', '/hr/jobs/(\\d+)/interview-kit', { jobId: 101, questions: kit, generatedBy: 'rules', updatedAt: ago(24 * 4) }),
    route('GET', '/hr/interviewers', [{ userId: 14, name: 'Sana Khan', email: users.INTERVIEWER.email, role: 'INTERVIEWER' }, { userId: 13, name: 'Arjun Mehta', email: 'arjun.mehta@northwind.example', role: 'INTERVIEWER' }, { userId: 11, name: 'Priya Raman', email: users.HR_MANAGER.email, role: 'HR_MANAGER' }]),
    route('GET', '/interviews/mine', [
      { id: 600, applicationId: 3009, jobId: 101, jobTitle: 'Senior Backend Engineer', candidateName: 'Rohan Desai', interviewerUserId: 14, interviewerName: 'Sana Khan', scheduledAt: ago(2), durationMinutes: 45, location: 'Room 4B, Indiranagar office', status: 'SCHEDULED', scorecard: null },
      { ...interviewNext, interviewerUserId: 14, interviewerName: 'Sana Khan', scheduledAt: ahead(3) },
      { id: 603, applicationId: 3022, jobId: 101, jobTitle: 'Senior Backend Engineer', candidateName: 'Candidate #A-3022', interviewerUserId: 14, interviewerName: 'Sana Khan', scheduledAt: ahead(28), durationMinutes: 30, location: 'https://meet.example.com/nwl-be-3022', status: 'SCHEDULED', scorecard: null },
      { id: 604, applicationId: 2990, jobId: 102, jobTitle: 'Product Designer, Growth', candidateName: 'Farah Ali', interviewerUserId: 14, interviewerName: 'Sana Khan', scheduledAt: ahead(24 * 4), durationMinutes: 60, location: 'Room 2A', status: 'SCHEDULED', scorecard: null },
      interviewDone,
      { id: 588, applicationId: 2950, jobId: 102, jobTitle: 'Product Designer, Growth', candidateName: 'Ishaan Verma', interviewerUserId: 14, interviewerName: 'Sana Khan', scheduledAt: ago(24 * 4), durationMinutes: 60, location: 'Room 2A', status: 'CANCELLED', scorecard: null },
    ]),
    route('GET', '/interviews/(\\d+)', ({ match }) => {
      const id = Number(match[1])
      const focus = blindRequirements.filter(r => r.tier === 'SELF_CLAIMED' || r.status !== 'MATCHED').slice(0, 3)
      if (id === 590) return { ...interviewDone, kit, focusRequirements: focus }
      return { id, applicationId: 3009, jobId: 101, jobTitle: 'Senior Backend Engineer', candidateName: 'Rohan Desai', interviewerUserId: 14, interviewerName: 'Sana Khan', scheduledAt: ago(2), durationMinutes: 45, location: 'https://meet.example.com/nwl-be-3009', status: 'SCHEDULED', scorecard: null, kit, focusRequirements: focus }
    }),
    route('GET', '/me/notifications/unread-count', { count: 3 }),
    route('GET', '/me/notifications', page(notifications, { size: 20 })),
    route('GET', '/me/mfa', mfa({ enabled: true, enabledAt: ago(24 * 40), recoveryCodesRemaining: 2, requiredByOrganization: true })),
    route('POST', '/me/mfa/enroll', { secret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/Northwind%20Labs:sana.k%40northwind.example?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=RecruitPro&algorithm=SHA1&digits=6&period=30' }),
    route('POST', '/me/mfa/confirm', { recoveryCodes: recovery }),
    route('GET', '/admin/verification/settings', settings),
    route('GET', '/admin/verification/metrics', metrics(false)),
    route('GET', '/admin/verification/shadow-queue', page(queue, { size: 10, total: 23 })),
    route('GET', '/admin/invitations', invitations),
    route('GET', '/admin/organization', { id: 1, name: 'Northwind Labs', slug: 'northwind-labs', blindReview: true, shareVerificationStatus: true, requireStaffMfa: true, ssoAllowedDomains: ['northwind.example'] }),
    route('GET', '/admin/jobs', page(adminJobs, { size: 20 })),
    route('GET', '/admin/audit-logs', page(audit, { size: 25, total: 212 })),
  ]
}

const click = (text, selector = 'button, a, [role="tab"], summary, [role="menuitem"]') => async (p) => {
  await p.evaluate((t, sel) => {
    const el = [...document.querySelectorAll(sel)].reverse().find((n) => n.textContent.trim().startsWith(t) && !n.closest('[inert]') && !n.closest('dialog:not([open])'))
    el?.click()
  }, text, selector)
  await new Promise((r) => setTimeout(r, 800))
}
const clickIn = (text, selector) => async (p) => {
  await p.evaluate((t, sel) => { [...document.querySelectorAll(sel)].find((n) => n.textContent.includes(t))?.click() }, text, selector)
  await new Promise((r) => setTimeout(r, 500))
}
const press = (key) => async (p) => { await p.keyboard.press(key); await new Promise((r) => setTimeout(r, 500)) }
const seq = (...steps) => async (p) => { for (const s of steps) await s(p) }

const A = '/jobs/101/applicants'
export const screens = [
  // Applicant review
  { name: 'b-applicant-blind', path: `${A}/3017`, settle: 900 },
  { name: 'b-applicant-revealed', path: `${A}/3001`, settle: 900 },
  { name: 'b-applicant-claims', path: `${A}/3017?tab=claims` },
  { name: 'b-applicant-claim-verify', path: `${A}/3017?tab=claims`, action: click('Verify claim') },
  { name: 'b-applicant-work-samples', path: `${A}/3001?tab=work-samples`, action: click('Submission') },
  { name: 'b-applicant-score-drawer', path: `${A}/3009?tab=work-samples`, action: click('Score with rubric') },
  { name: 'b-applicant-interviews', path: `${A}/3001?tab=interviews`, action: click('Scorecard') },
  { name: 'b-applicant-schedule', path: `${A}/3009?tab=interviews`, action: click('Schedule interview') },
  { name: 'b-applicant-verification', path: `${A}/3001?tab=verification`, settle: 900 },
  { name: 'b-applicant-verification-blind', path: `${A}/3017?tab=verification`, action: click('Backend Engineer, Finlytic') },
  { name: 'b-applicant-verification-shadow', path: `${A}/3001?tab=verification`, override: [route('GET', '/hr/applications/(\\d+)/verification', { mode: 'SHADOW', revealed: true, claims: [] })] },
  { name: 'b-applicant-verification-empty', path: `${A}/3001?tab=verification`, override: [route('GET', '/hr/applications/(\\d+)/verification', { mode: 'VISIBLE', revealed: true, claims: [] })] },
  { name: 'b-applicant-shortlist', path: `${A}/3017`, action: click('Shortlist', '.ap-bar button') },
  { name: 'b-applicant-reject', path: `${A}/3017`, action: click('Reject', '.ap-bar button') },
  { name: 'b-applicant-reveal', path: `${A}/3017`, action: click('Reveal identity', '.p-page-actions button') },
  { name: 'b-applicant-select', path: `${A}/3001`, action: click('Select', '.ap-bar button') },
  { name: 'b-applicant-shortcuts', path: `${A}/3001`, action: press('?') },
  { name: 'b-applicant-recruiter', path: `${A}/3017`, role: 'RECRUITER' },
  { name: 'b-applicant-loading', path: `${A}/3017`, waitUntil: 'domcontentloaded', settle: 1200, override: [route('GET', '/hr/jobs/(\\d+)/applicants/(\\d+)', delayed(20000, detail(3017)))] },
  { name: 'b-applicant-error', path: `${A}/3017`, override: [route('GET', '/hr/jobs/(\\d+)/applicants/(\\d+)', fail(503, 'The review service is not answering. Your data is safe; try again in a moment.'))] },
  { name: 'b-applicant-matching', path: `${A}/3017`, override: [route('GET', '/hr/jobs/(\\d+)/applicants/(\\d+)', { ...detail(3017), matchStatus: 'PENDING', verifiedCoverage: null, claimedCoverage: null, rankGroup: 'UNSCORED' })] },
  { name: 'b-applicant-1200', path: `${A}/3001`, settle: 900 },
  // Interviews
  { name: 'b-interviews', path: '/interviews', role: 'INTERVIEWER' },
  { name: 'b-interviews-empty', path: '/interviews', role: 'INTERVIEWER', override: [route('GET', '/interviews/mine', [])] },
  { name: 'b-interview-scorecard', path: '/interviews/600', role: 'INTERVIEWER' },
  { name: 'b-interview-scorecard-partial', path: '/interviews/600', role: 'INTERVIEWER', action: seq(clickIn('Clear design with trade-offs', '.sx-rating-option'), clickIn('Measured, changed one thing', '.sx-rating-option'), clickIn('Strong yes', '.ui-seg-item')) },
  { name: 'b-interview-submitted', path: '/interviews/590', role: 'INTERVIEWER' },
  { name: 'b-interview-manager', path: '/interviews/601' },
  { name: 'b-interview-locked', path: '/interviews/590', role: 'INTERVIEWER', override: [route('GET', '/interviews/590', () => ({ ...interviewDone, kit, focusRequirements: [], applicationStatus: 'REJECTED', scorecardEditable: false }))] },
  // Inbox and account
  { name: 'b-notifications', path: '/notifications' },
  { name: 'b-notifications-empty', path: '/notifications', override: [route('GET', '/me/notifications', page([], { size: 20 })), route('GET', '/me/notifications/unread-count', { count: 0 })] },
  { name: 'b-password', path: '/account/password' },
  { name: 'b-security-on', path: '/account/security' },
  { name: 'b-security-off', path: '/account/security', override: [route('GET', '/me/mfa', mfa({ requiredByOrganization: true }))] },
  { name: 'b-security-enrol', path: '/account/security', override: [route('GET', '/me/mfa', mfa({}))], action: click('Set up two-step verification') },
  { name: 'b-security-codes', path: '/account/security', override: [route('GET', '/me/mfa', mfa({}))], action: seq(click('Set up two-step verification'), async (p) => { await p.type('input[autocomplete="one-time-code"]', '123456'); await new Promise((r) => setTimeout(r, 900)) }) },
  // Integration pass: new recovery codes need a current authenticator code (R1-U1).
  { name: 'b-security-regen', path: '/account/security', action: click('Create new recovery codes') },
  { name: 'b-security-regen-wrong', path: '/account/security', override: [route('POST', '/me/mfa/recovery-codes', fail(400, 'The verification code is not valid'))], action: seq(click('Create new recovery codes'), async (p) => { await p.type('dialog[open] input[autocomplete="one-time-code"]', '123456'); await new Promise((r) => setTimeout(r, 900)) }) },
  // Administration
  { name: 'b-admin-overview', path: '/admin', role: 'ADMIN', settle: 900 },
  { name: 'b-admin-users', path: '/admin/users', role: 'ADMIN' },
  { name: 'b-admin-invite', path: '/admin/users', role: 'ADMIN', action: click('Invite staff') },
  { name: 'b-admin-invitations', path: '/admin/invitations', role: 'ADMIN' },
  { name: 'b-admin-organization', path: '/admin/organization', role: 'ADMIN' },
  { name: 'b-admin-organization-dirty', path: '/admin/organization', role: 'ADMIN', action: click('Blind review', 'label, .ui-switch') },
  { name: 'b-admin-jobs', path: '/admin/jobs', role: 'ADMIN' },
  { name: 'b-admin-audit', path: '/admin/audit', role: 'ADMIN' },
  { name: 'b-admin-audit-truncated', path: '/admin/audit', role: 'ADMIN', override: [route('GET', '/admin/audit-export', { __status: 200, __headers: { 'Content-Disposition': 'attachment; filename="audit-2026-11.csv"', 'X-Export-Truncated': 'true' }, __body: 'id,action' })], action: click('Export CSV') },
  { name: 'b-admin-verification', path: '/admin/verification', role: 'ADMIN', settle: 900 },
  { name: 'b-admin-verification-ready', path: '/admin/verification', role: 'ADMIN', override: [route('GET', '/admin/verification/metrics', metrics(true))] },
  { name: 'b-admin-verification-queue', path: '/admin/verification?tab=queue', role: 'ADMIN' },
  { name: 'b-admin-verification-connectors', path: '/admin/verification?tab=connectors', role: 'ADMIN' },
  { name: 'b-admin-verification-trusted', path: '/admin/verification?tab=trusted', role: 'ADMIN' },
  { name: 'b-admin-verification-switch', path: '/admin/verification', role: 'ADMIN', action: click('Switch to visible') },
  { name: 'b-admin-verification-manager', path: '/admin/verification?tab=queue' },
]
