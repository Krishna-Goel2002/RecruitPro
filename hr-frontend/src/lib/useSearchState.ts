import { useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'

/**
 * Screen state kept in the URL (filters, page, tab), so Back/Forward and shared links restore the same view.
 * Setting any key other than `page` resets `page` to the first page.
 */
export function useSearchState() {
  const [params, setParams] = useSearchParams()

  const get = useCallback((key: string, fallback = '') => params.get(key) ?? fallback, [params])

  const getPage = useCallback(() => {
    const value = Number(params.get('page') ?? '0')
    return Number.isInteger(value) && value >= 0 ? value : 0
  }, [params])

  const set = useCallback((updates: Record<string, string | number | null | undefined>, options: { replace?: boolean } = {}) => {
    setParams(current => {
      const next = new URLSearchParams(current)
      for (const [key, value] of Object.entries(updates)) {
        if (value === null || value === undefined || value === '' || (key === 'page' && Number(value) === 0)) next.delete(key)
        else next.set(key, String(value))
      }
      if (!('page' in updates)) next.delete('page')
      return next
    }, { replace: options.replace ?? false })
  }, [setParams])

  return { params, get, getPage, set }
}
