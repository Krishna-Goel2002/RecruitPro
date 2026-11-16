import type {
  ApplicantDetail, ApplicantSummary, JobStaff, Page, Requirement, StaffVerdict, StaffVerification, WorkSampleAssignment,
} from '../api/types'

export const NOW = '2026-11-16T09:00:00Z'

export function requirement(id: number, over: Partial<Requirement> = {}): Requirement {
  return { id, type: 'SKILL', description: `Requirement ${id}`, mandatory: true, knockout: false, minimumYears: null, alternatives: [], proficiency: null, ...over } as Requirement
}

export function job(over: Partial<JobStaff> = {}): JobStaff {
  return {
    id: 101, title: 'Senior Backend Engineer', description: 'Build services.', department: 'Engineering', location: 'Pune', jobType: 'FULL_TIME',
    experienceLevel: 'SENIOR', vacancies: 1, status: 'PUBLISHED', requirementsVersion: 1, createdAt: NOW, updatedAt: NOW,
    requirements: [requirement(1, { description: 'Java 17' }), requirement(2, { description: 'PostgreSQL', mandatory: false })],
    applicationCount: 3,
    ...over,
  } as JobStaff
}

export function summary(id: number, over: Partial<ApplicantSummary> = {}): ApplicantSummary {
  return {
    applicationId: id, displayName: `Candidate #A-${id}`, identityRevealed: false, applicationStatus: 'SUBMITTED', appliedAt: NOW,
    statusChangedAt: null, analysisStatus: 'DONE', matchStatus: 'READY', verifiedCoverage: 60, claimedCoverage: 80, knockoutsVerified: true,
    unverifiedKnockouts: [], rankGroup: 'RANKED', rank: id - 3000, flags: [], workSampleStatus: null, interviewCount: 0,
    ...over,
  }
}

export function detail(id: number, over: Partial<ApplicantDetail> = {}): ApplicantDetail {
  return {
    ...summary(id),
    requirements: [], matchRunId: 1, algorithmVersion: 'v2', calculatedAt: NOW, claims: [], candidate: null, coverLetter: null,
    resumeAvailable: true, resumeFilename: null, workSamples: [], interviews: [], history: [],
    allowedActions: ['SHORTLIST', 'REJECT'],
    ...over,
  } as ApplicantDetail
}

export function page<T>(content: T[], size = 25): Page<T> {
  return { content, page: 0, size, totalElements: content.length, totalPages: 1 } as unknown as Page<T>
}

export function assignment(id: number, over: Partial<WorkSampleAssignment> = {}): WorkSampleAssignment {
  return {
    id, workSampleId: 11, workSampleTitle: 'Payments API task', applicationId: 3001, status: 'SUBMITTED', assignedAt: NOW, dueAt: null,
    startedAt: NOW, submittedAt: NOW, responseText: 'My answer', responseUrl: null, scores: [], totalScore: null, scoredAt: null,
    scoredByName: null, cancelledAt: null, cancelReason: null, cancelledByName: null,
    files: [{ id: 501, originalName: 'solution.zip', contentType: 'application/zip', sizeBytes: 20480, sha256: 'abc', uploadedAt: NOW }],
    ...over,
  } as WorkSampleAssignment
}

export function verdict(id: number, over: Partial<StaffVerdict> = {}): StaffVerdict {
  return {
    id, verifier: 'code-verifier', verdict: 'VERIFIED', ownershipProven: true, reasons: [{ text: 'Commits match the claim.', evidenceKeys: ['E1'], quote: null }],
    integrityNotes: [], probeQuestion: 'Walk me through the payment retries.', candidateNote: null, myOrgReview: null,
    evidence: null, evidenceCount: 2, sourceKind: 'GITHUB', createdAt: NOW, domainTrust: null,
    ...over,
  } as StaffVerdict
}

export function verification(over: Partial<StaffVerification> = {}): StaffVerification {
  return {
    mode: 'VISIBLE', revealed: false,
    claims: [{ subjectType: 'CANDIDATE_CLAIM', subjectId: 1, claimType: 'SKILL', value: 'Kafka', tier: 'SELF_CLAIMED', verdicts: [verdict(900)] }],
    ...over,
  }
}
