import { useCallback, useEffect, useState } from 'react'

export type Role = 'ADMIN' | 'HR_MANAGER' | 'RECRUITER'
export type Session = { accessToken: string; refreshToken: string; role: Role; email: string; firstName: string; lastName: string }
export type RequirementMatch = {
  requirementId: number
  type: string
  description: string
  mandatory: boolean
  minimumYears: number | null
  status: 'MATCHED' | 'UNCLEAR' | 'NOT_OBSERVED'
  evidence?: string
  sourceSection?: string
  provenance?: 'RESUME' | 'CANDIDATE_REVIEW'
  explanation: string
}
export type Applicant = {
  applicationId: number
  applicationStatus: string
  appliedAt: string
  analysisStatus?: string
  observedEvidenceCoverage?: number
  scoreLabel?: string
  algorithmVersion?: string
  matchingErrorCode?: string
  requirements: RequirementMatch[]
}
export type DecisionEvent = {
  id: number
  previousStatus: string
  newStatus: string
  actionType: string
  actorUserId: number
  actorName: string
  timestamp: string
  comment?: string | null
}
export type Page<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

type Envelope<T> = { success: boolean; message?: string; data?: T; errors?: { field: string; message: string }[] }

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1').replace(/\/$/, '')

async function request<T>(session: Session, path: string, onExpired: () => void, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${session.accessToken}`, ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...init.headers },
  })
  let envelope: Envelope<T> | null = null
  try { envelope = await response.json() as Envelope<T> } catch { /* handled below */ }
  if (response.status === 401) {
    onExpired()
    throw new Error('Your session has expired. Sign in again to continue.')
  }
  if (!response.ok || envelope?.success !== true) {
    const details = envelope?.errors?.map(error => `${error.field}: ${error.message}`).join(' · ')
    const msg = envelope?.message || `Request failed (${response.status}).`
    throw new Error(details || msg)
  }
  return envelope.data as T
}

function formatStatus(status: string) {
  return status.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase())
}

function dateLabel(value?: string | null) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(date)
}

function getEvidenceTier(coverage?: number | null): { label: string; tierClass: string; scoreText: string } {
  if (coverage == null) {
    return { label: 'Evidence coverage unobserved', tierClass: 'tier-unobserved', scoreText: '—' }
  }
  const scoreText = `${coverage.toFixed(1)}%`
  if (coverage >= 75) {
    return { label: 'High evidence coverage', tierClass: 'tier-high', scoreText }
  }
  if (coverage >= 50) {
    return { label: 'Moderate evidence coverage', tierClass: 'tier-moderate', scoreText }
  }
  return { label: 'Low evidence coverage', tierClass: 'tier-low', scoreText }
}

interface ApplicantIntelligencePanelProps {
  jobId: number
  session: Session
  onSessionExpired: () => void
  canRetry: boolean
}

export function ApplicantIntelligencePanel({
  jobId,
  session,
  onSessionExpired,
  canRetry,
}: ApplicantIntelligencePanelProps) {
  const [pageData, setPageData] = useState<Page<Applicant> | null>(null)
  const [page, setPage] = useState(0)
  const [sortMode, setSortMode] = useState<'score' | 'applied'>('score')
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [retrying, setRetrying] = useState<number | null>(null)
  const [decisionBusy, setDecisionBusy] = useState<number | null>(null)
  const [commentDrafts, setCommentDrafts] = useState<Record<number, string>>({})
  const [histories, setHistories] = useState<Record<number, DecisionEvent[]>>({})
  const [historyLoading, setHistoryLoading] = useState<number | null>(null)
  const [expandedApplicantIds, setExpandedApplicantIds] = useState<Set<number>>(new Set())

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    const params = new URLSearchParams({ page: String(page), size: '20' })
    if (statusFilter !== 'ALL') params.set('status', statusFilter)
    try {
      setPageData(await request<Page<Applicant>>(session, `/hr/jobs/${jobId}/applicants?${params}`, onSessionExpired))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load applicant reviews.')
    } finally {
      setLoading(false)
    }
  }, [session, jobId, page, statusFilter, onSessionExpired])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    if (!pageData?.content.some(a => a.analysisStatus === 'PENDING' || a.analysisStatus === 'PROCESSING')) return
    const timer = window.setTimeout(() => void load(), 2500)
    return () => window.clearTimeout(timer)
  }, [pageData, load])

  function toggleApplicantDetails(applicationId: number) {
    setExpandedApplicantIds(prev => {
      const next = new Set(prev)
      if (next.has(applicationId)) next.delete(applicationId)
      else next.add(applicationId)
      return next
    })
  }

  function toggleAll(expand: boolean) {
    if (!pageData) return
    if (expand) {
      setExpandedApplicantIds(new Set(pageData.content.map(a => a.applicationId)))
    } else {
      setExpandedApplicantIds(new Set())
    }
  }

  async function retry(applicationId: number) {
    setRetrying(applicationId)
    setError('')
    try {
      await request<void>(session, `/hr/jobs/${jobId}/applicants/${applicationId}/analysis/retry`, onSessionExpired, {
        method: 'PATCH',
      })
      await load()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not retry analysis.')
    } finally {
      setRetrying(null)
    }
  }

  async function decide(applicant: Applicant, decision: 'shortlist' | 'reject') {
    if (decision === 'reject' && !window.confirm('Reject this application? This decision cannot be reversed.')) return
    setDecisionBusy(applicant.applicationId)
    setError('')
    setNotice('')
    try {
      await request<DecisionEvent>(
        session,
        `/hr/jobs/${jobId}/applicants/${applicant.applicationId}/${decision}`,
        onSessionExpired,
        {
          method: 'POST',
          body: JSON.stringify({ comment: commentDrafts[applicant.applicationId] ?? '' }),
        }
      )
      setNotice(`Application #${applicant.applicationId} ${decision === 'shortlist' ? 'shortlisted' : 'rejected'}.`)
      await load()
      await loadHistory(applicant.applicationId)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not record the application decision.')
    } finally {
      setDecisionBusy(null)
    }
  }

  async function loadHistory(applicationId: number) {
    setHistoryLoading(applicationId)
    setError('')
    try {
      const events = await request<DecisionEvent[]>(
        session,
        `/hr/jobs/${jobId}/applicants/${applicationId}/history`,
        onSessionExpired
      )
      setHistories(current => ({ ...current, [applicationId]: events }))
      const latestComment = [...events].reverse().find(event => event.comment != null)?.comment
      if (latestComment != null) setCommentDrafts(current => ({ ...current, [applicationId]: latestComment }))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load decision history.')
    } finally {
      setHistoryLoading(null)
    }
  }

  async function saveComment(applicant: Applicant) {
    setDecisionBusy(applicant.applicationId)
    setError('')
    setNotice('')
    try {
      await request<DecisionEvent>(
        session,
        `/hr/jobs/${jobId}/applicants/${applicant.applicationId}/review-comment`,
        onSessionExpired,
        {
          method: 'PUT',
          body: JSON.stringify({ comment: commentDrafts[applicant.applicationId] ?? '' }),
        }
      )
      setNotice(`Review comment for application #${applicant.applicationId} updated.`)
      await loadHistory(applicant.applicationId)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not update the review comment.')
    } finally {
      setDecisionBusy(null)
    }
  }

  const applicants = [...(pageData?.content ?? [])].sort((a, b) => {
    if (sortMode === 'score') {
      const scoreDiff = (b.observedEvidenceCoverage ?? -1) - (a.observedEvidenceCoverage ?? -1)
      if (scoreDiff !== 0) return scoreDiff
    }
    return new Date(b.appliedAt).getTime() - new Date(a.appliedAt).getTime()
  })

  return (
    <div className="applicant-review-panel">
      {/* Panel Top Heading & Toolbar */}
      <div className="applicant-panel-head">
        <div>
          <h3>Applicant Intelligence & Evidence Coverage</h3>
          <p className="evidence-disclaimer-note">
            Observed requirement evidence coverage — not a probability of success or a hiring recommendation.
          </p>
        </div>
        <div className="applicant-toolbar-controls">
          <label>
            Filter by status
            <select
              value={statusFilter}
              onChange={event => {
                setStatusFilter(event.target.value)
                setPage(0)
              }}
            >
              <option value="ALL">All statuses</option>
              <option value="SUBMITTED">Submitted</option>
              <option value="SHORTLISTED">Shortlisted</option>
              <option value="REJECTED">Rejected</option>
              <option value="WITHDRAWN">Withdrawn</option>
            </select>
          </label>
          <label>
            Sort by
            <select value={sortMode} onChange={event => setSortMode(event.target.value as 'score' | 'applied')}>
              <option value="score">Evidence coverage</option>
              <option value="applied">Application date</option>
            </select>
          </label>
          {applicants.length > 0 && (
            <div className="bulk-toggle-actions">
              <button
                type="button"
                className="outline-button compact"
                onClick={() => toggleAll(true)}
                title="Expand match details for all applicants"
              >
                Expand All
              </button>
              <button
                type="button"
                className="outline-button compact"
                onClick={() => toggleAll(false)}
                title="Collapse all match details"
              >
                Collapse All
              </button>
            </div>
          )}
        </div>
      </div>

      {error && (
        <div className="inline-notice applicant-error" role="alert">
          {error}
        </div>
      )}
      {notice && (
        <div className="inline-notice applicant-success" role="status">
          {notice}
        </div>
      )}

      {loading && !pageData ? (
        <div className="applicant-empty">Loading applicant intelligence…</div>
      ) : applicants.length === 0 ? (
        <div className="applicant-empty">
          {statusFilter === 'ALL'
            ? 'There are no applications for this job yet.'
            : `There are no ${formatStatus(statusFilter).toLowerCase()} applications for this job.`}
        </div>
      ) : (
        <div className="applicant-summary-list">
          {applicants.map((applicant, index) => {
            const isExpanded = expandedApplicantIds.has(applicant.applicationId)
            const tier = getEvidenceTier(applicant.observedEvidenceCoverage)
            const matchedCount = applicant.requirements.filter(r => r.status === 'MATCHED').length
            const unclearCount = applicant.requirements.filter(r => r.status === 'UNCLEAR').length
            const notObservedCount = applicant.requirements.filter(r => r.status === 'NOT_OBSERVED').length

            return (
              <article
                className={`applicant-summary-card ${isExpanded ? 'card-expanded' : ''}`}
                key={applicant.applicationId}
              >
                {/* Summary Row */}
                <div className="applicant-summary-row">
                  <div className="applicant-identity-col">
                    <span className="applicant-index-badge">
                      #{index + 1 + page * (pageData?.size ?? 20)}
                    </span>
                    <div>
                      <div className="applicant-primary-info">
                        <strong>Application #{applicant.applicationId}</strong>
                        <span className={`status-badge-small status-${applicant.applicationStatus.toLowerCase()}`}>
                          {formatStatus(applicant.applicationStatus)}
                        </span>
                      </div>
                      <p className="applicant-meta-text">
                        Applied {dateLabel(applicant.appliedAt)} · Analysis:{' '}
                        <strong>{formatStatus(applicant.analysisStatus ?? 'NOT_AVAILABLE')}</strong>
                      </p>
                    </div>
                  </div>

                  {/* Evidence Coverage Score Badge with 3 Visual Tiers */}
                  <div className="applicant-coverage-col">
                    <div className={`evidence-coverage-badge ${tier.tierClass}`}>
                      <span className="coverage-percentage">{tier.scoreText}</span>
                      <span className="coverage-tier-label">{tier.label}</span>
                    </div>
                    {applicant.requirements.length > 0 && (
                      <div className="requirement-tally">
                        <span className="tally-item matched" title="Matched requirements">
                          {matchedCount} Matched
                        </span>
                        <span className="tally-item unclear" title="Unclear requirements">
                          {unclearCount} Unclear
                        </span>
                        <span className="tally-item not-observed" title="Not observed requirements">
                          {notObservedCount} Not Observed
                        </span>
                      </div>
                    )}
                  </div>

                  {/* Expand / Collapse Button */}
                  <div className="applicant-toggle-col">
                    {canRetry && applicant.analysisStatus === 'FAILED' && (
                      <button
                        type="button"
                        className="outline-button retry-btn-sm"
                        disabled={retrying === applicant.applicationId}
                        onClick={() => void retry(applicant.applicationId)}
                      >
                        {retrying === applicant.applicationId ? 'Retrying…' : 'Retry analysis'}
                      </button>
                    )}
                    <button
                      type="button"
                      className={`details-toggle-btn ${isExpanded ? 'active' : ''}`}
                      onClick={() => toggleApplicantDetails(applicant.applicationId)}
                      aria-expanded={isExpanded}
                      aria-label={`${isExpanded ? 'Hide' : 'View'} match details for application #${applicant.applicationId}`}
                    >
                      {isExpanded ? 'Hide Match Details ▲' : 'View Match Details ▼'}
                    </button>
                  </div>
                </div>

                {/* Expandable Match Details Drawer */}
                {isExpanded && (
                  <div className="applicant-expanded-drawer">
                    {/* Mandatory Compliance Disclaimer Banner */}
                    <div className="ai-disclaimer-callout" role="note">
                      <span className="callout-icon">ℹ</span>
                      <div>
                        <strong>Advisory Evidence Intelligence Notice</strong>
                        <p>
                          Observed requirement evidence coverage — not a probability of success or a hiring
                          recommendation. AI results never trigger automatic status changes. All hiring decisions are made
                          independently by authorized HR professionals.
                        </p>
                      </div>
                    </div>

                    {applicant.matchingErrorCode && (
                      <div className="intelligence-warning-banner">
                        {applicant.matchingErrorCode === 'MATCHING_UNAVAILABLE'
                          ? 'Local matching service is unavailable. Refresh after the service is started.'
                          : applicant.matchingErrorCode === 'NO_STRUCTURED_REQUIREMENTS'
                          ? 'This job posting currently has no structured requirements, so evidence coverage cannot be computed.'
                          : 'Resume analysis is not yet available for this applicant.'}
                      </div>
                    )}

                    {applicant.analysisStatus === 'NEEDS_OCR' && (
                      <div className="intelligence-warning-banner ocr-warning">
                        This uploaded resume appears to be a scanned image document. OCR is not enabled in this environment,
                        so text qualifications could not be extracted automatically.
                      </div>
                    )}

                    {applicant.analysisStatus === 'UNSUPPORTED_FORMAT' && (
                      <div className="intelligence-warning-banner">
                        The uploaded resume format is retained for manual review but is not supported for automated text extraction.
                      </div>
                    )}

                    {/* Requirement-Level Results */}
                    {applicant.requirements.length > 0 && (
                      <div className="drawer-requirements-section">
                        <div className="requirements-section-title">
                          <h5>Requirement-Level Observed Evidence</h5>
                          <small>
                            Weights: Mandatory requirements carry 2× weight; Optional requirements carry 1× weight.
                          </small>
                        </div>

                        <div className="match-evidence-grid">
                          {applicant.requirements.map(item => {
                            const isCandidateVerified = item.provenance === 'CANDIDATE_REVIEW'

                            return (
                              <article className={`evidence-card status-border-${item.status.toLowerCase()}`} key={item.requirementId}>
                                <div className="evidence-card-header">
                                  <div className="evidence-status-group">
                                    <span className={`match-status-pill match-${item.status.toLowerCase()}`}>
                                      {formatStatus(item.status)}
                                    </span>
                                    <span className="requirement-type-label">
                                      {formatStatus(item.type)}
                                      {item.minimumYears != null ? ` (Min ${item.minimumYears} yrs)` : ''}
                                    </span>
                                  </div>
                                  <div className="evidence-weight-group">
                                    <span className={`weight-badge ${item.mandatory ? 'mandatory' : 'optional'}`}>
                                      {item.mandatory ? 'Mandatory (2×)' : 'Optional (1×)'}
                                    </span>
                                    {isCandidateVerified ? (
                                      <span className="provenance-chip candidate-verified" title="Verified by candidate review">
                                        ✓ Candidate Verified
                                      </span>
                                    ) : (
                                      <span className="provenance-chip resume-text" title="Extracted from raw resume text">
                                        📄 Resume Text
                                      </span>
                                    )}
                                  </div>
                                </div>

                                <p className="requirement-description-text">{item.description}</p>

                                {item.evidence && (
                                  <blockquote className="evidence-snippet" title="Extracted excerpt">
                                    {item.evidence}
                                  </blockquote>
                                )}

                                <div className="evidence-explanation-row">
                                  <span className="explanation-icon">💬</span>
                                  <p className="explanation-text">{item.explanation}</p>
                                </div>
                              </article>
                            )
                          })}
                        </div>
                      </div>
                    )}

                    {/* HR Decision & Review Controls */}
                    <div className="hr-decision-section">
                      <div className="decision-section-header">
                        <h5>HR Review & Decision</h5>
                        <p className="muted">
                          Record your review notes and execute hiring workflow actions.
                        </p>
                      </div>

                      <div className="decision-controls-body">
                        <label className="hr-comment-label">
                          HR internal review comment
                          <textarea
                            rows={2}
                            maxLength={2000}
                            value={commentDrafts[applicant.applicationId] ?? ''}
                            onChange={event =>
                              setCommentDrafts(current => ({
                                ...current,
                                [applicant.applicationId]: event.target.value,
                              }))
                            }
                            placeholder="Optional internal justification or notes for this applicant"
                          />
                        </label>

                        <div className="decision-action-buttons">
                          {applicant.applicationStatus === 'SUBMITTED' ? (
                            <>
                              <button
                                type="button"
                                className="primary-button workspace-primary shortlist-btn"
                                disabled={!canRetry || decisionBusy === applicant.applicationId}
                                onClick={() => void decide(applicant, 'shortlist')}
                              >
                                {decisionBusy === applicant.applicationId ? 'Saving…' : '✓ Shortlist Candidate'}
                              </button>
                              <button
                                type="button"
                                className="outline-button reject-button"
                                disabled={!canRetry || decisionBusy === applicant.applicationId}
                                onClick={() => void decide(applicant, 'reject')}
                              >
                                {decisionBusy === applicant.applicationId ? 'Saving…' : '✕ Reject Application'}
                              </button>
                            </>
                          ) : applicant.applicationStatus === 'SHORTLISTED' || applicant.applicationStatus === 'REJECTED' ? (
                            <button
                              type="button"
                              className="outline-button"
                              disabled={!canRetry || decisionBusy === applicant.applicationId}
                              onClick={() => void saveComment(applicant)}
                            >
                              {decisionBusy === applicant.applicationId ? 'Saving…' : 'Update Review Comment'}
                            </button>
                          ) : (
                            <span className="applicant-muted">No further HR decision available for withdrawn applications.</span>
                          )}

                          <button
                            type="button"
                            className="outline-button history-btn"
                            disabled={historyLoading === applicant.applicationId}
                            onClick={() => void loadHistory(applicant.applicationId)}
                          >
                            {historyLoading === applicant.applicationId ? 'Loading history…' : '📋 Decision History'}
                          </button>
                        </div>
                      </div>

                      {/* Decision History Audit Log */}
                      {histories[applicant.applicationId] && (
                        <div className="decision-history-card">
                          <h6>Decision Audit Trail</h6>
                          {histories[applicant.applicationId].length === 0 ? (
                            <p className="applicant-muted">No prior HR decisions recorded.</p>
                          ) : (
                            <div className="decision-history-events">
                              {histories[applicant.applicationId].map(event => (
                                <div className="decision-event-row" key={event.id}>
                                  <div className="event-primary">
                                    <strong>
                                      {event.actionType === 'COMMENT_UPDATED'
                                        ? 'Review comment updated'
                                        : `${formatStatus(event.previousStatus)} → ${formatStatus(event.newStatus)}`}
                                    </strong>
                                    <small>
                                      by {event.actorName} on {new Date(event.timestamp).toLocaleString()}
                                    </small>
                                  </div>
                                  {event.comment && <p className="event-comment">"{event.comment}"</p>}
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                )}
              </article>
            )
          })}
        </div>
      )}

      {/* Pagination */}
      {pageData && pageData.totalPages > 1 && (
        <div className="applicant-pagination">
          <span>
            {pageData.totalElements} applications · Page {pageData.page + 1} of {pageData.totalPages}
          </span>
          <div>
            <button
              type="button"
              className="outline-button"
              disabled={pageData.first || loading}
              onClick={() => setPage(value => Math.max(0, value - 1))}
            >
              Previous
            </button>
            <button
              type="button"
              className="outline-button"
              disabled={pageData.last || loading}
              onClick={() => setPage(value => value + 1)}
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
