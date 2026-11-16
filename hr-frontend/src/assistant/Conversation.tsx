import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react'
import type { AssistantMessage } from '../api/assistantTypes'
import { useAuth } from '../auth/AuthContext'
import { Alert, Button, IconButton, Skeleton, SkeletonText } from '../design/components'
import { Icon, MessagesSquare, SendHorizontal, X } from '../design/icons'
import { formatDate } from '../design/lib/format'
import { useAssistant, isMac, type PendingSend } from './AssistantProvider'
import { Blocks } from './blocks'
import { MarkdownLite } from './markdown'
import { suggestionsFor } from './pageContext'

const MAX_TEXT = 2000

const PLANNER_LABELS: Record<string, string> = {
  offline: 'Offline rules',
  gemini: 'Gemini',
  groq: 'Groq',
  anthropic: 'Anthropic',
  openai_compatible: 'Hosted model',
}

function timeOf(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const sameDay = new Date().toDateString() === d.toDateString()
  const time = d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' })
  return sameDay ? time : `${formatDate(d, { day: 'numeric', month: 'short' })}, ${time}`
}

/* ------------------------------------------------------------------------------------------------ composer */

export interface ComposerProps {
  onSend: (text: string) => void
  disabled?: boolean
  /** Sending is blocked (a reply is on its way) but typing is not. */
  busy?: boolean
  placeholder?: string
  textareaRef?: React.MutableRefObject<HTMLTextAreaElement | null>
  /** Text to restore (after a failed send). */
  restore?: string | null
}

/**
 * Message composer: multi-line, Enter sends, Shift + Enter adds a line (IME composition never sends), grows up to
 * eight lines and then scrolls. The text is kept until the send succeeds or the user clears it.
 */
export function Composer({ onSend, disabled, busy, placeholder = 'Ask about a job, an applicant or what needs attention', textareaRef, restore }: ComposerProps) {
  const [text, setText] = useState('')
  const local = useRef<HTMLTextAreaElement | null>(null)
  const id = 'as-composer-input'

  useEffect(() => {
    if (restore) setText(restore)
  }, [restore])

  // Auto-grow: 1 line up to 8 lines, then scroll.
  useLayoutEffect(() => {
    const el = local.current
    if (!el) return
    el.style.height = 'auto'
    const line = parseFloat(getComputedStyle(el).lineHeight) || 20
    const max = line * 8 + 16
    el.style.height = `${Math.min(max, Math.max(line + 16, el.scrollHeight + 2))}px`
  }, [text])

  const submit = () => {
    const value = text.trim()
    if (!value || disabled || busy) return
    onSend(value)
    setText('')
  }

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.altKey && !e.ctrlKey && !e.metaKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      submit()
    }
  }

  const near = text.length > MAX_TEXT - 200
  return (
    <form
      className="as-composer"
      onSubmit={e => {
        e.preventDefault()
        submit()
      }}
    >
      <label htmlFor={id} className="sr-only">
        Message the assistant
      </label>
      <div className="as-composer-box">
        <textarea
          id={id}
          ref={el => {
            local.current = el
            if (textareaRef) textareaRef.current = el
          }}
          className="as-composer-input"
          rows={1}
          maxLength={MAX_TEXT}
          value={text}
          placeholder={placeholder}
          disabled={disabled}
          onChange={e => setText(e.target.value)}
          onKeyDown={onKeyDown}
          aria-describedby="as-composer-hint"
          enterKeyHint="send"
        />
        <IconButton
          type="submit"
          className="as-send"
          label="Send"
          icon={SendHorizontal}
          variant={text.trim() && !busy ? 'secondary' : 'ghost'}
          disabled={disabled || busy || !text.trim()}
          noTooltip
        />
      </div>
      <p id="as-composer-hint" className="as-composer-hint">
        <span>
          <kbd>Enter</kbd> to send · <kbd>Shift</kbd> + <kbd>Enter</kbd> for a new line
        </span>
        {near && <span className="numeric" data-over={text.length >= MAX_TEXT || undefined}>{text.length} / {MAX_TEXT}</span>}
      </p>
    </form>
  )
}

/* ------------------------------------------------------------------------------------------------ messages */

function Thinking({ pending }: { pending: PendingSend }) {
  const [slow, setSlow] = useState(false)
  useEffect(() => {
    const t = window.setTimeout(() => setSlow(true), 8000)
    return () => window.clearTimeout(t)
  }, [pending.startedAt])
  return (
    <div className="as-msg as-msg--assistant as-thinking-row" aria-hidden="true">
      <span className="ui-thinking">
        <span />
        <span />
        <span />
      </span>
      <span className="as-thinking-text">{slow ? 'Still working: checking the data can take up to a minute.' : 'Working on it…'}</span>
    </div>
  )
}

