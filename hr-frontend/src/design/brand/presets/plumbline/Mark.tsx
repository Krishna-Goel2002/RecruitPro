import type { MarkProps } from '../../types'

/**
 * Plumbline "line and bob" (NAMING.md §4.1): anchor bar and line in ink, an inverted-triangle bob in brass.
 * At 16 px or less the line thickens, the bar grows and the bob loses its soft stroke.
 */
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
      <rect x="8" y="3" width="16" height={small ? 3.5 : 3} rx={small ? 1.75 : 1.5} fill="var(--mark-ink)" />
      <line x1="16" y1="6" x2="16" y2="19" stroke="var(--mark-ink)" strokeWidth={small ? 2.5 : 2} strokeLinecap="butt" />
      <path
        d="M16 29.5 L10.5 19 H21.5 Z"
        fill="var(--mark-accent)"
        stroke={small ? 'none' : 'var(--mark-accent)'}
        strokeWidth="1"
        strokeLinejoin="round"
      />
    </svg>
  )
}
