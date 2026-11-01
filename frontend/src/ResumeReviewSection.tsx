import { useMemo, useState } from 'react'

export type ResumeFact = {
  id: number
  type: string
  value: string
  extractedValue: string
  evidence: string
  sourceSection: string
  sourceReference?: string
  reviewStatus: 'UNREVIEWED' | 'CONFIRMED' | 'CORRECTED'
  reviewedAt?: string
}

export type ResumeAnalysis = {
  resumeId: number
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'NEEDS_OCR' | 'UNSUPPORTED_FORMAT'
  parserVersion?: string
  errorCode?: string
  attemptCount: number
  analyzedAt?: string
  facts: ResumeFact[]
}

export type Resume = {
  id: number
  originalFilename: string
  contentType: string
  fileSize: number
  uploadedAt: string
}

interface ResumeReviewSectionProps {
  resume: Resume
  analysis: ResumeAnalysis | null
  onRetry: (resumeId: number) => Promise<void>
  onReviewFact: (resumeId: number, fact: ResumeFact, newValue: string) => Promise<void>
  busy?: boolean
}

type FactCategory = 'ALL' | 'SKILL' | 'EXPERIENCE' | 'EDUCATION' | 'CERTIFICATION'

const MAX_FACT_LENGTH = 1000

export function ResumeReviewSection({
  resume,
  analysis,
  onRetry,
  onReviewFact,
  busy = false,
}: ResumeReviewSectionProps) {
  const [activeCategory, setActiveCategory] = useState<FactCategory>('ALL')
  const [editingFactId, setEditingFactId] = useState<number | null>(null)
  const [draftValue, setDraftValue] = useState('')
  const [savingFactId, setSavingFactId] = useState<number | null>(null)
  const [validationError, setValidationError] = useState<string | null>(null)

  const facts = analysis?.facts ?? []

  const categorizedFacts = useMemo(() => {
    const skills = facts.filter(f => f.type.toUpperCase() === 'SKILL')
    const experience = facts.filter(f => f.type.toUpperCase() === 'EXPERIENCE')
    const education = facts.filter(f => f.type.toUpperCase() === 'EDUCATION')
    const certifications = facts.filter(f => f.type.toUpperCase() === 'CERTIFICATION')
    const other = facts.filter(
      f => !['SKILL', 'EXPERIENCE', 'EDUCATION', 'CERTIFICATION'].includes(f.type.toUpperCase())
    )
    return { skills, experience, education, certifications, other }
  }, [facts])

  const visibleFacts = useMemo(() => {
    if (activeCategory === 'ALL') return facts
    if (activeCategory === 'SKILL') return categorizedFacts.skills
    if (activeCategory === 'EXPERIENCE') return categorizedFacts.experience
    if (activeCategory === 'EDUCATION') return categorizedFacts.education
    if (activeCategory === 'CERTIFICATION') return categorizedFacts.certifications
    return facts
  }, [activeCategory, facts, categorizedFacts])

  function handleStartEdit(fact: ResumeFact) {
    setEditingFactId(fact.id)
    setDraftValue(fact.value || fact.extractedValue || '')
    setValidationError(null)
  }

  function handleCancelEdit() {
    setEditingFactId(null)
    setDraftValue('')
    setValidationError(null)
  }

  async function handleSaveFact(fact: ResumeFact) {
    const trimmed = draftValue.trim()
    if (!trimmed) {
      setValidationError('Resume information cannot be blank.')
      return
    }
    if (trimmed.length > MAX_FACT_LENGTH) {
      setValidationError(`Value cannot exceed ${MAX_FACT_LENGTH} characters.`)
      return
    }

    setSavingFactId(fact.id)
    setValidationError(null)
    try {
      await onReviewFact(resume.id, fact, trimmed)
      setEditingFactId(null)
      setDraftValue('')
    } catch {
      // Error is caught and surfaced by the parent
    } finally {
      setSavingFactId(null)
    }
  }

  if (!analysis) {
    return (
      <div className="resume-analysis-container">
        <p className="muted">Analysis is not yet available for this resume.</p>
      </div>
    )
  }

  return (
    <div className="resume-analysis-container">
      {/* State Banners */}
      <AnalysisStateBanner
        status={analysis.status}
        errorCode={analysis.errorCode}
        onRetry={() => void onRetry(resume.id)}
        busy={busy}
      />

      {/* Facts Review Section (available for COMPLETED and PARTIAL) */}
      {(analysis.status === 'COMPLETED' || analysis.status === 'PARTIAL') && (
        <div className="resume-facts-wrapper">
          <div className="resume-facts-header">
            <div>
              <h4>Extracted Qualifications & Facts</h4>
              <p className="muted">
                Review your extracted qualifications. Verified facts are used directly by the
                matching engine when evaluating your applications against job requirements.
              </p>
            </div>
            <div className="verified-summary-pill">
              {facts.filter(f => f.reviewStatus !== 'UNREVIEWED').length} of {facts.length} verified
            </div>
          </div>

          {/* Category Tabs */}
          {facts.length > 0 && (
            <div className="fact-category-tabs" role="tablist" aria-label="Fact categories">
              <button
                type="button"
                role="tab"
                aria-selected={activeCategory === 'ALL'}
                className={`category-tab ${activeCategory === 'ALL' ? 'active' : ''}`}
                onClick={() => setActiveCategory('ALL')}
              >
                All Facts <span className="tab-count">{facts.length}</span>
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={activeCategory === 'SKILL'}
                className={`category-tab ${activeCategory === 'SKILL' ? 'active' : ''}`}
                onClick={() => setActiveCategory('SKILL')}
              >
                Skills <span className="tab-count">{categorizedFacts.skills.length}</span>
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={activeCategory === 'EXPERIENCE'}
                className={`category-tab ${activeCategory === 'EXPERIENCE' ? 'active' : ''}`}
                onClick={() => setActiveCategory('EXPERIENCE')}
              >
                Experience <span className="tab-count">{categorizedFacts.experience.length}</span>
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={activeCategory === 'EDUCATION'}
                className={`category-tab ${activeCategory === 'EDUCATION' ? 'active' : ''}`}
                onClick={() => setActiveCategory('EDUCATION')}
              >
                Education <span className="tab-count">{categorizedFacts.education.length}</span>
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={activeCategory === 'CERTIFICATION'}
                className={`category-tab ${activeCategory === 'CERTIFICATION' ? 'active' : ''}`}
                onClick={() => setActiveCategory('CERTIFICATION')}
              >
                Certifications{' '}
                <span className="tab-count">{categorizedFacts.certifications.length}</span>
              </button>
            </div>
          )}

          {/* Facts List */}
          {visibleFacts.length === 0 ? (
            <div className="facts-empty-state">
              <p className="muted">No {activeCategory.toLowerCase()} facts detected in this resume.</p>
            </div>
          ) : (
            <div className="fact-cards-grid">
              {visibleFacts.map(fact => {
                const isEditing = editingFactId === fact.id
                const isSaving = savingFactId === fact.id
                const isVerified = fact.reviewStatus === 'CORRECTED' || fact.reviewStatus === 'CONFIRMED'
                const isCorrected = fact.reviewStatus === 'CORRECTED' && fact.value !== fact.extractedValue

                return (
                  <article className={`fact-card ${isVerified ? 'fact-verified' : ''}`} key={fact.id}>
                    <div className="fact-card-head">
                      <div className="fact-type-wrap">
                        <span className="fact-type-badge">{formatFactType(fact.type)}</span>
                        {fact.sourceSection && (
                          <span className="fact-section-tag">Section: {fact.sourceSection}</span>
                        )}
                      </div>
                      <div className="fact-provenance-wrap">
                        {isVerified ? (
                          <span className="provenance-pill verified" title="Verified by you">
                            ✓ Candidate Verified
                          </span>
                        ) : (
                          <span className="provenance-pill unreviewed" title="Extracted from resume text">
                            Needs Review
                          </span>
                        )}
                      </div>
                    </div>

                    {isEditing ? (
                      <div className="fact-edit-panel">
                        <label htmlFor={`fact-input-${fact.id}`} className="fact-edit-label">
                          Edit {formatFactType(fact.type).toLowerCase()} value:
                        </label>
                        <textarea
                          id={`fact-input-${fact.id}`}
                          className="fact-textarea"
                          rows={2}
                          maxLength={MAX_FACT_LENGTH}
                          value={draftValue}
                          onChange={e => {
                            setDraftValue(e.target.value)
                            if (validationError) setValidationError(null)
                          }}
                          aria-invalid={Boolean(validationError)}
                          disabled={isSaving}
                        />
                        <div className="fact-edit-meta">
                          <span className="char-count">
                            {draftValue.length} / {MAX_FACT_LENGTH}
                          </span>
                          {validationError && (
                            <span className="fact-validation-error">{validationError}</span>
                          )}
                        </div>
                        <div className="fact-edit-actions">
                          <button
                            type="button"
                            className="primary small"
                            onClick={() => void handleSaveFact(fact)}
                            disabled={isSaving || !draftValue.trim()}
                          >
                            {isSaving ? 'Saving…' : 'Save'}
                          </button>
                          <button
                            type="button"
                            className="quiet-button small"
                            onClick={handleCancelEdit}
                            disabled={isSaving}
                          >
                            Cancel
                          </button>
                        </div>
                      </div>
                    ) : (
                      <div className="fact-display-panel">
                        <div className="fact-current-value">
                          <strong>{fact.value}</strong>
                        </div>

                        {/* Show original value diff if corrected */}
                        {isCorrected && (
                          <div className="fact-original-diff">
                            <span className="diff-label">Original Extracted:</span>
                            <span className="diff-text">{fact.extractedValue}</span>
                          </div>
                        )}

                        {fact.evidence && (
                          <blockquote className="fact-evidence-quote" title="Original text excerpt from resume">
                            {fact.evidence}
                          </blockquote>
                        )}

                        <div className="fact-card-actions">
                          <button
                            type="button"
                            className="text-button compact-button"
                            onClick={() => handleStartEdit(fact)}
                            disabled={busy}
                          >
                            ✏ Edit
                          </button>
                          {fact.reviewStatus === 'UNREVIEWED' && (
                            <button
                              type="button"
                              className="text-button compact-button confirm-button"
                              onClick={() => {
                                setDraftValue(fact.extractedValue || fact.value)
                                void onReviewFact(resume.id, fact, fact.extractedValue || fact.value)
                              }}
                              disabled={busy}
                            >
                              ✓ Confirm as is
                            </button>
                          )}
                        </div>
                      </div>
                    )}
                  </article>
                )
              })}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function AnalysisStateBanner({
  status,
  errorCode,
  onRetry,
  busy,
}: {
  status: ResumeAnalysis['status']
  errorCode?: string
  onRetry: () => void
  busy: boolean
}) {
  switch (status) {
    case 'PENDING':
      return (
        <div className="state-banner state-pending" role="status">
          <div className="state-banner-icon">⏳</div>
          <div>
            <strong>Queued for Analysis</strong>
            <p className="muted">
              Your resume is queued for local analysis. This page updates automatically once extraction completes.
            </p>
          </div>
        </div>
      )
    case 'PROCESSING':
      return (
        <div className="state-banner state-processing" role="status">
          <div className="state-banner-icon"><span className="spinner-green" /></div>
          <div>
            <strong>Analyzing Resume Text</strong>
            <p className="muted">
              Extracting structured skills, experience, education, and credentials...
            </p>
          </div>
        </div>
      )
    case 'COMPLETED':
      return (
        <div className="state-banner state-completed" role="status">
          <div className="state-banner-icon">✓</div>
          <div>
            <strong>Structured Analysis Ready</strong>
            <p className="muted">
              Review and verify your qualifications below. Verified facts are used directly for requirement matching.
            </p>
          </div>
        </div>
      )
    case 'PARTIAL':
      return (
        <div className="state-banner state-partial" role="status">
          <div className="state-banner-icon">ℹ</div>
          <div>
            <strong>Partial Analysis Ready</strong>
            <p className="muted">
              Some information was extracted. Any unobserved qualifications can be verified or supplemented below.
            </p>
          </div>
        </div>
      )
    case 'NEEDS_OCR':
      return (
        <div className="state-banner state-ocr" role="alert">
          <div className="state-banner-icon">📄</div>
          <div>
            <strong>Scanned Document Detected (OCR Required)</strong>
            <p className="guidance-text">
              This document appears to be a scanned image without selectable text. Because automated OCR
              is not enabled in this environment, text qualifications could not be extracted automatically.
            </p>
            <p className="guidance-suggestion">
              💡 <strong>Action Recommended:</strong> Upload a digital PDF (exported directly from Word, Google Docs, or LaTeX)
              or a Word document (.docx). Your current resume file remains safely stored and attached to your applications for human review.
            </p>
          </div>
        </div>
      )
    case 'UNSUPPORTED_FORMAT':
      return (
        <div className="state-banner state-unsupported" role="alert">
          <div className="state-banner-icon">⚠</div>
          <div>
            <strong>Unsupported Format for Automated Extraction</strong>
            <p className="guidance-text">
              Automated text extraction is supported for modern <strong>PDF</strong> and <strong>DOCX</strong> files.
              Files in other formats cannot be automatically parsed.
            </p>
            <p className="guidance-suggestion">
              💡 <strong>Note:</strong> Your file remains safely attached to your applications and is fully downloadable by HR recruiters.
              To benefit from automated requirement matching, consider re-uploading your resume in PDF or DOCX format.
            </p>
          </div>
        </div>
      )
    case 'FAILED':
      return (
        <div className="state-banner state-failed" role="alert">
          <div className="state-banner-icon">✕</div>
          <div>
            <strong>Analysis Failed</strong>
            <p className="guidance-text">
              {errorCode === 'AI_SERVICE_UNAVAILABLE' || errorCode === 'AI_SERVICE_AUTHENTICATION_NOT_CONFIGURED'
                ? 'The local analysis service is temporarily unavailable. Please retry once the service is running.'
                : 'An unexpected error occurred while parsing the resume text.'}
            </p>
            <button
              type="button"
              className="primary small retry-btn"
              onClick={onRetry}
              disabled={busy}
            >
              ↻ Retry Analysis
            </button>
          </div>
        </div>
      )
    default:
      return null
  }
}

function formatFactType(type: string): string {
  switch (type.toUpperCase()) {
    case 'SKILL': return 'Skill'
    case 'EXPERIENCE': return 'Experience'
    case 'EDUCATION': return 'Education'
    case 'CERTIFICATION': return 'Certification'
    default: return type.charAt(0).toUpperCase() + type.slice(1).toLowerCase()
  }
}
