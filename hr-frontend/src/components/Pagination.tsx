import type { Page } from '../api/types'

export function Pagination({ page, onPage, label = 'records' }: { page: Page<unknown> | undefined; onPage: (page: number) => void; label?: string }) {
  if (!page) return null
  const start = page.totalElements ? page.page * page.size + 1 : 0
  const end = Math.min((page.page + 1) * page.size, page.totalElements)
  return (
    <nav className="table-footer" aria-label="Pagination">
      <span>Showing {start}–{end} of {page.totalElements} {label}</span>
      <div className="pagination">
        <button type="button" className="outline-button" disabled={page.first} onClick={() => onPage(Math.max(0, page.page - 1))}>Previous</button>
        <span aria-current="page">Page {page.page + 1} of {Math.max(page.totalPages, 1)}</span>
        <button type="button" className="outline-button" disabled={page.last} onClick={() => onPage(page.page + 1)}>Next</button>
      </div>
    </nav>
  )
}
