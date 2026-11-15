import { useEffect, useId, useRef, useState } from 'react'
import { Check, Copy, Icon } from '../icons'
import { cx } from './cx'

export interface CodeFieldProps {
  value: string
  /** Visible label above the field (DNS host, TXT value). */
  label?: string
  /** Shorten long hashes: 'middle' keeps the first 12 and last 6 characters. */
  truncate?: 'middle' | false
  copyable?: boolean
  className?: string
}

/** Clipboard write with a textarea fallback for older browsers / insecure origins. */
async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    ta.remove()
    return ok
  }
}

/**
 * Code field + copy (§9.27, M17): sunken, mono 13, radius 4. The copy icon crossfades to a check, "Copied" is
 * announced, and it reverts after 1.6 s. The full value is in the title and in what gets copied.
 */
export function CodeField({ value, label, truncate = false, copyable = true, className }: CodeFieldProps) {
  const id = useId()
  const [copied, setCopied] = useState(false)
  const timer = useRef<number>()
  useEffect(() => () => window.clearTimeout(timer.current), [])
  const shown = truncate === 'middle' && value.length > 22 ? `${value.slice(0, 12)}…${value.slice(-6)}` : value
  const copy = async () => {
    if (await copyText(value)) {
      setCopied(true)
      window.clearTimeout(timer.current)
      timer.current = window.setTimeout(() => setCopied(false), 1600)
    }
  }
  return (
    <div className={cx('ui-code-root', className)}>
      {label && (
        <span id={`${id}-l`} className="ui-field-label">
          {label}
        </span>
      )}
      <div className="ui-code">
        <code className="ui-code-value" title={value !== shown ? value : undefined} aria-labelledby={label ? `${id}-l` : undefined}>
          {shown}
          {value !== shown && <span className="sr-only"> (full value: {value})</span>}
        </code>
        {copyable && (
          <button type="button" className="ui-code-copy" data-copied={copied || undefined} aria-label={label ? `Copy ${label}` : 'Copy'} onClick={() => void copy()}>
            <Icon icon={Copy} size={14} className="ui-code-copy-icon" />
            <Icon icon={Check} size={14} className="ui-code-check-icon" />
          </button>
        )}
        <span className="sr-only" role="status" aria-live="polite">
          {copied ? 'Copied' : ''}
        </span>
      </div>
    </div>
  )
}

/** Alias used by the redesign brief (CopyField = CodeField). */
export const CopyField = CodeField
