/**
 * Client-side checks for uploads. The server repeats every check (extension must match the file's magic bytes), so
 * these only save a round trip and give a clear message.
 */
import { formatFileSize } from './format'

const MB = 1024 * 1024

export function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : ''
}

// ---------------------------------------------------------------------------------------------------------------------
// Verification documents (WP-VBE: pdf/docx/pptx, at most 15 MB)
// ---------------------------------------------------------------------------------------------------------------------

export const DOCUMENT_MAX_BYTES = 15 * MB
export const DOCUMENT_EXTENSIONS = ['pdf', 'docx', 'pptx'] as const
export const DOCUMENT_ACCEPT =
  '.pdf,.docx,.pptx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.presentationml.presentation'

export function validateDocumentFile(file: File | null | undefined): string | null {
  if (!file) return 'Choose a PDF, DOCX or PPTX file.'
  if (!(DOCUMENT_EXTENSIONS as readonly string[]).includes(extensionOf(file.name))) {
    return 'Only PDF, DOCX and PPTX files can be checked.'
  }
  if (file.size === 0) return 'This file is empty.'
  if (file.size > DOCUMENT_MAX_BYTES) return `The file is ${formatFileSize(file.size)}; the limit is 15 MB.`
  return null
}

// ---------------------------------------------------------------------------------------------------------------------
// Work-sample attachments (WP-PLAT: pdf, zip, docx, pptx, png, jpg/jpeg, at most 20 MB each, at most 3 files)
// ---------------------------------------------------------------------------------------------------------------------

export const WORK_SAMPLE_MAX_FILES = 3
export const WORK_SAMPLE_MAX_BYTES = 20 * MB
export const WORK_SAMPLE_EXTENSIONS = ['pdf', 'zip', 'docx', 'pptx', 'png', 'jpg', 'jpeg'] as const
export const WORK_SAMPLE_ACCEPT = WORK_SAMPLE_EXTENSIONS.map(extension => `.${extension}`).join(',')

/** Problems with one candidate file, or null. */
export function validateWorkSampleFile(file: File): string | null {
  if (!(WORK_SAMPLE_EXTENSIONS as readonly string[]).includes(extensionOf(file.name))) {
    return `${file.name}: only PDF, ZIP, DOCX, PPTX, PNG and JPG files can be attached.`
  }
  if (file.size === 0) return `${file.name} is empty.`
  if (file.size > WORK_SAMPLE_MAX_BYTES) return `${file.name} is ${formatFileSize(file.size)}; the limit is 20 MB per file.`
  return null
}

/**
 * Checks a set of files that would be attached together (already chosen + newly chosen).
 * Returns every problem found; an empty list means the set is acceptable.
 */
export function validateWorkSampleFiles(files: readonly File[]): string[] {
  const problems: string[] = []
  if (files.length > WORK_SAMPLE_MAX_FILES) problems.push(`You can attach at most ${WORK_SAMPLE_MAX_FILES} files.`)
  for (const file of files) {
    const problem = validateWorkSampleFile(file)
    if (problem) problems.push(problem)
  }
  return problems
}
