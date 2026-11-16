import { useEffect, type ReactNode } from 'react'
import brand from '../brand/active'
import { BackLink, useBackTarget, type BackTarget } from './BackLink'
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
  /**
   * "← Back to …" above the title. Omitted: the target the layout provides for this route (BackNavProvider);
   * `null`: no back control (top-level pages); an object: this exact target.
   */
  back?: BackTarget | null
  className?: string
}

/**
 * Page header (§6): back control + breadcrumb → H1 + status → meta line → actions on the right. The H1 is the focus
 * target after navigation (tabIndex -1, data-page-heading), so screen-reader users hear the new page.
 */
export function PageHeader({ title, documentTitle, breadcrumb, status, meta, description, actions, back, className }: PageHeaderProps) {
  useDocumentTitle(documentTitle ?? (typeof title === 'string' ? title : null))
  const provided = useBackTarget()
  const target = back === undefined ? provided : back
  const metaItems = (meta ?? []).filter(Boolean)
  const crumbs = breadcrumb && breadcrumb.length > 0
  return (
    <header className={cx('p-page-header', className)}>
      {(target || crumbs) && (
        <div className="p-page-nav">
          {target && <BackLink {...target} />}
          {crumbs && <Breadcrumb items={breadcrumb} />}
        </div>
      )}
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
