import { Fragment, type ReactNode } from 'react'
import { Link } from 'react-router-dom'

/**
 * Markdown-lite for assistant text: paragraphs, line breaks, `-`/`*` and `1.` lists, **bold**, *italic* / _italic_,
 * `code` and [links](url). Output is built as React elements (never innerHTML), so markup in the text is shown as
 * text. Links: http/https open in a new tab; app paths ("/jobs/12") use the router; anything else (javascript:, data:,
 * protocol-relative "//host") is rendered as plain text.
 */

export type SafeHref = { kind: 'internal'; to: string } | { kind: 'external'; href: string } | null

export function safeHref(raw: string | null | undefined): SafeHref {
  const value = (raw ?? '').trim()
  if (!value) return null
  if (value.startsWith('/') && !value.startsWith('//') && !value.startsWith('/\\')) return { kind: 'internal', to: value }
  try {
    const url = new URL(value)
    if (url.protocol === 'http:' || url.protocol === 'https:') return { kind: 'external', href: url.href }
  } catch {
    /* not a URL */
  }
  return null
}

export function SafeLink({ href, children, className }: { href: string; children: ReactNode; className?: string }) {
  const safe = safeHref(href)
  if (!safe) return <span className={className}>{children}</span>
  if (safe.kind === 'internal') return <Link to={safe.to} className={className}>{children}</Link>
  return (
    <a href={safe.href} target="_blank" rel="noopener noreferrer" className={className}>
      {children}
    </a>
  )
}

const INLINE = /(\*\*([^*]+?)\*\*|__([^_]+?)__|`([^`]+?)`|\[([^\]]+?)\]\(([^)\s]+?)\)|\*([^*\s][^*]*?)\*|(?<![\w])_([^_\s][^_]*?)_(?![\w]))/g

export function renderInline(text: string, keyBase = 'i'): ReactNode[] {
  const out: ReactNode[] = []
  let last = 0
  let n = 0
  for (const m of text.matchAll(INLINE)) {
    const at = m.index ?? 0
    if (at > last) out.push(text.slice(last, at))
    const key = `${keyBase}-${n++}`
    if (m[2] ?? m[3]) out.push(<strong key={key}>{renderInline(m[2] ?? m[3], key)}</strong>)
    else if (m[4]) out.push(<code key={key} className="as-code">{m[4]}</code>)
    else if (m[5] && m[6]) out.push(<SafeLink key={key} href={m[6]}>{m[5]}</SafeLink>)
    else if (m[7] ?? m[8]) out.push(<em key={key}>{m[7] ?? m[8]}</em>)
    last = at + m[0].length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

type Chunk = { type: 'p'; lines: string[] } | { type: 'list'; ordered: boolean; items: string[] } | { type: 'h'; text: string }

function chunks(text: string): Chunk[] {
  const out: Chunk[] = []
  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  const st: { para: string[] | null; list: { ordered: boolean; items: string[] } | null } = { para: null, list: null }
  const flush = () => {
    if (st.para) out.push({ type: 'p', lines: st.para })
    if (st.list) out.push({ type: 'list', ordered: st.list.ordered, items: st.list.items })
    st.para = null
    st.list = null
  }
  for (const raw of lines) {
    const line = raw.trimEnd()
    const ul = /^\s*[-*•]\s+(.*)$/.exec(line)
    const ol = /^\s*\d+[.)]\s+(.*)$/.exec(line)
    const h = /^\s*#{1,6}\s+(.*)$/.exec(line)
    if (!line.trim()) {
      flush()
    } else if (h) {
      flush()
      out.push({ type: 'h', text: h[1] })
    } else if (ul || ol) {
      const ordered = !ul
      if (!st.list || st.list.ordered !== ordered) {
        flush()
        st.list = { ordered, items: [] }
      }
      st.list.items.push((ul ?? ol)![1])
    } else if (st.list && /^\s{2,}\S/.test(raw)) {
      // Indented continuation of a list item.
      const items = st.list.items
      items[items.length - 1] += ` ${line.trim()}`
    } else {
      if (st.list) flush()
      if (!st.para) st.para = []
      st.para.push(line)
    }
  }
  flush()
  return out
}

export function MarkdownLite({ text, className }: { text: string; className?: string }) {
  if (!text?.trim()) return null
  return (
    <div className={className ?? 'as-md'}>
      {chunks(text).map((c, i) => {
        const key = `c${i}`
        if (c.type === 'h') return <p key={key} className="as-md-h">{renderInline(c.text, key)}</p>
        if (c.type === 'list') {
          const items = c.items.map((item, j) => <li key={j}>{renderInline(item, `${key}-${j}`)}</li>)
          return c.ordered ? <ol key={key}>{items}</ol> : <ul key={key}>{items}</ul>
        }
        return (
          <p key={key}>
            {c.lines.map((line, j) => (
              <Fragment key={j}>
                {j > 0 && <br />}
                {renderInline(line, `${key}-${j}`)}
              </Fragment>
            ))}
          </p>
        )
      })}
    </div>
  )
}
