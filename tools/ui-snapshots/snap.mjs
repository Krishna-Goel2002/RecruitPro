#!/usr/bin/env node
// Screenshot harness for both portals: serves a built `dist/`, answers every `/api/v1/**` call from fixtures,
// and captures each screen at desktop and phone widths, in light and/or dark theme.
//
//   node snap.mjs --portal candidate --dist ../../frontend/dist --out out/candidate
//   node snap.mjs --portal hr --dist ../../hr-frontend/dist --out out/hr --theme both --only applicant-revealed,jobs
//
// Flags
//   --portal candidate|hr       which fixture set (required)
//   --dist <dir>                built portal (pnpm build) (required)
//   --out <dir>                 where PNGs go (default out/<portal>)
//   --theme light|dark|both     emulated prefers-color-scheme + localStorage['theme-preference'] (default light)
//   --widths 1440,390           viewport widths; heights are 900 (desktop) / 844 (phone)
//   --only a,b                  screen names from fixtures/<portal>.mjs
//   --full                      full-page screenshots (default: full page)  --viewport  viewport only
//   --motion                    do NOT emulate prefers-reduced-motion (default: reduced, for stable pixels)
//   --api-prefix /api/v1        path prefix the bundle calls (any origin)
//   --axe                       run axe-core WCAG 2.1 A/AA rules on every capture (needs `npm i axe-core`)
//
// Exit code 1 when any capture crashed (blank #root or page error), overflows horizontally, or (with --axe) has a
// serious/critical axe violation. Details: <out>/report.json.
//
// Browser: uses CHROME_PATH if set (any local Chrome/Chromium/Edge), else @sparticuz/chromium (Linux sandboxes).
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

const here = path.dirname(fileURLToPath(import.meta.url))
// A delayed fixture may try to answer after its page was closed (loading-state captures): ignore exactly that.
process.on('unhandledRejection', (err) => {
  if (/Target closed|Session closed|detached|Request is already handled/i.test(String(err?.message ?? err))) return
  console.error(err)
  process.exitCode = 1
})
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, cur, i, all) => {
  if (cur.startsWith('--')) acc.push([cur.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : true])
  return acc
}, []))

const portal = args.portal
if (!['candidate', 'hr'].includes(portal) || !args.dist) {
  console.error('usage: node snap.mjs --portal candidate|hr --dist <built dist dir> [--out dir] [--theme light|dark|both] [--only a,b]')
  process.exit(2)
}
const dist = path.resolve(args.dist)
const outDir = path.resolve(args.out ?? path.join(here, 'out', portal))
const themes = args.theme === 'both' ? ['light', 'dark'] : [args.theme === 'dark' ? 'dark' : 'light']
const widths = String(args.widths ?? '1440,390').split(',').map(Number)
const only = args.only ? new Set(String(args.only).split(',')) : null
const fullPage = !args.viewport
const apiPrefix = args['api-prefix'] ?? '/api/v1'
const THEME_KEY = 'theme-preference' // must match src/design/theme.ts in both portals

const baseFixtures = await import(path.join(here, 'fixtures', `${portal}.mjs`))
// Extension fixture files (fixtures/<portal>-<part>.mjs, e.g. hr-b.mjs) add screens and routes without editing the
// shared file. Their `routes({ role })` answers are tried before the base routes.
const extensions = []
for (const file of fs.readdirSync(path.join(here, 'fixtures')).sort()) {
  if (file.startsWith(`${portal}-`) && file.endsWith('.mjs')) extensions.push(await import(path.join(here, 'fixtures', file)))
}
const fixtures = {
  ...baseFixtures,
  screens: [...baseFixtures.screens, ...extensions.flatMap((e) => e.screens ?? [])],
  routes: (opts) => [
    ...extensions.flatMap((e) => (typeof e.routes === 'function' ? e.routes(opts) : e.routes ?? [])),
    ...(typeof baseFixtures.routes === 'function' ? baseFixtures.routes(opts) : baseFixtures.routes),
  ],
}
fs.mkdirSync(outDir, { recursive: true })

