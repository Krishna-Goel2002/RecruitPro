import { useCallback, useEffect } from 'react'
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

/** The URL value when it is one of `allowed`, else `fallback` (a hand-edited ?status=FOO never reaches the server). */
export function oneOf<T extends string>(value: string, allowed: readonly T[], fallback: T): T {
  return (allowed as readonly string[]).includes(value) ? (value as T) : fallback
}

/**
 * A page past the end (a hand-edited ?page=, or the last item of the last page just went away) jumps back to the last
 * page that has rows, instead of showing a misleading "nothing here yet" with no way back.
 */
export function useClampPage(state: ReturnType<typeof useSearchState>, page: number, data: { content: unknown[]; totalPages: number } | undefined) {
  const total = data?.totalPages
  const empty = data != null && data.content.length === 0
  useEffect(() => {
    if (!empty || page <= 0 || total == null) return
    state.set({ page: Math.max(0, total - 1) }, { replace: true })
  }, [empty, page, total, state])
}
