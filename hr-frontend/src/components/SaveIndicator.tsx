import { useEffect, useState } from 'react'
import { relativeTime } from '../design/lib/format'
import { Check, CircleAlert, Icon } from '../design/icons'
import { Spinner } from '../design/components'
import './hra.css'

export type SaveState =
  | { kind: 'idle' }
  | { kind: 'dirty' }
  | { kind: 'saving' }
  | { kind: 'saved'; at: number; where: 'server' | 'device' }
  | { kind: 'error'; message: string; retry: () => void }

/** Autosave status line ("Saved · just now", "Saving…", "Couldn't save · Retry"). Polite live region. */
export function SaveIndicator({ state }: { state: SaveState }) {
  const [, tick] = useState(0)
  useEffect(() => {
    if (state.kind !== 'saved') return
    const timer = window.setInterval(() => tick(n => n + 1), 30_000)
    return () => window.clearInterval(timer)
  }, [state])

  if (state.kind === 'idle') return null
  let content = null
  if (state.kind === 'dirty') content = <span className="hra-save-text">Unsaved changes</span>
  if (state.kind === 'saving') content = <><Spinner size={12} /><span className="hra-save-text">Saving…</span></>
  if (state.kind === 'saved') {
    const ago = Date.now() - state.at < 45_000 ? 'just now' : relativeTime(state.at)
    content = <><Icon icon={Check} size={12} /><span className="hra-save-text">{state.where === 'device' ? 'Draft kept on this device' : 'Saved'} · {ago}</span></>
  }
  if (state.kind === 'error') {
    content = (
      <>
        <Icon icon={CircleAlert} size={12} />
        <span className="hra-save-text">Couldn’t save</span>
        <button type="button" className="hra-save-retry" onClick={state.retry}>Retry</button>
      </>
    )
  }
  return (
    <p className="hra-save" data-kind={state.kind} role="status" aria-live="polite">
      {content}
    </p>
  )
}
