import { useEffect, type ReactNode } from 'react'
import brand from '../brand/active'
import { Breadcrumb, type Crumb } from './Breadcrumb'
import { cx } from './cx'

/** Sets document.title to "{title} · {brand name}" while mounted. */
export function useDocumentTitle(title?: string | null) {
  useEffect(() => {
    const previous = document.title
    document.title = title ? `${title} · ${brand.name}` : brand.name
    return () => {
      document.title = previous
    }
  }, [title])
}

export interface PageHeaderProps {
  title: ReactNode
  /** Plain-text title for document.title when `title` is not a string. */
  documentTitle?: string
  breadcrumb?: Crumb[]
  /** Status badge on the H1 line. */
  status?: ReactNode
  /** Meta items, joined with "·" (body-sm, muted). */
  meta?: ReactNode[]
  description?: ReactNode
  /** Right-aligned actions: primary LAST, at most two visible, the rest in a "⋯" Menu. */
  actions?: ReactNode
  className?: string
}

/**
 * Page header (§6): breadcrumb → H1 + status → meta line → actions on the right. The H1 is the focus target after
 * navigation (tabIndex -1, data-page-heading), so screen-reader users hear the new page.
 */
export function PageHeader({ title, documentTitle, breadcrumb, status, meta, description, actions, className }: PageHeaderProps) {
  useDocumentTitle(documentTitle ?? (typeof title === 'string' ? title : null))
  const metaItems = (meta ?? []).filter(Boolean)
  return (
    <header className={cx('p-page-header', className)}>
      {breadcrumb && breadcrumb.length > 0 && <Breadcrumb items={breadcrumb} />}
      <div className="p-page-header-row">
        <div className="p-page-header-main">
          <div className="p-page-title-line">
            <h1 className="t-h1" tabIndex={-1} data-page-heading="">
              {title}
            </h1>
            {status}
          </div>
          {metaItems.length > 0 && (
            <p className="p-page-meta">
              {metaItems.map((m, i) => (
                <span key={i}>
                  {i > 0 && <span className="ui-sep" aria-hidden="true">·</span>}
                  {m}
                </span>
              ))}
            </p>
          )}
          {description && <p className="p-page-desc">{description}</p>}
        </div>
        {actions && <div className="p-page-actions">{actions}</div>}
      </div>
    </header>
  )
}
