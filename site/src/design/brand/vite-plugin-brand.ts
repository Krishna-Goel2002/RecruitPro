/*
 * Build-time brand plugin (DESIGN_SYSTEM §2). Used by both portals' vite.config.ts:
 *   plugins: [react(), brandPlugin({ portal: 'candidate' | 'staff' })]
 *
 * 1. Reads the preset named in ./active.ts (regex `presets/(\w+)`) and its tokens.json.
 * 2. index.html: sets <title> to the brand name (+ portal label), replaces %BRAND_NAME%, injects the two
 *    theme-color metas and <style id="brand-tokens"> (allowed by the CSP: style-src 'self' 'unsafe-inline').
 * 3. Contrast gate: the build FAILS when a preset's accent pairs miss WCAG AA in either theme.
 *
 * Runs in Node (vite.config), so it is excluded from the app's tsconfig (no @types/node in the browser build).
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Plugin } from 'vite'
import { checkBrandContrast } from './contrast'
import type { BrandThemeTokens, BrandTokens } from './types'

const here = dirname(fileURLToPath(import.meta.url))

export function readActivePreset(): { id: string; tokens: BrandTokens } {
  const active = readFileSync(join(here, 'active.ts'), 'utf8')
  const match = /^\s*export\s*\{\s*default\s*\}\s*from\s*['"]\.\/presets\/(\w+)['"]/m.exec(active)
  if (!match) throw new Error('[brand] src/design/brand/active.ts must contain `export { default } from \'./presets/<name>\'`')
  const id = match[1]
  const tokens = JSON.parse(readFileSync(join(here, 'presets', id, 'tokens.json'), 'utf8')) as BrandTokens
  return { id, tokens }
}

const kebab = (key: string) => key.replace(/[A-Z]/g, m => `-${m.toLowerCase()}`)

function themeDeclarations(t: BrandThemeTokens): string {
  return Object.entries(t)
    .map(([key, value]) => (key === 'onAccent' ? `--fg-on-accent:${value}` : `--${kebab(key)}:${value}`))
    .join(';')
}

export function brandCss(tokens: BrandTokens): string {
  const shared = [
    `--font-display:${tokens.fonts.display}`,
    `--font-display-weight:${tokens.fonts.displayWeight}`,
    `--font-display-tracking:${tokens.fonts.displayTracking}`,
    tokens.fonts.h2Display ? '--font-h2:var(--font-display)' : '',
    `--wordmark-transform:${tokens.wordmark.case}`,
    `--wordmark-tracking:${tokens.wordmark.tracking}`,
    tokens.linkUnderline ? '--link-decoration:underline' : '',
  ].filter(Boolean).join(';')
  const neutralLight = tokens.neutral ? Object.entries(tokens.neutral.light).map(([k, v]) => `;--${k}:${v}`).join('') : ''
  const neutralDark = tokens.neutral ? Object.entries(tokens.neutral.dark).map(([k, v]) => `;--${k}:${v}`).join('') : ''
  const light = `${themeDeclarations(tokens.light)}${neutralLight}`
  const dark = `${themeDeclarations(tokens.dark)}${neutralDark}`
  return (
    `:root{${shared}}` +
    `:root,[data-theme="light"]{${light}}` +
    `[data-theme="dark"]{${dark}}` +
    `@media (prefers-color-scheme: dark){:root:not([data-theme]){${dark}}}`
  )
}

export default function brandPlugin(options: { portal: 'candidate' | 'staff' }): Plugin {
  let preset = readActivePreset()
  return {
    name: 'design-brand',
    buildStart() {
      preset = readActivePreset()
      const failures = checkBrandContrast(preset.tokens).filter(c => !c.ok)
      if (failures.length) {
        const lines = failures.map(f => `  ${f.theme}: ${f.pair} = ${f.ratio}:1 (needs ${f.min}:1)`).join('\n')
        throw new Error(`[brand] preset "${preset.id}" fails the contrast gate:\n${lines}`)
      }
    },
    transformIndexHtml(html) {
      const t = preset.tokens
      const label = t.portalLabel[options.portal]
      const title = label ? `${t.name} ${label}` : t.name
      const light = t.neutral?.light['n-50'] ?? '#F5F6F8'
      const dark = t.neutral?.dark['d-0'] ?? '#0D0F12'
      const out = html
        .replace(/<title>[^<]*<\/title>/, `<title>${title}</title>`)
        .replace(/%BRAND_NAME%/g, t.name)
        .replace(/\s*<meta name="theme-color"[^>]*>/g, '')
      return {
        html: out,
        tags: [
          { tag: 'meta', attrs: { name: 'theme-color', media: '(prefers-color-scheme: light)', content: light }, injectTo: 'head' },
          { tag: 'meta', attrs: { name: 'theme-color', media: '(prefers-color-scheme: dark)', content: dark }, injectTo: 'head' },
          { tag: 'style', attrs: { id: 'brand-tokens' }, children: brandCss(t), injectTo: 'head' },
        ],
      }
    },
  }
}
