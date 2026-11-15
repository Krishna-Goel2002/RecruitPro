import { cx } from './cx'

/** 16 px indeterminate spinner (decorative; pair it with text or aria-busy). */
export function Spinner({ size = 16, className }: { size?: 12 | 14 | 16 | 20; className?: string }) {
  return (
    <svg className={cx('ui-spinner', className)} width={size} height={size} viewBox="0 0 16 16" aria-hidden="true" focusable="false">
      <circle cx="8" cy="8" r="6.25" fill="none" stroke="currentColor" strokeWidth="1.5" opacity="0.22" />
      <path d="M8 1.75a6.25 6.25 0 0 1 6.25 6.25" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}
