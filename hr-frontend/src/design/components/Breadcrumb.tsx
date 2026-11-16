import { Link } from 'react-router-dom'
import { ChevronRight, Icon } from '../icons'
import { cx } from './cx'

export interface Crumb {
  label: string
  /** Router path; the last crumb (current page) has none. */
  to?: string
}

/** Breadcrumb (§9.22): body-sm muted links, 12 px chevrons, current page in default ink; middle crumbs collapse on phones. */
export function Breadcrumb({ items, className }: { items: Crumb[]; className?: string }) {
  return (
    <nav aria-label="Breadcrumb" className={cx('ui-breadcrumb', className)}>
      <ol>
        {items.map((c, i) => {
          const last = i === items.length - 1
          return (
            <li key={`${c.label}-${i}`} data-middle={i > 0 && i < items.length - 1 ? true : undefined}>
              {last || !c.to ? (
                <span aria-current={last ? 'page' : undefined} title={last ? c.label : undefined}>{c.label}</span>
              ) : (
                <Link to={c.to}>{c.label}</Link>
              )}
              {!last && <Icon icon={ChevronRight} size={12} className="ui-breadcrumb-sep" />}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
