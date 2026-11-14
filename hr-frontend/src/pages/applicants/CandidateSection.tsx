import { useMutation } from '@tanstack/react-query'
import { errorMessage } from '../../api/client'
import { reviewApi } from '../../api/review'
import type { ApplicantDetail } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { DownloadIcon, EyeIcon } from '../../components/Icons'
import { useNotices } from '../../components/Notices'
import { safeHttpUrl } from '../../lib/format'

/** Identity, resume and cover letter: hidden until reveal (blind-first review, D4). */
export function CandidateSection({ jobId, detail, canReveal, onReveal }: {
  jobId: number
  detail: ApplicantDetail
  canReveal: boolean
  onReveal: () => void
}) {
  const { can } = useAuth()
  const notices = useNotices()
  const download = useMutation({
    mutationFn: () => reviewApi.downloadResume(jobId, detail.applicationId, detail.resumeFilename ?? `application-${detail.applicationId}-resume`),
    onSuccess: filename => notices.success(`Downloaded ${filename}. The download is recorded in the audit log.`),
    onError: cause => notices.error(errorMessage(cause, 'The resume could not be downloaded.')),
  })

  if (!detail.identityRevealed || !detail.candidate) {
    return (
      <section className="detail-card section-card blind-card" aria-labelledby="candidate-heading">
        <h2 id="candidate-heading">Candidate identity</h2>
        <p>
          <strong>Blind review.</strong> Name, contact details, resume file and cover letter stay hidden so the first review rests on evidence alone.
          {detail.applicationStatus === 'SUBMITTED'
            ? ' Shortlisting reveals them automatically; the reveal is recorded in the audit log.'
            : ' They can be revealed now; the reveal is recorded in the audit log.'}
        </p>
        {canReveal && <button type="button" className="outline-button" onClick={onReveal}><EyeIcon /> Reveal identity</button>}
      </section>
    )
  }

  const candidate = detail.candidate
  const linkedin = safeHttpUrl(candidate.linkedinUrl)
  const portfolio = safeHttpUrl(candidate.portfolioUrl)
  const place = [candidate.city, candidate.country].filter(Boolean).join(', ')

  return (
    <section className="detail-card section-card" aria-labelledby="candidate-heading">
      <h2 id="candidate-heading">Candidate</h2>
      <dl className="fact-list">
        <div><dt>Name</dt><dd>{candidate.fullName}</dd></div>
        <div><dt>Email</dt><dd><a href={`mailto:${candidate.email}`}>{candidate.email}</a></dd></div>
        {place && <div><dt>Location</dt><dd>{place}</dd></div>}
        {candidate.currentDesignation && <div><dt>Current role</dt><dd>{candidate.currentDesignation}{candidate.currentCompany ? ` at ${candidate.currentCompany}` : ''}</dd></div>}
        {!candidate.currentDesignation && candidate.currentCompany && <div><dt>Current company</dt><dd>{candidate.currentCompany}</dd></div>}
        {candidate.yearsOfExperience != null && <div><dt>Experience (self-reported)</dt><dd>{candidate.yearsOfExperience} years</dd></div>}
        {linkedin && <div><dt>LinkedIn</dt><dd><a href={linkedin} target="_blank" rel="noopener noreferrer nofollow">Profile ↗</a></dd></div>}
        {portfolio && <div><dt>Portfolio</dt><dd><a href={portfolio} target="_blank" rel="noopener noreferrer nofollow">Portfolio ↗</a></dd></div>}
      </dl>
      {detail.resumeAvailable && can('applicants.downloadResume') && (
        <button type="button" className="outline-button" onClick={() => download.mutate()} disabled={download.isPending}>
          <DownloadIcon /> {download.isPending ? 'Downloading…' : `Download resume${detail.resumeFilename ? ` (${detail.resumeFilename})` : ''}`}
        </button>
      )}
      <h3 className="subheading">Cover letter</h3>
      {detail.coverLetter ? <p className="description-text cover-letter">{detail.coverLetter}</p> : <p className="muted-copy">No cover letter.</p>}
    </section>
  )
}
