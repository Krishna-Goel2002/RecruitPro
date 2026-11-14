import { useId, type ReactNode } from 'react'
import { useDocumentTitle } from '../lib/hooks'

/**
 * Page title block. The <h1> is focusable (tabIndex -1) so the layout can move focus to it after navigation,
 * which tells screen-reader users that the page changed.
 */
export function PageHeader({
  eyebrow,
  title,
  description,
  actions,
  documentTitle,
}: {
  eyebrow?: string
  title: string
  description?: ReactNode
  actions?: ReactNode
  documentTitle?: string
}) {
  useDocumentTitle(documentTitle ?? title)
  return (
    <header className="page-header">
      <div>
        {eyebrow && <span className="eyebrow">{eyebrow}</span>}
        <h1 tabIndex={-1} data-page-heading>
          {title}
        </h1>
        {description && <p className="page-description">{description}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </header>
  )
}

export function Section({ title, description, actions, children, id }: { title: string; description?: ReactNode; actions?: ReactNode; children: ReactNode; id?: string }) {
  const autoId = useId()
  const headingId = id ? `${id}-title` : autoId
  return (
    <section className="section" aria-labelledby={headingId} id={id}>
      <div className="section-heading">
        <div>
          <h2 id={headingId}>{title}</h2>
          {description && <p className="muted">{description}</p>}
        </div>
        {actions && <div className="section-actions">{actions}</div>}
      </div>
      {children}
    </section>
  )
}
