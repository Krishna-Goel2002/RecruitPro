import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type PointerEvent as ReactPointerEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, IconButton, Tooltip } from '../design/components'
import { Maximize2, MessagesSquare, SquarePen, X } from '../design/icons'
import { reduced } from '../design/motion'
import { useAssistant } from './AssistantProvider'
import { ConversationView, shortcutLabel } from './Conversation'
import './assistant.css'

const WIDTH_KEY = 'rp-assistant-width'
const MIN_W = 360
const DEFAULT_W = 440
const maxWidth = () => Math.max(MIN_W, Math.min(960, Math.round(window.innerWidth * 0.7)))

function storedWidth(): number {
  try {
    const n = Number(window.localStorage.getItem(WIDTH_KEY))
    return Number.isFinite(n) && n >= MIN_W ? n : DEFAULT_W
  } catch {
    return DEFAULT_W
  }
}

/** Top-bar entry point: "Ask" with the shortcut. */
export function AskButton() {
  const a = useAssistant()
  return (
    <Tooltip content={`Ask the assistant (${shortcutLabel()})`} labelOnly>
      <Button
        size="sm"
        icon={MessagesSquare}
        className="as-ask"
        onClick={a.togglePanel}
        aria-expanded={a.open}
        aria-controls="assistant-panel"
        aria-keyshortcuts="Control+K Meta+K"
      >
        Ask
        <kbd className="as-ask-kbd" aria-hidden="true">{shortcutLabel()}</kbd>
      </Button>
    </Tooltip>
  )
}

/**
 * Right-side assistant panel (non-modal: the page stays usable). Opens with "Ask" or Ctrl/⌘ + K, closes with Escape
 * (focus inside), the close button or the shortcut again; focus goes to the composer on open and back to where it was
 * on close. The left edge drags (or Arrow keys on the handle) to resize; "Expand" opens the same conversation at
 * /assistant. Below 600 px it is a full-width sheet.
 */
export function AssistantPanel() {
  const a = useAssistant()
  const navigate = useNavigate()
  const [mounted, setMounted] = useState(a.open)
  const [closing, setClosing] = useState(false)
  const [width, setWidth] = useState(storedWidth)
  const returnFocus = useRef<HTMLElement | null>(null)
  const panel = useRef<HTMLElement>(null)

  useEffect(() => {
    if (a.open) {
      returnFocus.current = document.activeElement as HTMLElement | null
      setClosing(false)
      setMounted(true)
      a.focusComposer()
      return
    }
    if (!mounted) return
    const back = () => {
      const target = returnFocus.current
      if (target && document.contains(target) && !panel.current?.contains(target)) target.focus({ preventScroll: true })
    }
    if (reduced()) {
      setMounted(false)
      back()
      return
    }
    setClosing(true)
    const t = window.setTimeout(() => {
      setMounted(false)
      setClosing(false)
      back()
    }, 160)
    return () => window.clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [a.open])

  useEffect(() => {
    try {
      window.localStorage.setItem(WIDTH_KEY, String(width))
    } catch {
      /* ignore */
    }
  }, [width])

  if (!mounted) return null

  const onKeyDown = (e: ReactKeyboardEvent<HTMLElement>) => {
    if (e.key === 'Escape' && !e.defaultPrevented) {
      e.stopPropagation()
      a.closePanel()
    }
  }

  const startDrag = (e: ReactPointerEvent<HTMLDivElement>) => {
    e.preventDefault()
    const handle = e.currentTarget
    handle.setPointerCapture(e.pointerId)
    const move = (ev: PointerEvent) => setWidth(Math.min(maxWidth(), Math.max(MIN_W, window.innerWidth - ev.clientX)))
    const up = () => {
      handle.removeEventListener('pointermove', move)
      handle.removeEventListener('pointerup', up)
      handle.removeEventListener('pointercancel', up)
    }
    handle.addEventListener('pointermove', move)
    handle.addEventListener('pointerup', up)
    handle.addEventListener('pointercancel', up)
  }
  const onHandleKey = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    const step = e.shiftKey ? 80 : 24
    if (e.key === 'ArrowLeft') setWidth(w => Math.min(maxWidth(), w + step))
    else if (e.key === 'ArrowRight') setWidth(w => Math.max(MIN_W, w - step))
    else if (e.key === 'Home') setWidth(maxWidth())
    else if (e.key === 'End') setWidth(MIN_W)
    else return
    e.preventDefault()
  }

  return (
    <aside
      ref={panel}
      id="assistant-panel"
      className="as-panel"
      data-closing={closing || undefined}
      aria-label="Assistant"
      style={{ ['--as-w' as string]: `${width}px` }}
      onKeyDown={onKeyDown}
    >
      <div
        className="as-resize"
        role="separator"
        aria-orientation="vertical"
        aria-label="Resize the assistant panel"
        aria-valuemin={MIN_W}
        aria-valuemax={typeof window !== 'undefined' ? maxWidth() : 960}
        aria-valuenow={width}
        tabIndex={0}
        onPointerDown={startDrag}
        onKeyDown={onHandleKey}
      />
      <header className="as-panel-head">
        <h2 className="as-panel-title">
          <span className="as-panel-mark" aria-hidden="true"><MessagesSquare size={16} strokeWidth={1.75} absoluteStrokeWidth /></span>
          Assistant
        </h2>
        <div className="as-panel-tools">
          <IconButton size="sm" label="New conversation" icon={SquarePen} onClick={a.newConversation} />
          <IconButton
            size="sm"
            label="Open as a full page"
            icon={Maximize2}
            onClick={() => {
              a.closePanel()
              navigate(`/assistant${a.conversationId != null ? `?c=${encodeURIComponent(String(a.conversationId))}` : ''}`)
            }}
          />
          <IconButton size="sm" label="Close assistant (Esc)" icon={X} onClick={a.closePanel} />
        </div>
      </header>
      <ConversationView compact />
    </aside>
  )
}