function Message({ message, latest, canAct }: { message: AssistantMessage; latest: boolean; canAct: boolean }) {
  const a = useAssistant()
  const user = message.role === 'user'
  return (
    <li className={`as-msg as-msg--${user ? 'user' : 'assistant'} ui-message-in`} data-message-id={String(message.id)}>
      <h3 className="sr-only">{user ? 'You said' : 'Assistant'}</h3>
      {user ? <p className="as-bubble">{message.text}</p> : <MarkdownLite text={message.text} />}
      {!user && message.blocks?.length > 0 && (
        <Blocks
          blocks={message.blocks}
          handlers={{
            interactive: latest && canAct,
            canAct,
            busyAction: a.busyAction,
            onAnswer: text => a.send(text),
            onConfirm: (block, mode) => void a.confirmAction(block, mode ? { mode } : {}),
            onCancel: block => void a.cancelAction(block),
          }}
        />
      )}
      <p className="as-msg-meta">
        <time dateTime={message.createdAt}>{timeOf(message.createdAt)}</time>
        {!user && message.planner && <span> · {PLANNER_LABELS[message.planner] ?? message.planner}</span>}
      </p>
    </li>
  )
}

/** Empty conversation: what it can do here, suggested prompts for this page, and the confirm promise. */
function Welcome({ compact }: { compact?: boolean }) {
  const a = useAssistant()
  const { can } = useAuth()
  const suggestions = suggestionsFor(a.context, can)
  return (
    <div className="as-welcome" data-compact={compact || undefined}>
      <span className="as-welcome-icon" aria-hidden="true">
        <Icon icon={MessagesSquare} size={20} />
      </span>
      <h2 className="as-welcome-title">What can I help with?</h2>
      <p className="as-welcome-text">
        Ask about jobs, applicants and what needs attention, or have it draft a job posting. It works with your permissions
        and <strong>always asks before it changes anything</strong>.
      </p>
      <Suggestions items={suggestions.map(s => s.prompt ?? s.label)} onPick={a.send} disabled={a.pending != null} />
    </div>
  )
}

export function Suggestions({ items, onPick, disabled, label = 'Suggested questions' }: { items: string[]; onPick: (text: string) => void; disabled?: boolean; label?: string }) {
  if (items.length === 0) return null
  return (
    <div className="as-suggest">
      <p className="as-suggest-label">{label}</p>
      <ul className="as-chips ui-stagger" role="list">
        {items.map(text => (
          <li key={text}>
            <button type="button" className="as-chip ui-lift" onClick={() => onPick(text)} disabled={disabled}>
              {text}
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

/**
 * The conversation: context line, message log (role="log", announced politely), thinking indicator, send error and
 * composer. Used by the side panel and the /assistant page.
 */
export function ConversationView({ compact }: { compact?: boolean }) {
  const a = useAssistant()
  const { can } = useAuth()
  const scroller = useRef<HTMLDivElement>(null)
  const messages = a.messages
  const lastAssistant = [...messages].reverse().find(m => m.role === 'assistant')
  const loading = a.conversationId != null && a.conversation.isPending && messages.length === 0

  // Keep the newest message in view.
  useEffect(() => {
    const el = scroller.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages.length, a.pending])

  const followUps = lastAssistant && !a.pending && messages.length > 0 && !lastAssistant.blocks?.some(b => b.kind === 'question') ? suggestionsFor(a.context, can).slice(0, 3) : []
  const empty = !loading && messages.length === 0 && !a.pending

  return (
    <div className="as-convo" data-compact={compact || undefined}>
      <div ref={scroller} className="as-scroll" tabIndex={-1}>
        {loading ? (
          <div className="as-loading" aria-label="Loading the conversation">
            <SkeletonText lines={2} />
            <Skeleton height={64} />
            <SkeletonText lines={3} />
          </div>
        ) : empty ? (
          <Welcome compact={compact} />
        ) : (
          // The live region wraps the list (role="log" on the <ol> itself would strip the list semantics its items need).
          <div role="log" aria-live="polite" aria-relevant="additions" aria-label="Conversation" className="as-log-region">
          <ol className="as-log" role="list">
            {messages.map(m => (
              <Message key={String(m.id)} message={m} latest={m === lastAssistant && m === messages[messages.length - 1]} canAct={!a.pending} />
            ))}
            {a.pending && (
              <li className="as-msg as-msg--user ui-message-in">
                <p className="as-bubble">{a.pending.text}</p>
              </li>
            )}
          </ol>
          </div>
        )}
        {a.pending && <Thinking pending={a.pending} />}
        <p className="sr-only" role="status">{a.pending ? 'The assistant is working on it.' : ''}</p>
        {a.sendError && (
          <Alert
            tone="danger"
            live
            className="as-error"
            action={
              <span className="as-btns">
                <Button size="sm" onClick={a.retry}>Try again</Button>
                <IconButton size="sm" label="Dismiss" icon={X} onClick={a.dismissError} noTooltip />
              </span>
            }
          >
            {a.sendError}
          </Alert>
        )}
        {followUps.length > 0 && <Suggestions items={followUps.map(s => s.prompt ?? s.label)} onPick={a.send} label="Ask next" />}
      </div>
      <div className="as-foot">
        {a.contextLabel && (
          <p className="as-context">
            <span className="subtle">Context</span> <span className="as-context-chip">{a.contextLabel}</span>
          </p>
        )}
        <Composer onSend={a.send} busy={a.pending != null} textareaRef={a.composerRef} />
      </div>
    </div>
  )
}

export const shortcutLabel = () => (isMac() ? '⌘K' : 'Ctrl K')
