import type { MarkProps } from '../../types'

/**
 * Neutral working mark (DESIGN_SYSTEM §2): a 20×20 square in the default ink holding three rising rungs
 * (heights 5 / 8 / 11, width 3, gap 2) cut out in the surface colour. Deliberately plain.
 */
export default function Mark({ size = 20, className, title }: MarkProps) {
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 20 20"
      role={title ? 'img' : undefined}
      aria-hidden={title ? undefined : true}
      aria-label={title}
      focusable="false"
    >
      <rect width="20" height="20" rx="3" fill="var(--mark-ink, var(--fg-default))" />
      <g fill="var(--mark-accent, var(--bg-surface))">
        <rect x="3" y="11" width="3" height="5" rx="0.5" />
        <rect x="8" y="8" width="3" height="8" rx="0.5" />
        <rect x="13" y="5" width="3" height="11" rx="0.5" />
      </g>
    </svg>
  )
}
