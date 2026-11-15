/**
 * WCAG 2.x contrast helpers. Pure functions with no DOM or Node dependency, shared by the Vite brand plugin
 * (build-time gate) and the /__design gallery (live readout).
 */
import type { BrandTokens } from './types'

export function luminance(hex: string): number {
  const h = hex.replace('#', '')
  const full = h.length === 3 ? h.split('').map(c => c + c).join('') : h.slice(0, 6)
  const [r, g, b] = [0, 2, 4].map(i => parseInt(full.slice(i, i + 2), 16) / 255)
  const lin = (c: number) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
}

export function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/** Default surfaces from tokens.css (light n-0 / n-50, dark d-50 / d-0); a preset's neutral override replaces them. */
export function surfacesOf(tokens: BrandTokens) {
  return {
    light: { surface: tokens.neutral?.light['n-0'] ?? '#FFFFFF', canvas: tokens.neutral?.light['n-50'] ?? '#F5F6F8' },
    dark: { surface: tokens.neutral?.dark['d-50'] ?? '#14171B', canvas: tokens.neutral?.dark['d-0'] ?? '#0D0F12' },
  }
}

export interface ContrastCheck {
  theme: 'light' | 'dark'
  pair: string
  ratio: number
  min: number
  ok: boolean
}

/** The gate from DESIGN_SYSTEM §2.4, plus the selected-nav pair and the primary hover state. */
export function checkBrandContrast(tokens: BrandTokens): ContrastCheck[] {
  const out: ContrastCheck[] = []
  const surfaces = surfacesOf(tokens)
  for (const theme of ['light', 'dark'] as const) {
    const t = tokens[theme]
    const { surface, canvas } = surfaces[theme]
    const add = (pair: string, a: string, b: string, min: number) => {
      const ratio = Math.round(contrast(a, b) * 100) / 100
      out.push({ theme, pair, ratio, min, ok: ratio >= min })
    }
    add('accentText on surface', t.accentText, surface, 4.5)
    add('accentText on canvas', t.accentText, canvas, 4.5)
    add('accentText on accentSubtle', t.accentText, t.accentSubtle, 4.5)
    add('onAccent on accent', t.onAccent, t.accent, 4.5)
    add('onAccent on accentHover', t.onAccent, t.accentHover, 4.5)
    add('accent on surface', t.accent, surface, 3)
    add('tierFill on surface', t.tierFill, surface, 3)
  }
  return out
}
