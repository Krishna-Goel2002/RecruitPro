import { useId, useState, type ClipboardEvent, type KeyboardEvent, type ReactNode } from 'react'
import { Button, Field, Input, Tag } from '../design/components'
import { Globe, Icon, Plus } from '../design/icons'
import './staff-ui.css'

/** Public second levels the server refuses as an organisation-wide domain (a small mirror of its list). */
const PUBLIC_SECOND_LEVELS = new Set(['co.uk', 'org.uk', 'ac.uk', 'gov.uk', 'com.au', 'net.au', 'org.au', 'co.in', 'net.in', 'org.in', 'gov.in', 'ac.in', 'co.nz', 'co.jp', 'com.br', 'com.cn', 'com.sg', 'co.za'])
const DOMAIN_RE = /^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/

/** "https://www.Globex.example/careers" → "globex.example" (scheme, path, port, `www.` and a trailing dot removed). */
export function normalizeDomain(raw: string): string {
  let value = raw.trim().toLowerCase()
  value = value.replace(/^[a-z][a-z0-9+.-]*:\/\//, '')
  value = value.replace(/^[^@/]*@/, '')
  value = value.split(/[/?#]/)[0]
  value = value.replace(/:\d+$/, '').replace(/\.$/, '')
  if (value.startsWith('www.')) value = value.slice(4)
  return value
}

export function domainProblem(domain: string): string | null {
  if (!DOMAIN_RE.test(domain)) return `“${domain}” is not a domain name such as example.com.`
  if (PUBLIC_SECOND_LEVELS.has(domain)) return `“${domain}” is a public suffix; enter the organisation’s own domain.`
  return null
}

/**
 * A list of domain names as removable chips (SSO domains, trusted employer domains, trusted badge issuers). Type and
 * press Enter, comma or space, or paste several at once; entries are normalised as they are added and duplicates are
 * ignored. Invalid entries stay in the field with an inline message.
 */
export function DomainListInput({ label, hint, value, onChange, max = 200, error, placeholder = 'example.com', disabled, addLabel = 'Add' }: {
  label: ReactNode
  hint?: ReactNode
  value: string[]
  onChange: (next: string[]) => void
  max?: number
  /** Server error for the whole list. */
  error?: ReactNode
  placeholder?: string
  disabled?: boolean
  addLabel?: string
}) {
  const [draft, setDraft] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const listId = useId()

  function add(raw: string): boolean {
    const parts = raw.split(/[\s,;]+/).map(normalizeDomain).filter(Boolean)
    if (parts.length === 0) return true
    const next = [...value]
    const bad: string[] = []
    let message: string | null = null
    for (const domain of parts) {
      const found = domainProblem(domain)
      if (found) {
        bad.push(domain)
        message = message ?? found
        continue
      }
      if (!next.includes(domain)) next.push(domain)
    }
    if (next.length > max) {
      setProblem(`At most ${max} domains.`)
      return false
    }
    if (next.length !== value.length) onChange(next)
    setDraft(bad.join(' '))
    setProblem(message)
    return bad.length === 0
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter' || event.key === ',' || (event.key === ' ' && draft.trim())) {
      event.preventDefault()
      add(draft)
    } else if (event.key === 'Backspace' && !draft && value.length > 0) {
      onChange(value.slice(0, -1))
    }
  }

  function onPaste(event: ClipboardEvent<HTMLInputElement>) {
    const text = event.clipboardData.getData('text')
    if (/[\s,;]/.test(text.trim())) {
      event.preventDefault()
      add(`${draft} ${text}`)
    }
  }

  return (
    <div className="sx-domains">
      <Field label={label} hint={hint} error={problem ?? error}>
        <Input
          value={draft}
          disabled={disabled}
          placeholder={placeholder}
          leading={<Icon icon={Globe} size={14} />}
          onChange={event => {
            setDraft(event.target.value)
            if (problem) setProblem(null)
          }}
          onKeyDown={onKeyDown}
          onPaste={onPaste}
          onBlur={() => { if (draft.trim()) add(draft) }}
          aria-controls={listId}
          autoComplete="off"
          spellCheck={false}
          inputMode="url"
        />
      </Field>
      <div className="sx-domains-row">
        <Button size="sm" variant="secondary" icon={Plus} onClick={() => add(draft)} disabled={disabled || !draft.trim()}>
          {addLabel}
        </Button>
        <span className="sx-domains-count numeric" aria-live="polite">
          {value.length === 0 ? 'None yet' : `${value.length} of ${max}`}
        </span>
      </div>
      <ul id={listId} className="sx-domains-list" aria-label="Domains">
        {value.map(domain => (
          <li key={domain}>
            <Tag
              className="sx-domain-chip"
              onRemove={disabled ? undefined : () => onChange(value.filter(item => item !== domain))}
              removeLabel={`Remove ${domain}`}
            >
              <span className="mono">{domain}</span>
            </Tag>
          </li>
        ))}
      </ul>
    </div>
  )
}
