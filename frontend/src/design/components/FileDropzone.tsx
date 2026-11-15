import { useId, useRef, useState, type DragEvent, type ReactNode } from 'react'
import { CircleAlert, FileText, Icon, Upload, X } from '../icons'
import { formatFileSize } from '../lib/format'
import { cx } from './cx'

export interface DropzoneFile {
  id: string | number
  name: string
  size: number
  /** 0–1 while uploading; omit when done. */
  progress?: number
  error?: string
}

export interface FileDropzoneProps {
  /** Accessible name / visible lead, e.g. "Drop a PDF, DOC or DOCX here, or". */
  prompt?: ReactNode
  /** Limits line, e.g. "Up to 5 MB". */
  hint?: ReactNode
  /** <input accept>, e.g. ".pdf,.doc,.docx". Also checked client-side. */
  accept?: string
  /** Bytes. Checked client-side before onFiles. */
  maxSize?: number
  multiple?: boolean
  maxFiles?: number
  disabled?: boolean
  compact?: boolean
  /** Valid files only; rejected files produce the error line instead. */
  onFiles: (files: File[]) => void
  files?: DropzoneFile[]
  onRemove?: (id: DropzoneFile['id']) => void
  /** Server or form error. */
  error?: ReactNode
  className?: string
}

function matchesAccept(file: File, accept?: string) {
  if (!accept) return true
  const name = file.name.toLowerCase()
  return accept.split(',').map(s => s.trim().toLowerCase()).some(a =>
    a.startsWith('.') ? name.endsWith(a) : a.endsWith('/*') ? file.type.startsWith(a.slice(0, -1)) : file.type === a,
  )
}

/**
 * File dropzone (§9.26): dashed 1 px strong border, radius 6, 96 px (64 compact). Drag-over turns the border accent
 * on the selected background. The real <input type=file> stays in the tab order (visually hidden), so keyboard and
 * screen-reader users get the native picker. Chosen files list below with progress and remove.
 */
export function FileDropzone({
  prompt = 'Drop a file here, or', hint, accept, maxSize, multiple, maxFiles, disabled, compact, onFiles, files, onRemove, error, className,
}: FileDropzoneProps) {
  const id = useId()
  const input = useRef<HTMLInputElement>(null)
  const [over, setOver] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)

  const take = (list: FileList | null) => {
    if (!list || disabled) return
    let chosen = Array.from(list)
    if (!multiple) chosen = chosen.slice(0, 1)
    if (maxFiles) chosen = chosen.slice(0, maxFiles)
    const bad = chosen.find(f => !matchesAccept(f, accept))
    const big = chosen.find(f => maxSize && f.size > maxSize)
    if (bad) setLocalError(`${bad.name} is not an accepted file type.`)
    else if (big) setLocalError(`${big.name} is larger than ${formatFileSize(maxSize!)}.`)
    else setLocalError(null)
    const ok = chosen.filter(f => matchesAccept(f, accept) && !(maxSize && f.size > maxSize))
    if (ok.length) onFiles(ok)
    if (input.current) input.current.value = ''
  }

  const onDrop = (e: DragEvent) => {
    e.preventDefault()
    setOver(false)
    take(e.dataTransfer.files)
  }

  const err = error ?? localError
  return (
    <div className={cx('ui-dropzone-root', className)}>
      <label
        htmlFor={id}
        className="ui-dropzone"
        data-over={over || undefined}
        data-compact={compact || undefined}
        data-disabled={disabled || undefined}
        data-invalid={err ? true : undefined}
        onDragEnter={e => {
          e.preventDefault()
          if (!disabled) setOver(true)
        }}
        onDragOver={e => e.preventDefault()}
        onDragLeave={e => {
          if (!e.currentTarget.contains(e.relatedTarget as Node)) setOver(false)
        }}
        onDrop={onDrop}
      >
        <input
          ref={input}
          id={id}
          type="file"
          className="ui-dropzone-input"
          accept={accept}
          multiple={multiple}
          disabled={disabled}
          aria-describedby={cx(hint && `${id}-h`, err && `${id}-e`) || undefined}
          aria-invalid={err ? true : undefined}
          onChange={e => take(e.target.files)}
        />
        <Icon icon={Upload} size={20} className="ui-dropzone-icon" />
        <span className="ui-dropzone-text">
          <span>
            {prompt} <span className="ui-dropzone-choose">choose a file</span>
          </span>
          {hint && (
            <span id={`${id}-h`} className="ui-dropzone-hint">
              {hint}
            </span>
          )}
        </span>
      </label>
      {err && (
        <p id={`${id}-e`} className="ui-field-error" role="alert">
          <Icon icon={CircleAlert} size={14} />
          <span>{err}</span>
        </p>
      )}
      {files && files.length > 0 && (
        <ul className="ui-file-list" role="list">
          {files.map(f => (
            <li key={f.id} className="ui-file-row" data-error={f.error ? true : undefined}>
              <Icon icon={FileText} size={16} className="ui-file-icon" />
              <span className="ui-file-name truncate">{f.name}</span>
              <span className="ui-file-size numeric">{formatFileSize(f.size)}</span>
              {f.progress != null && f.progress < 1 && (
                <span className="ui-file-progress" role="progressbar" aria-label={`Uploading ${f.name}`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(f.progress * 100)}>
                  <span style={{ ['--v' as string]: f.progress }} />
                </span>
              )}
              {f.error && <span className="ui-file-error">{f.error}</span>}
              {onRemove && (
                <button type="button" className="ui-file-remove" aria-label={`Remove ${f.name}`} onClick={() => onRemove(f.id)}>
                  <Icon icon={X} size={14} />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
