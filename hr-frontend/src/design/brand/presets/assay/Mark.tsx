import type { MarkProps } from '../../types'

/** Assay "touchstone streak" (NAMING.md §4.2): a graphite stone with one gold streak rising at about 42.5°. */
export default function Mark({ size = 20, className, title }: MarkProps) {
  const small = size <= 16
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 32 32"
      role={title ? 'img' : undefined}
      aria-hidden={title ? undefined : true}
      aria-label={title}
      focusable="false"
    >
      <rect x="3.5" y="3.5" width="25" height="25" rx={small ? 5 : 6} fill="var(--mark-ink)" stroke="var(--mark-edge)" strokeWidth="1" />
      <line x1="10" y1="21.5" x2="22" y2="10.5" stroke="var(--mark-accent)" strokeWidth={small ? 4 : 3.5} strokeLinecap="round" />
    </svg>
  )
}
