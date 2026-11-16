// Landing site fixtures (site/). The page calls no API, so there are no routes; every screen is anonymous.
const clickFaq = (n) => async (page) => {
  await page.evaluate((i) => document.querySelectorAll('.faq-btn')[i]?.click(), n)
  await new Promise((r) => setTimeout(r, 400))
}
const openMenu = async (page) => {
  await page.evaluate(() => document.querySelector('.hd-menu-btn')?.click())
  await new Promise((r) => setTimeout(r, 300))
}

// Scrolls a section to the top of the viewport. For phone captures use these with --viewport: full-page captures taller
// than about 8,192 px repeat the top of the page (README, known limitation), and the 390 px page is about 10,900 px.
const scrollToId = (id) => async (page) => {
  await page.evaluate((i) => document.getElementById(i)?.scrollIntoView({ block: 'start' }), id)
  await new Promise((r) => setTimeout(r, 500))
}

export const routes = []
export const anonymousRoutes = []

export const screens = [
  { name: 'landing', path: '/', anonymous: true },
  // Unknown address: a 404 with a way home instead of the landing page under the wrong URL.
  { name: 'not-found', path: '/pricing', anonymous: true },
  { name: 'landing-faq-open', path: '/', anonymous: true, action: clickFaq(0) },
  // Phone menu (the button only shows below 1060 px; at 1440 the capture equals `landing`).
  { name: 'landing-menu', path: '/', anonymous: true, action: openMenu },
  // Lower sections, for --viewport captures on phones.
  { name: 'landing-at-trust', path: '/', anonymous: true, action: scrollToId('trust') },
  { name: 'landing-at-faq', path: '/', anonymous: true, action: async (p) => { await scrollToId('faq')(p); await clickFaq(1)(p) } },
  { name: 'landing-at-footer', path: '/', anonymous: true, action: async (p) => { await p.evaluate(() => window.scrollTo(0, document.body.scrollHeight)); await new Promise((r) => setTimeout(r, 500)) } },
]
