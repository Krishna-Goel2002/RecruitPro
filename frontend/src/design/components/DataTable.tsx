import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type MouseEvent, type ReactNode } from 'react'
import { ArrowDown, ArrowUp, ArrowUpDown, Icon, Rows3, Rows4 } from '../icons'
import { stagger, useFlip } from '../motion'
import { formatNumber } from '../lib/format'
import { Checkbox } from './Checkbox'
import { cx } from './cx'
import { useStoredState } from './hooks'
import { SegmentedControl } from './SegmentedControl'

export type Density = 'comfortable' | 'compact'
export type SortDir = 'asc' | 'desc'
export interface SortState {
  key: string
  dir: SortDir
}

export interface Column<T> {
  key: string
  header: ReactNode
  cell: (row: T, index: number) => ReactNode
  /** Makes the header a sort button. Rows are sorted locally with this value unless `manualSort` is set. */
  sortValue?: (row: T) => string | number | null | undefined
  align?: 'start' | 'end' | 'center'
  /** Right-aligned tabular numerals. */
  numeric?: boolean
  /** CSS width (e.g. '32px', '20%'). */
  width?: string
  /** The cell shown first (and bold-ish) when rows stack on phones; also the row header for screen readers. */
  primary?: boolean
  /** Hide this column when rows stack below 600 px. */
  hideWhenStacked?: boolean
}

export interface RowGroup<T> {
  id: string
  title: ReactNode
  description?: ReactNode
  rows: T[]
}

export interface DataTableProps<T> {
  /** Accessible name (rendered as a visually hidden caption unless `captionVisible`). */
  caption: string
  captionVisible?: boolean
  columns: Column<T>[]
  rows?: T[]
  /** Group header rows (e.g. Ranked / Must-haves not yet verified / Unscored). Overrides `rows`. */
  groups?: RowGroup<T>[]
  rowKey: (row: T) => string | number
  sort?: SortState | null
  defaultSort?: SortState
  onSortChange?: (sort: SortState) => void
  /** Sorting happens on the server: do not reorder rows locally. */
  manualSort?: boolean
  density?: Density
  loading?: boolean
  loadingRows?: number
  /** Shown in place of the body when there are no rows (use <EmptyState compact>). */
  empty?: ReactNode
  /** Makes rows activatable: click anywhere on the row, or ↑/↓ then Enter. */
  onRowActivate?: (row: T) => void
  /** Highlighted (selected) row, e.g. the one open in a drawer. */
  activeRowKey?: string | number | null
  selectable?: boolean
  selected?: ReadonlySet<string | number>
  onSelectionChange?: (next: Set<string | number>) => void
  /** Rendered above the table; replaced by `bulkBar` while a selection exists. */
  toolbar?: ReactNode
  bulkBar?: (count: number, clear: () => void) => ReactNode
  /** Constrain height so the header sticks (default true: up to the viewport height). */
  stickyHeader?: boolean
  maxHeight?: string
  /** Stack rows below 600 px (primary cell, then a ·-separated meta line). Default true. */
  stackOnPhone?: boolean
  /** Rows glide to new positions when their order changes (M7). */
  animateReorder?: boolean
  /** Rows fade up in reading order on first load (M2). Default true. */
  animateEntrance?: boolean
  className?: string
}

function compare(a: unknown, b: unknown): number {
  if (a == null && b == null) return 0
  if (a == null) return 1
  if (b == null) return -1
  if (typeof a === 'number' && typeof b === 'number') return a - b
  return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: 'base' })
}

/**
 * DataTable (§9.6): sticky header, sortable headers with aria-sort, density, hover/selected rows, keyboard row focus,
 * selection with a tri-state header checkbox and a bulk bar, skeleton and empty rows, group header rows, and
 * stacked rows on phones. Columns render cells; the table owns structure and state only.
 */
