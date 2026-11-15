import type { MarkProps } from '../../types'

const STEPS = [
  { x: 2, y: 26 },
  { x: 8, y: 20 },
  { x: 14, y: 14 },
  { x: 20, y: 8 },
  { x: 26, y: 2 },
]

/**
 * Gradus "five steps" (NAMING.md §4.3): the tier scale itself climbing a diagonal. Step 1 is hollow (self-claimed).
 * At 16 px or less only steps 1, 3 and 5 are drawn, with a pitch of 10.
 */
export default function Mark({ size = 20, className, title }: MarkProps) {
  const small = size <= 16
  const steps = small ? [0, 2, 4] : [0, 1, 2, 3, 4]
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
      {steps.map((i, n) => {
        const p = small ? { x: 2 + n * 10.5, y: 23 - n * 10.5 } : STEPS[i]
        const s = small ? 7 : 5
        return i === 0 ? (
          <rect key={i} x={p.x + 0.75} y={p.y + 0.75} width={s - 1.5} height={s - 1.5} rx={1} fill="none" stroke="var(--mark-step1)" strokeWidth="1.5" />
        ) : (
          <rect key={i} x={p.x} y={p.y} width={s} height={s} rx={1.25} fill={`var(--mark-step${i + 1})`} />
        )
      })}
    </svg>
  )
}
