import { useId, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { errorMessage } from '../../api/client'
import { resumesApi } from '../../api/endpoints'
import type { Resume } from '../../api/types'
import { useNotify } from '../../components/Notices'
import { safeFilename, saveBlob } from '../../lib/download'
import { formatDate, formatFileSize } from '../../lib/format'
import { ANALYSIS_STATUS_LABEL } from '../../lib/labels'
import { AnalysisPanel } from './AnalysisPanel'
import { useResumeAnalysis } from './useResumeAnalysis'

export function ResumeCard({ resume, defaultExpanded }: { resume: Resume; defaultExpanded: boolean }) {
  const notify = useNotify()
  const detailsId = useId()
  const [expanded, setExpanded] = useState(defaultExpanded)
  const analysis = useResumeAnalysis(resume.id)
  const status = analysis.query.data?.status

  const download = useMutation({
    mutationFn: () => resumesApi.download(resume.id),
    onSuccess: file => {
      const extension = resume.originalFilename.split('.').pop() ?? 'pdf'
      saveBlob(file.blob, safeFilename(resume.originalFilename, file.filename ?? `resume-${resume.id}.${extension}`))
    },
    onError: error => notify('error', `Download failed: ${errorMessage(error)}`),
  })

  const extension = resume.originalFilename.split('.').pop()?.toUpperCase() ?? 'FILE'

  return (
    <article className="resume-row" aria-labelledby={`${detailsId}-name`}>
      <div className="resume-summary">
        <div className="file-icon" aria-hidden="true">
          {extension.slice(0, 4)}
        </div>
        <div className="resume-info">
          <strong id={`${detailsId}-name`} className="break-anywhere">
            {resume.originalFilename}
          </strong>
          <small>
            {formatFileSize(resume.fileSize)} <span className="dot" aria-hidden="true">·</span> Uploaded {formatDate(resume.uploadedAt)}
          </small>
          <span className={`analysis-status analysis-${(status ?? 'unknown').toLowerCase()}`}>
            {status ? ANALYSIS_STATUS_LABEL[status] : analysis.query.isError ? 'Analysis unavailable' : 'Checking analysis…'}
          </span>
          {/* Announces status changes (e.g. queued → ready) even while the details are collapsed. */}
          <span className="visually-hidden" role="status">
            {status ? `${resume.originalFilename}: ${ANALYSIS_STATUS_LABEL[status]}` : ''}
          </span>
        </div>
        <div className="resume-actions">
          <button type="button" className="quiet-button" onClick={() => download.mutate()} disabled={download.isPending}>
            {download.isPending ? 'Downloading…' : '↓ Download'}
            <span className="visually-hidden"> {resume.originalFilename}</span>
          </button>
          <button
            type="button"
            className="quiet-button"
            aria-expanded={expanded}
            aria-controls={detailsId}
            onClick={() => setExpanded(current => !current)}
          >
            {expanded ? 'Hide details' : 'Review details'}
          </button>
        </div>
      </div>
      <div id={detailsId} hidden={!expanded} className="resume-details">
        {expanded && <AnalysisPanel resume={resume} analysis={analysis} />}
      </div>
    </article>
  )
}
