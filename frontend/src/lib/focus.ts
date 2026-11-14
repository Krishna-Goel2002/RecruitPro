/**
 * Moves focus to the page's <h1> (rendered by PageHeader with tabIndex -1). Used after an action removes the
 * control that had focus (withdraw, delete, start/submit), so keyboard and screen-reader users are not dropped
 * onto <body>.
 */
export function focusPageHeading(): void {
  window.setTimeout(() => {
    const active = document.activeElement
    if (active && active !== document.body && document.contains(active)) return
    document.querySelector<HTMLElement>('[data-page-heading]')?.focus()
  }, 0)
}
