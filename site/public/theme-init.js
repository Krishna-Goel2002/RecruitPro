/* Applies the saved theme before first paint (DESIGN_SYSTEM §10). Loaded as the first <script> in <head>;
   an external file because the CSP forbids inline scripts (script-src 'self'). Identical in both portals. */
(function () {
  var p = 'system'
  try { p = localStorage.getItem('theme-preference') || 'system' } catch (e) {}
  var dark = p === 'dark' || (p !== 'light' && window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches)
  var d = document.documentElement
  d.setAttribute('data-theme', dark ? 'dark' : 'light')
  d.style.colorScheme = dark ? 'dark' : 'light'
})()
