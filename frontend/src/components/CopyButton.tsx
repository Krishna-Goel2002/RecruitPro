import { useEffect, useRef, useState } from 'react'

async function copyText(value: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value)
      return true
    }
  } catch {
    /* fall back below */
  }
  try {
    const area = document.createElement('textarea')
    area.value = value
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    const ok = document.execCommand('copy')
    area.remove()
    return ok
  } catch {
    return false
  }
}

/** A labelled value with a Copy button; the result ("Copied") is announced politely. */
export function CopyField({ label, value }: { label: string; value: string }) {
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle')
  const timer = useRef<number>()
  useEffect(() => () => window.clearTimeout(timer.current), [])

  async function copy() {
    const ok = await copyText(value)
    setState(ok ? 'copied' : 'failed')
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setState('idle'), 3000)
  }

  return (
    <div className="copy-field">
      <span className="copy-label">{label}</span>
      <code className="copy-value break-anywhere">{value}</code>
      <button type="button" className="quiet-button" onClick={() => void copy()} aria-label={`Copy ${label.toLowerCase()}`}>
        Copy
      </button>
      <span className="copy-status" role="status">
        {state === 'copied' ? 'Copied' : state === 'failed' ? 'Copy failed: select the text and copy it by hand' : ''}
      </span>
    </div>
  )
}
