import type { ComponentType } from 'react'

/** Colour tokens a preset sets per theme. Keys become CSS custom properties (see vite-plugin-brand.ts). */
export interface BrandThemeTokens {
  accent: string
  accentHover: string
  accentActive: string
  accentSubtle: string
  accentSubtleBorder: string
  accentText: string
  onAccent: string
  tierFill: string
  /** Extra mark colours (markInk, markAccent, markStep1…); exposed as --mark-ink, --mark-accent, … */
  [mark: `mark${string}`]: string
}

/** The pure-data part of a preset (tokens.json). Read by the app and by the build-time Vite plugin. */
export interface BrandTokens {
  id: string
  name: string
  shortName: string
  portalLabel: { candidate: string; staff: string }
  tagline: string
  wordmark: { case: 'none' | 'uppercase' | 'lowercase'; tracking: string }
  fonts: { display: string; displayWeight: number; displayTracking: string; h2Display: boolean }
  /** Links always underlined (for presets whose accent is not a hue, e.g. Assay). */
  linkUnderline: boolean
  light: BrandThemeTokens
  dark: BrandThemeTokens
  /** Optional full override of the neutral ramps: { light: { 'n-0': '#…', … }, dark: { 'd-0': '#…', … } }. */
  neutral: null | { light: Record<string, string>; dark: Record<string, string> }
}

export interface MarkProps {
  /** Rendered size in px (square). Default 20. */
  size?: number
  className?: string
  /** Accessible name. Omit when a visible wordmark sits next to the mark (it is then decorative). */
  title?: string
}

export interface Brand extends BrandTokens {
  Mark: ComponentType<MarkProps>
}
