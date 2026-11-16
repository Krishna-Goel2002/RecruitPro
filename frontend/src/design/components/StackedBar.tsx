import { useRef } from 'react'
import { useGrowOnView } from '../motion'
import { cx } from './cx'

/** Six colour slots for proportions (accent ramp, success, two neutrals). Same order in the bar and its legend. */
export type StackedBarTone = 1 | 2 | 3 | 4 | 5 | 6

export interface StackedBarSegment {
  key: string
  label: string
  value: number
  /** Colour slot; defaults to the segment's position (1–6). */
  tone?: StackedBarTone
}

/**
 * Percentages (0–100) for each value, in order. All-zero or empty input gives all zeros. Negative and non-finite values
 * count as zero. The visible segments of a non-zero total always sum to exactly 100.
 */
export function stackedBarWidths(values: number[]): number[] {
  const clean = values.map(v => (Number.isFinite(v) && v > 0 ? v : 0))
  const total = clean.reduce((n, v) => n + v, 0)
  if (total <= 0) return clean.map(() => 0)
  const raw = clean.map(v => (v / total) * 100)
  // Give any floating-point remainder to the last non-zero segment, so the fill reaches the track's right end.
  const last = raw.map((w, i) => (w > 0 ? i : -1)).filter(i => i >= 0).pop()!
  const sumOthers = raw.reduce((n, w, i) => (i === last ? n : n + w), 0)
  raw[last] = 100 - sumOthers
  return raw
}

export interface StackedBarProps {
  segments: StackedBarSegment[]
  /** Accessible name; the bar is an image with a text alternative listing every non-zero share. */
  label: string
  /** Grow from the left on first view (default true; reduced motion: static). */
  animate?: boolean
  size?: 'sm' | 'md'
  className?: string
}

/**
 * Stacked proportion bar (HR-UX). The fill covers the track exactly: no inset at either end, a 100 % segment reaches the
 * right edge, only the outer corners are rounded (the track clips), and neighbouring segments are separated by a 1 px
 * hairline drawn inside the later segment (it takes no width). Zero segments are not drawn.
 */
export function StackedBar({ segments, label, animate = true, size = 'md', className }: StackedBarProps) {
  const fill = useRef<HTMLDivElement>(null)
  useGrowOnView(fill, animate)
  const widths = stackedBarWidths(segments.map(s => s.value))
  const total = segments.reduce((n, s) => n + (Number.isFinite(s.value) && s.value > 0 ? s.value : 0), 0)
  const visible = segments.map((s, i) => ({ s, i, w: widths[i] })).filter(x => x.w > 0)
  const text = total > 0 ? `${label}: ${visible.map(({ s, w }) => `${s.label} ${Math.round(w)}%`).join(', ')}` : `${label}: none yet`
  return (
    <div className={cx('ui-stackbar', className)} data-size={size} role="img" aria-label={text}>
      <div ref={fill} className="ui-stackbar-fill">
        {visible.map(({ s, i, w }, n) => (
          <span
            key={s.key}
            className="ui-stackbar-seg"
            data-tone={s.tone ?? ((i % 6) + 1)}
            data-first={n === 0 || undefined}
            data-last={n === visible.length - 1 || undefined}
            style={{ width: `${w}%` }}
            title={`${s.label}: ${Math.round(w)}%`}
          />
        ))}
      </div>
    </div>
  )
}

/** Legend swatch in the same colour slot as a StackedBar segment. */
export function StackedBarSwatch({ tone }: { tone: StackedBarTone }) {
  return <span className="ui-stackbar-swatch" data-tone={tone} aria-hidden="true" />
}