export function DataTable<T>(props: DataTableProps<T>) {
  const {
    caption, captionVisible, columns, rows = [], groups, rowKey, sort: sortProp, defaultSort, onSortChange, manualSort,
    density = 'comfortable', loading, loadingRows = 6, empty, onRowActivate, activeRowKey, selectable, selected,
    onSelectionChange, toolbar, bulkBar, stickyHeader = true, maxHeight, stackOnPhone = true, animateReorder,
    animateEntrance = true, className,
  } = props

  const [innerSort, setInnerSort] = useState<SortState | null>(defaultSort ?? null)
  const sort = sortProp !== undefined ? sortProp : innerSort
  const setSort = (s: SortState) => {
    if (sortProp === undefined) setInnerSort(s)
    onSortChange?.(s)
  }

  const sortRows = (list: T[]) => {
    if (!sort || manualSort) return list
    const col = columns.find(c => c.key === sort.key)
    if (!col?.sortValue) return list
    const sv = col.sortValue
    const out = [...list].sort((a, b) => compare(sv(a), sv(b)))
    return sort.dir === 'desc' ? out.reverse() : out
  }

  const sections: RowGroup<T>[] = useMemo(
    () => (groups ? groups.map(g => ({ ...g, rows: sortRows(g.rows) })) : [{ id: '_', title: null, rows: sortRows(rows) }]),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [groups, rows, sort, manualSort, columns],
  )
  const flat = sections.flatMap(s => s.rows)
  const keys = flat.map(rowKey)
  const tbodyRef = useRef<HTMLTableSectionElement>(null)
  const wrapRef = useRef<HTMLDivElement>(null)
  useFlip(tbodyRef, animateReorder ? keys.join('|') : null)

  // M2: stagger rows in on the first non-loading render that has rows.
  const entered = useRef(false)
  useEffect(() => {
    if (entered.current || loading || !flat.length || !tbodyRef.current) return
    entered.current = true
    if (animateEntrance) stagger(tbodyRef.current.querySelectorAll<HTMLElement>('tr[data-row]'))
  }, [loading, flat.length, animateEntrance])

  // A scrolling wrapper must be reachable by keyboard when nothing inside it is focusable (axe scrollable-region).
  const [scrollable, setScrollable] = useState(false)
  useEffect(() => {
    const el = wrapRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const check = () => setScrollable(el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1)
    check()
    const ro = new ResizeObserver(check)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  // Roving row focus.
  const [focusKey, setFocusKey] = useState<string | number | null>(null)
  const tabbableKey = focusKey != null && keys.includes(focusKey) ? focusKey : keys[0]
  const focusRow = (key: string | number) => {
    setFocusKey(key)
    tbodyRef.current?.querySelector<HTMLElement>(`tr[data-row="${CSS.escape(String(key))}"]`)?.focus()
  }

  const toggle = (key: string | number) => {
    if (!onSelectionChange) return
    const next = new Set(selected ?? [])
    if (next.has(key)) next.delete(key)
    else next.add(key)
    onSelectionChange(next)
  }
  const selCount = selected ? keys.filter(k => selected.has(k)).length : 0
  const allSelected = selCount > 0 && selCount === keys.length

  const onRowKey = (e: KeyboardEvent<HTMLTableRowElement>, row: T, key: string | number) => {
    if (e.target !== e.currentTarget) return
    const i = keys.indexOf(key)
    if (e.key === 'ArrowDown' && i < keys.length - 1) {
      e.preventDefault()
      focusRow(keys[i + 1])
    } else if (e.key === 'ArrowUp' && i > 0) {
      e.preventDefault()
      focusRow(keys[i - 1])
    } else if (e.key === 'Home') {
      e.preventDefault()
      focusRow(keys[0])
    } else if (e.key === 'End') {
      e.preventDefault()
      focusRow(keys[keys.length - 1])
    } else if (e.key === 'Enter' && onRowActivate) {
      e.preventDefault()
      onRowActivate(row)
    } else if (e.key === ' ' && selectable) {
      e.preventDefault()
      toggle(key)
    }
  }

  const onRowClick = (e: MouseEvent<HTMLTableRowElement>, row: T) => {
    if (!onRowActivate) return
    if ((e.target as HTMLElement).closest('a,button,input,select,textarea,label,[role="button"]')) return
    if (window.getSelection()?.toString()) return
    onRowActivate(row)
  }

  const colCount = columns.length + (selectable ? 1 : 0)
  // Stacked phone rows: the first visible meta cell gets no leading "·".
  const metaFirst = columns.find(c => !c.primary && !c.hideWhenStacked)?.key
  const interactive = !!onRowActivate || !!selectable
  const showBulk = selectable && bulkBar && selCount > 0

  return (
    <div className={cx('ui-table-root', className)}>
      {(toolbar || showBulk) && (
        <div className="ui-table-toolbar" data-bulk={showBulk || undefined}>
          {showBulk ? bulkBar!(selCount, () => onSelectionChange?.(new Set())) : toolbar}
        </div>
      )}
      <div
        ref={wrapRef}
        className="ui-table-wrap"
        data-sticky={stickyHeader || undefined}
        style={maxHeight ? { maxHeight } : undefined}
        tabIndex={scrollable && !interactive ? 0 : undefined}
        role={scrollable && !interactive ? 'region' : undefined}
        aria-label={scrollable && !interactive ? caption : undefined}
      >
        <table
          className="ui-table"
          data-density={density}
          data-stack={stackOnPhone || undefined}
          data-interactive={interactive || undefined}
          aria-busy={loading || undefined}
          aria-rowcount={loading ? -1 : undefined}
        >
          <caption className={captionVisible ? 'ui-table-caption' : 'sr-only'}>{caption}</caption>
          <colgroup>
            {selectable && <col style={{ width: 40 }} />}
            {columns.map(c => (
              <col key={c.key} style={c.width ? { width: c.width } : undefined} />
            ))}
          </colgroup>
          <thead>
            <tr>
              {selectable && (
                <th scope="col" className="ui-td-select">
                  <Checkbox
                    aria-label={allSelected ? 'Clear selection' : 'Select all rows'}
                    checked={allSelected}
                    indeterminate={selCount > 0 && !allSelected}
                    onChange={() => onSelectionChange?.(allSelected ? new Set() : new Set(keys))}
                  />
                </th>
              )}
              {columns.map(c => {
                const active = sort?.key === c.key
                const ariaSort = active ? (sort!.dir === 'asc' ? 'ascending' : 'descending') : 'none'
                const sortable = !!c.sortValue
                return (
                  <th
                    key={c.key}
                    scope="col"
                    aria-sort={sortable ? ariaSort : undefined}
                    data-align={c.numeric ? 'end' : c.align}
                    data-hide-stacked={c.hideWhenStacked || undefined}
                  >
                    {sortable ? (
                      <button
                        type="button"
                        className="ui-th-sort"
                        data-active={active || undefined}
                        onClick={() => setSort({ key: c.key, dir: active && sort!.dir === 'asc' ? 'desc' : 'asc' })}
                      >
                        <span>{c.header}</span>
                        <Icon icon={active ? (sort!.dir === 'asc' ? ArrowUp : ArrowDown) : ArrowUpDown} size={14} />
                      </button>
                    ) : (
                      c.header
                    )}
                  </th>
                )
              })}
            </tr>
          </thead>
          <tbody ref={tbodyRef}>
            {loading &&
              Array.from({ length: loadingRows }, (_, r) => (
                <tr key={`sk${r}`} className="ui-tr-skeleton" aria-hidden="true">
                  {selectable && <td className="ui-td-select" />}
                  {columns.map((c, ci) => (
                    <td key={c.key} data-align={c.numeric ? 'end' : c.align}>
                      <span className="ui-skeleton ui-skeleton-line" style={{ width: c.numeric ? '40%' : `${[72, 56, 64, 44, 80][(r + ci) % 5]}%` }} />
                    </td>
                  ))}
                </tr>
              ))}
            {!loading && flat.length === 0 && (
              <tr className="ui-tr-empty">
                <td colSpan={colCount}>{empty ?? <p className="ui-table-empty-text">No rows to show.</p>}</td>
              </tr>
            )}
            {!loading &&
              sections.map(section => [
                groups && (
                  <tr key={`g-${section.id}`} className="ui-tr-group">
                    <th colSpan={colCount} scope="colgroup">
                      <span className="ui-tr-group-title">{section.title}</span>
                      <span className="ui-tr-group-count numeric">{formatNumber(section.rows.length)}</span>
                      {section.description && <span className="ui-tr-group-desc">{section.description}</span>}
                    </th>
                  </tr>
                ),
                ...section.rows.map((row, i) => {
                  const key = rowKey(row)
                  const isSelected = selected?.has(key) ?? false
                  return (
                    <tr
                      key={key}
                      data-row={key}
                      data-flip-id={key}
                      tabIndex={interactive ? (key === tabbableKey ? 0 : -1) : undefined}
                      data-active={activeRowKey === key || undefined}
                      data-selected={isSelected || undefined}
                      onKeyDown={interactive ? e => onRowKey(e, row, key) : undefined}
                      onFocus={interactive ? e => e.target === e.currentTarget && setFocusKey(key) : undefined}
                      onClick={e => onRowClick(e, row)}
                    >
                      {selectable && (
                        <td className="ui-td-select">
                          <Checkbox aria-label="Select row" checked={isSelected} onChange={() => toggle(key)} tabIndex={-1} />
                        </td>
                      )}
                      {columns.map(c =>
                        c.primary ? (
                          <th key={c.key} scope="row" data-primary="" data-align={c.align}>
                            {c.cell(row, i)}
                          </th>
                        ) : (
                          <td key={c.key} data-align={c.numeric ? 'end' : c.align} data-numeric={c.numeric || undefined} data-hide-stacked={c.hideWhenStacked || undefined} data-meta-first={c.key === metaFirst || undefined}>
                            {c.cell(row, i)}
                          </td>
                        ),
                      )}
                    </tr>
                  )
                }),
              ])}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/** Per-viewer density preference (localStorage['table-density']). */
export function useTableDensity(fallback: Density = 'comfortable') {
  return useStoredState<Density>('table-density', fallback, ['comfortable', 'compact'])
}

/** Density toggle for a table toolbar (icon segments with accessible names). */
export function DensityToggle({ value, onChange }: { value: Density; onChange: (d: Density) => void }) {
  return (
    <SegmentedControl
      label="Row density"
      size="sm"
      value={value}
      onChange={onChange}
      options={[
        { value: 'comfortable', label: 'Comfortable', icon: Rows3, iconOnly: true },
        { value: 'compact', label: 'Compact', icon: Rows4, iconOnly: true },
      ]}
    />
  )
}
