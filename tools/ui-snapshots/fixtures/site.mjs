// Landing site fixtures (site/). The page calls no API, so there are no routes; every screen is anonymous.
const clickFaq = (n) => async (page) => {
  await page.evaluate((i) => document.querySelectorAll('.faq-btn')[i]?.click(), n)
  await new Promise((r) => setTimeout(r, 400))
}
const openMenu = async (page) => {
  await page.evaluate(() => document.querySelector('.hd-menu-btn')?.click())
  await new Promise((r) => setTimeout(r, 300))
}

export const routes = []
export const anonymousRoutes = []

export const screens = [
  { name: 'landing', path: '/', anonymous: true },
  { name: 'landing-faq-open', path: '/', anonymous: true, action: clickFaq(0) },
  // Phone menu (the button only shows below 1060 px; at 1440 the capture equals `landing`).
  { name: 'landing-menu', path: '/', anonymous: true, action: openMenu },
]