// ---------------------------------------------------------------- static server with SPA fallback
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.woff': 'font/woff', '.json': 'application/json', '.ico': 'image/x-icon' }
const server = http.createServer((reqst, res) => {
  const urlPath = decodeURIComponent(new URL(reqst.url, 'http://x').pathname)
  let file = path.join(dist, urlPath)
  if (!file.startsWith(dist) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(dist, 'index.html')
  res.writeHead(200, { 'Content-Type': types[path.extname(file)] ?? 'application/octet-stream' })
  fs.createReadStream(file).pipe(res)
})
await new Promise((r) => server.listen(0, '127.0.0.1', r))
const origin = `http://127.0.0.1:${server.address().port}`

// ---------------------------------------------------------------- browser
async function launch() {
  if (process.env.CHROME_PATH) return puppeteer.launch({ executablePath: process.env.CHROME_PATH, headless: true, args: ['--no-sandbox'] })
  const chromium = (await import('@sparticuz/chromium')).default
  return puppeteer.launch({ args: chromium.args, executablePath: await chromium.executablePath(), headless: true })
}
const browser = await launch()

function cors(reqOrigin) {
  return {
    'Access-Control-Allow-Origin': reqOrigin || origin,
    'Access-Control-Allow-Credentials': 'true',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization, Accept, X-RecruitPro-Portal',
    'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
    'Access-Control-Expose-Headers': 'Content-Disposition',
  }
}

const unmatched = new Set()

async function capture(screen, theme, width) {
  const page = await browser.newPage()
  const height = width < 700 ? 844 : 900
  await page.setViewport({ width, height, deviceScaleFactor: 1, isMobile: width < 700, hasTouch: width < 700 })
  await page.emulateMediaFeatures([
    { name: 'prefers-color-scheme', value: theme },
    { name: 'prefers-reduced-motion', value: args.motion ? 'no-preference' : 'reduce' },
  ])
  await page.evaluateOnNewDocument((key, value) => { try { localStorage.setItem(key, value) } catch { /* ignore */ } }, THEME_KEY, theme)

  const base = screen.anonymous ? fixtures.anonymousRoutes
    : typeof fixtures.routes === 'function' ? fixtures.routes({ role: screen.role }) : fixtures.routes
  const table = [...(screen.override ?? []), ...base]

  await page.setRequestInterception(true)
  page.on('request', async (request) => {
    const url = new URL(request.url())
    const idx = url.pathname.indexOf(apiPrefix + '/')
    if (idx === -1) {
      if (url.origin === origin || url.protocol === 'data:' || url.protocol === 'blob:') return request.continue()
      return request.abort() // no third-party requests: the CSP forbids them anyway
    }
    const headers = cors(request.headers().origin)
    if (request.method() === 'OPTIONS') return request.respond({ status: 204, headers })
    const apiPath = url.pathname.slice(idx + apiPrefix.length)
    let body = null
    try { body = request.postData() ? JSON.parse(request.postData()) : null } catch { body = null }
    for (const [method, re, handler] of table) {
      const match = method === request.method() && apiPath.match(re)
      if (!match) continue
      let result = await handler({ method, path: apiPath, query: url.searchParams, body, match })
      if (result?.__delay) {
        const { __delay, __value } = result
        await new Promise((r) => setTimeout(r, __delay))
        result = __value
      }
      const status = result?.__status ?? 200
      const payload = result?.__body ?? { success: true, message: null, data: result ?? null, timestamp: new Date().toISOString() }
      return request.respond({ status, headers: { ...headers, ...(result?.__headers ?? {}) }, contentType: 'application/json', body: JSON.stringify(payload) })
    }
    unmatched.add(`${request.method()} ${apiPath}`)
    return request.respond({ status: 404, headers, contentType: 'application/json', body: JSON.stringify({ success: false, message: `No fixture for ${request.method()} ${apiPath}` }) })
  })
  const errors = []
  page.on('pageerror', (err) => { errors.push(err.message); console.warn(`  [${screen.name}] page error: ${err.message}`) })

  // Screen options: waitUntil ('networkidle0' default; 'domcontentloaded' to catch loading states), settle (ms, default 500).
  await page.goto(origin + screen.path, { waitUntil: screen.waitUntil ?? 'networkidle0', timeout: 30000 }).catch((e) => console.warn(`  [${screen.name}] ${e.message}`))
  await new Promise((r) => setTimeout(r, screen.settle ?? 500))
  if (screen.action) await screen.action(page)
  // Layout checks: horizontal overflow, elements poking past the viewport, empty render (crash = blank #root).
  const checks = await page.evaluate(() => {
    const vw = window.innerWidth
    const overflowX = document.documentElement.scrollWidth - vw
    const offenders = []
    const inScroller = (el) => {
      for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
        if (['auto', 'scroll', 'hidden', 'clip'].includes(getComputedStyle(p).overflowX)) return true
      }
      return false
    }
    for (const el of document.querySelectorAll('body *')) {
      const r = el.getBoundingClientRect()
      if (r.width && r.right > vw + 1 && getComputedStyle(el).position !== 'fixed' && !inScroller(el)) {
        const name = el.tagName.toLowerCase() + (el.id ? `#${el.id}` : '') + (el.className && typeof el.className === 'string' ? `.${el.className.trim().split(/\s+/).slice(0, 2).join('.')}` : '')
        offenders.push(`${name} (+${Math.round(r.right - vw)}px)`)
        if (offenders.length >= 5) break
      }
    }
    const rootText = (document.getElementById('root')?.innerText ?? '').trim().length
    return { overflowX, offenders, blank: rootText === 0 }
  })
  // Optional WCAG 2.1 A/AA scan with axe-core (npm i axe-core). Recorded in report.json, summarised on stdout.
  let axe = null
  if (args.axe) {
    const axePath = path.join(here, 'node_modules', 'axe-core', 'axe.min.js')
    if (fs.existsSync(axePath)) {
      await page.addScriptTag({ path: axePath })
      axe = await page.evaluate(async () => {
        const r = await window.axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] } })
        return r.violations.map((v) => ({ id: v.id, impact: v.impact, count: v.nodes.length, sample: v.nodes.slice(0, 3).map((n) => n.target.join(' ')) }))
      })
      if (axe.length) console.warn(`  [${screen.name}@${width}/${theme}] axe: ${axe.map((v) => `${v.id}×${v.count}`).join(', ')}`)
    } else if (!globalThis.__axeWarned) { globalThis.__axeWarned = true; console.warn('  --axe given but axe-core is not installed (npm i axe-core)') }
  }
  checks.axe = axe
  const file = path.join(outDir, `${screen.name}--${theme}--${width}.png`)
  await page.screenshot({ path: file, fullPage })
  await page.close()
  report.push({ screen: screen.name, theme, width, file: path.basename(file), errors, ...checks })
  if (checks.blank) console.warn(`  [${screen.name}@${width}] BLANK PAGE (render crashed?)`)
  if (checks.overflowX > 0 || checks.offenders.length) console.warn(`  [${screen.name}@${width}] overflow ${checks.overflowX}px: ${checks.offenders.join(', ')}`)
  return file
}
const report = []

let count = 0
for (const screen of fixtures.screens) {
  if (only && !only.has(screen.name)) continue
  for (const theme of themes) {
    for (const width of widths) {
      const file = await capture(screen, theme, width)
      count += 1
      console.log(`  ${path.relative(process.cwd(), file)}`)
    }
  }
}
await browser.close()
server.close()
if (unmatched.size) console.warn(`\nUnanswered API calls (add fixtures):\n  ${[...unmatched].sort().join('\n  ')}`)
fs.writeFileSync(path.join(outDir, 'report.json'), JSON.stringify({ portal, unmatched: [...unmatched].sort(), screens: report }, null, 2))
const failed = report.filter((r) => r.blank || r.errors.length || r.overflowX > 0 || (r.axe && r.axe.some((v) => ['serious', 'critical'].includes(v.impact))))
console.log(`\n${count} screenshots → ${outDir}  (report.json: ${failed.length} screen(s) with errors, blank render or horizontal overflow)`)
process.exitCode = failed.length ? 1 : 0
