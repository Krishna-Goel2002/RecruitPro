import { useEffect } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { errorMessage } from '../api/client'
import { resumesApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { Resume, ResumeAnalysisStatus } from '../api/types'
import { ErrorState } from '../components/Feedback'
import { useNotify } from '../components/Notices'
import { Badge, EmptyState, FileDropzone, IconButton, LoadingRegion, PageHeader, Skeleton, Spinner, buttonProps, type Tone } from '../design/components'
import { Download, FileText, Icon, ScrollText } from '../design/icons'
import { formatFileSize, relativeTime } from '../design/lib/format'
import { safeFilename, saveBlob } from '../lib/download'
import { ANALYSIS_STATUS_LABEL } from '../lib/labels'
import { newestFirst, resumesQuery } from '../lib/queries'
import { RESUME_REVIEWED_KEY, writeStored } from '../lib/storage'
import { AnalysisPanel } from './resumes/AnalysisPanel'
import { useResumeAnalysis } from './resumes/useResumeAnalysis'
import './common.css'
import './resumes/resumes.css'

const MAX_BYTES = 10 * 1024 * 1024
const ACCEPT = '.pdf,.doc,.docx'

export const ANALYSIS_TONE: Record<ResumeAnalysisStatus, Tone> = {
  PENDING: 'neutral',
  PROCESSING: 'info',
  COMPLETED: 'success',
  PARTIAL: 'warning',
  FAILED: 'danger',
  NEEDS_OCR: 'warning',
  UNSUPPORTED_FORMAT: 'warning',
}

/**
 * Resume (REDESIGN_PLAN A7): drop or choose a file and it uploads at once; files on the left, the selected file's
 * analysis on the right ("What we read", with corrections inline). The selection lives in ?resume=.
 */
export function ResumesPage() {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const [params, setParams] = useSearchParams()
  const resumes = useQuery(resumesQuery)
  const sorted = resumes.data ? newestFirst(resumes.data) : []
  const selectedId = Number(params.get('resume')) || sorted[0]?.id || null
  const selected = sorted.find(r => r.id === selectedId) ?? sorted[0] ?? null

  const upload = useMutation({
    mutationFn: (file: File) => resumesApi.upload(file),
    onSuccess: resume => {
      queryClient.setQueryData<Resume[]>(queryKeys.resumes, current => [resume, ...(current ?? []).filter(r => r.id !== resume.id)])
      void queryClient.invalidateQueries({ queryKey: queryKeys.resumes })
      setParams({ resume: String(resume.id) }, { replace: true })
      notify('success', `${resume.originalFilename} uploaded`, { body: 'We are reading it now. Earlier resumes stay attached to their applications.' })
    },
  })

  return (
    <>
      <PageHeader title="Resume" description="Upload your resume, check what we read from it, and correct anything that is off. Corrections become your claims." />

      <FileDropzone
        className="r-drop"
        compact={sorted.length > 0}
        prompt={sorted.length > 0 ? 'Upload a newer version: drop it here, or' : 'Drop your resume here, or'}
        hint="PDF, DOC or DOCX, up to 10 MB. It uploads straight away."
        accept={ACCEPT}
        maxSize={MAX_BYTES}
        disabled={upload.isPending}
        onFiles={files => upload.mutate(files[0])}
        files={upload.isPending && upload.variables ? [{ id: 'uploading', name: upload.variables.name, size: upload.variables.size, progress: 0.6 }] : []}
        error={upload.error ? errorMessage(upload.error) : undefined}
      />

      {resumes.isPending ? (
        <LoadingRegion label="Loading your resumes" className="r-layout">
          <div>
            {[0, 1].map(i => (
              <div key={i} className="c-skel-row">
                <Skeleton width={200} height={12} />
                <Skeleton width={140} height={10} />
              </div>
            ))}
          </div>
          <div>
            <Skeleton width={220} height={18} />
            <Skeleton width="100%" height={48} style={{ marginTop: 16, borderRadius: 6 }} />
          </div>
        </LoadingRegion>
      ) : resumes.isError ? (
        <ErrorState error={resumes.error} onRetry={() => void resumes.refetch()} title="Your resumes could not be loaded" />
      ) : sorted.length === 0 ? (
        <EmptyState
          icon={ScrollText}
          title="No resume yet"
          description="Employers need one to review you. Once it is uploaded we show you exactly what we read, so you can correct it before anyone else sees it."
        />
      ) : (
        <div className="r-layout">
          <nav aria-label="Your resumes" className="r-files">
            <p className="r-files-title">
              Your files <span className="c-meta numeric">· {sorted.length}</span>
            </p>
            <ul className="r-file-list" role="list">
              {sorted.map((resume, i) => (
                <FileRow key={resume.id} resume={resume} newest={i === 0} selected={resume.id === selected?.id} onSelect={() => setParams({ resume: String(resume.id) }, { replace: true })} />
              ))}
            </ul>
            <p className="c-meta r-files-note">Applications keep the file they were sent with. New applications use the file you choose.</p>
          </nav>
          {selected && <SelectedAnalysis resume={selected} key={selected.id} />}
        </div>
      )}
    </>
  )
}

function FileRow({ resume, newest, selected, onSelect }: { resume: Resume; newest: boolean; selected: boolean; onSelect: () => void }) {
  const analysis = useResumeAnalysis(resume.id)
  const status = analysis.query.data?.status
  return (
    <li>
      <button type="button" className="r-file" aria-current={selected ? 'true' : undefined} onClick={onSelect}>
        <Icon icon={FileText} size={16} className="r-file-icon" />
        <span className="r-file-text">
          <span className="r-file-name">{resume.originalFilename}</span>
          <span className="c-meta">
            {formatFileSize(resume.fileSize)} · {newest ? 'newest, ' : ''}uploaded {relativeTime(resume.uploadedAt)}
          </span>
        </span>
        <span className="r-file-status">
          {status ? (
            <Badge tone={ANALYSIS_TONE[status]} variant="plain" dot>
              {status === 'PROCESSING' || status === 'PENDING' ? <Spinner size={12} /> : null}
              {ANALYSIS_STATUS_LABEL[status]}
            </Badge>
          ) : analysis.query.isError ? (
            <span className="c-meta">Status unavailable</span>
          ) : null}
        </span>
        {/* Announces status changes (queued → ready) even when another file is selected. */}
        <span className="sr-only" role="status">
          {status ? `${resume.originalFilename}: ${ANALYSIS_STATUS_LABEL[status]}` : ''}
        </span>
      </button>
    </li>
  )
}

function SelectedAnalysis({ resume }: { resume: Resume }) {
  const notify = useNotify()
  const analysis = useResumeAnalysis(resume.id)
  const download = useMutation({
    mutationFn: () => resumesApi.download(resume.id),
    onSuccess: file => {
      const extension = resume.originalFilename.split('.').pop() ?? 'pdf'
      saveBlob(file.blob, safeFilename(resume.originalFilename, file.filename ?? `resume-${resume.id}.${extension}`))
    },
    onError: error => notify('error', `Download failed: ${errorMessage(error)}`),
  })
  const done = analysis.query.data?.status === 'COMPLETED' || analysis.query.data?.status === 'PARTIAL'

  // The first-run checklist ticks "Review what we read" once the candidate has seen a finished analysis.
  useEffect(() => {
    if (done) writeStored(RESUME_REVIEWED_KEY, '1')
  }, [done])

  return (
    <section className="r-analysis" aria-labelledby="r-analysis-title">
      <header className="r-analysis-head">
        <div className="r-analysis-heading">
          <h2 id="r-analysis-title" className="t-h2 c-break">
            What we read
          </h2>
          <p className="c-meta c-break">{resume.originalFilename}</p>
        </div>
        <IconButton label={`Download ${resume.originalFilename}`} icon={Download} variant="secondary" loading={download.isPending} onClick={() => download.mutate()} />
      </header>
      <AnalysisPanel resume={resume} analysis={analysis} />
      {done && (
        <div className="r-next">
          <p className="t-strong">Looks right?</p>
          <p className="c-muted">Check these against places that prove them, like GitHub or your website. Results stay private until you share them.</p>
          <Link {...buttonProps('secondary')} to="/verification?add=source&step=choose">
            Strengthen with a source
          </Link>
        </div>
      )}
    </section>
  )
}
