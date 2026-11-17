import { joinUrl } from '../design/lib/links'

/**
 * Absolute address of an in-app route for "Copy link": origin + this portal's base path (/hr behind the gateway, / on
 * its own port) + the router path. `window.location.origin + path` alone dropped the /hr prefix.
 */
export function absoluteAppUrl(routerPath: string, baseUrl: string = import.meta.env.BASE_URL, origin: string = window.location.origin): string {
  return `${origin}${joinUrl(baseUrl, routerPath)}`
}
