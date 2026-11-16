import { ChevronLeft, ChevronRight, Icon } from '../icons'
import { formatNumber } from '../lib/format'
import { cx } from './cx'

export interface PaginationProps {
  /** 1-based current page. */
  page: number
  pageSize: number
  total: number
  onPageChange: (page: number) => void
  className?: string
}

function pages(current: number, count: number): (number | 'gap')[] {
  if (count <= 7) return Array.from({ length: count }, (_, i) => i + 1)
  const out: (number | 'gap')[] = [1]
  const start = Math.max(2, Math.min(current - 1, count - 4))
  const end = Math.min(count - 1, Math.max(current + 1, 5))
  if (start > 2) out.push('gap')
  for (let p = start; p <= end; p++) out.push(p)
  if (end < count - 1) out.push('gap')
  out.push(count)
  return out
}

/** Pagination (§9.21): "Showing 1–25 of 38" · Previous · up to 7 page numbers with gaps · Next. Keep the page in the URL. */
export function Pagination({ page, pageSize, total, onPageChange, className }: PaginationProps) {
  const count = Math.max(1, Math.ceil(total / pageSize))
  const from = total === 0 ? 0 : (page - 1) * pageSize + 1
  const to = Math.min(total, page * pageSize)
  return (
    <nav className={cx('ui-pagination', className)} aria-label="Pagination">
      <p className="ui-pagination-summary numeric">
        Showing {formatNumber(from)}–{formatNumber(to)} of {formatNumber(total)}
      </p>
      {count > 1 && (
        <ul className="ui-pagination-pages" role="list">
          <li>
            <button type="button" className="ui-page-btn" data-edge="" disabled={page <= 1} onClick={() => onPageChange(page - 1)}>
              <Icon icon={ChevronLeft} size={16} />
              <span className="ui-page-edge-label">Previous</span>
            </button>
          </li>
          {pages(page, count).map((p, i) =>
            p === 'gap' ? (
              <li key={`g${i}`} className="ui-page-gap" aria-hidden="true">
                …
              </li>
            ) : (
              <li key={p}>
                <button
                  type="button"
                  className="ui-page-btn numeric"
                  aria-current={p === page ? 'page' : undefined}
                  aria-label={`Page ${p}`}
                  onClick={() => onPageChange(p)}
                >
                  {p}
                </button>
              </li>
            ),
          )}
          <li>
            <button type="button" className="ui-page-btn" data-edge="" disabled={page >= count} onClick={() => onPageChange(page + 1)}>
              <span className="ui-page-edge-label">Next</span>
              <Icon icon={ChevronRight} size={16} />
            </button>
          </li>
        </ul>
      )}
    </nav>
  )
}
