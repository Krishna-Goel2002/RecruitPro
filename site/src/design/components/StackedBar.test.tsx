import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StackedBar, stackedBarWidths } from './StackedBar'

const segs = (values: number[]) => values.map((value, i) => ({ key: `k${i}`, label: `S${i}`, value }))
const widths = (el: HTMLElement) => [...el.querySelectorAll<HTMLElement>('.ui-stackbar-seg')].map(s => parseFloat(s.style.width))

describe('stackedBarWidths', () => {
  it('all zero (or empty) gives zero widths', () => {
    expect(stackedBarWidths([0, 0, 0])).toEqual([0, 0, 0])
    expect(stackedBarWidths([])).toEqual([])
  })

  it('a single non-zero value is exactly 100 %', () => {
    expect(stackedBarWidths([0, 7, 0])).toEqual([0, 100, 0])
  })

  it('mixed values are proportional and sum to exactly 100', () => {
    const w = stackedBarWidths([71, 22, 9, 4, 24, 4])
    expect(w.reduce((a, b) => a + b, 0)).toBe(100)
    expect(w[0]).toBeCloseTo(52.985, 2)
    expect(w[3]).toBeCloseTo(2.985, 2)
  })

  it('thirds still sum to 100 (rounding goes to the last segment)', () => {
    const w = stackedBarWidths([1, 1, 1])
    expect(w.reduce((a, b) => a + b, 0)).toBe(100)
  })

  it('ignores negative and non-finite values', () => {
    expect(stackedBarWidths([-5, Number.NaN, 10])).toEqual([0, 0, 100])
  })
})

describe('<StackedBar>', () => {
  it('0 %: draws no segments, only the track, and says so', () => {
    const { container, getByRole } = render(<StackedBar label="Applications" segments={segs([0, 0])} />)
    expect(container.querySelectorAll('.ui-stackbar-seg')).toHaveLength(0)
    expect(getByRole('img')).toHaveAccessibleName('Applications: none yet')
  })

  it('100 %: one segment, first and last, full width (reaches the right end)', () => {
    const { container } = render(<StackedBar label="Jobs" segments={segs([0, 5, 0])} />)
    const seg = container.querySelectorAll<HTMLElement>('.ui-stackbar-seg')
    expect(seg).toHaveLength(1)
    expect(seg[0].style.width).toBe('100%')
    expect(seg[0]).toHaveAttribute('data-first')
    expect(seg[0]).toHaveAttribute('data-last')
  })

  it('mixed: zero segments are skipped (no left inset), widths sum to 100, the first starts at the left edge', () => {
    const { container, getByRole } = render(<StackedBar label="Applications by status" segments={segs([0, 3, 1, 0])} />)
    const seg = container.querySelectorAll<HTMLElement>('.ui-stackbar-seg')
    expect(seg).toHaveLength(2)
    expect(widths(container as HTMLElement)).toEqual([75, 25])
    // No gap or margin on the fill or the segments: the hairline is an inset shadow drawn by CSS.
    const fill = container.querySelector<HTMLElement>('.ui-stackbar-fill')!
    expect(fill.style.gap).toBe('')
    expect(seg[0].style.marginLeft).toBe('')
    expect(seg[0]).toHaveAttribute('data-first')
    expect(seg[1]).toHaveAttribute('data-last')
    expect(seg[1]).not.toHaveAttribute('data-first')
    expect(getByRole('img')).toHaveAccessibleName('Applications by status: S1 75%, S2 25%')
  })

  it('keeps colour slots by position, so the legend matches even when zeros are skipped', () => {
    const { container } = render(<StackedBar label="x" segments={segs([0, 3, 1])} />)
    const tones = [...container.querySelectorAll<HTMLElement>('.ui-stackbar-seg')].map(s => s.dataset.tone)
    expect(tones).toEqual(['2', '3'])
  })
})
