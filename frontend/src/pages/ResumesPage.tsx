import { useId, useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { resumesApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { Resume } from '../api/types'
import { EmptyState, ErrorBlock, FormError, LoadingBlock } from '../components/Feedback'
import { useNotify } from '../components/Notices'
import { PageHeader, Section } from '../components/PageHeader'
import { ResumeCard } from './resumes/ResumeCard'

const MAX_BYTES = 10 * 1024 * 1024
const ALLOWED_EXTENSIONS = ['pdf', 'doc', 'docx']
const ACCEPT =
  '.pdf,.doc,.docx,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document'

function validateFile(file: File | undefined): string | null {
  if (!file) return 'Choose a PDF, DOC or DOCX file.'
  const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
  if (!ALLOWED_EXTENSIONS.includes(extension)) return 'Only PDF, DOC and DOCX files can be uploaded.'
  if (file.size === 0) return 'This file is empty.'
  if (file.size > MAX_BYTES) return 'The file is larger than 10 MB.'
  return null
}

export function ResumesPage() {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const fileInputId = useId()
  const formRef = useRef<HTMLFormElement>(null)
  const [fileError, setFileError] = useState<string | null>(null)
  const resumes = useQuery({ queryKey: queryKeys.resumes, queryFn: resumesApi.list })

  const upload = useMutation({
    mutationFn: (file: File) => resumesApi.upload(file),
    onSuccess: resume => {
      formRef.current?.reset()
      notify('success', `${resume.originalFilename} uploaded. Analysis has started; earlier resumes stay attached to their applications.`)
      void queryClient.invalidateQueries({ queryKey: queryKeys.resumes })
    },
  })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const input = event.currentTarget.elements.namedItem('resume')
    const file = input instanceof HTMLInputElement ? input.files?.[0] : undefined
    const problem = validateFile(file)
    setFileError(problem)
    if (!problem && file) upload.mutate(file)
  }

  const sorted: Resume[] = resumes.data ? [...resumes.data].sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt)) : []

  return (
    <>
      <PageHeader
        eyebrow="CANDIDATE PORTAL"
        title="Your resumes"
        description="Upload a resume, see what was read from it and suggest corrections."
      />

      <form ref={formRef} className="upload-panel" onSubmit={submit} aria-labelledby={`${fileInputId}-title`}>
        <div className="upload-icon" aria-hidden="true">
          ↑
        </div>
        <div className="upload-body">
          <strong id={`${fileInputId}-title`}>Upload a resume</strong>
          <label htmlFor={fileInputId} className="muted">
            PDF, DOC or DOCX · up to 10 MB
          </label>
          <input
            id={fileInputId}
            name="resume"
            type="file"
            accept={ACCEPT}
            aria-describedby={fileError ? `${fileInputId}-error` : undefined}
            aria-invalid={fileError ? true : undefined}
            onChange={() => setFileError(null)}
          />
          {fileError && (
            <p id={`${fileInputId}-error`} className="field-error" role="alert">
              {fileError}
            </p>
          )}
        </div>
        <button type="submit" className="primary" disabled={upload.isPending}>
          {upload.isPending ? 'Uploading…' : 'Upload file'}
        </button>
      </form>
      <FormError error={upload.error} />

      <Section title="Uploaded resumes" id="resume-list">
        {resumes.isPending ? (
          <LoadingBlock label="Loading resumes…" />
        ) : resumes.isError ? (
          <ErrorBlock error={resumes.error} onRetry={() => void resumes.refetch()} />
        ) : sorted.length === 0 ? (
          <EmptyState icon="▤" title="No resumes yet">
            Upload a PDF, DOC or DOCX file before applying to a job.
          </EmptyState>
        ) : (
          <ul className="plain-list resume-list">
            {sorted.map((resume, index) => (
              <li key={resume.id}>
                <ResumeCard resume={resume} defaultExpanded={index === 0} />
              </li>
            ))}
          </ul>
        )}
      </Section>
    </>
  )
}
