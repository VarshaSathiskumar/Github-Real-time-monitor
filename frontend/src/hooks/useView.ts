import { useCallback, useEffect, useState } from 'react'
import type { View } from '@/lib/types'

const read = (): View => (new URLSearchParams(window.location.search).get('view') === 'analytics' ? 'analytics' : 'graph')

/** Current screen, mirrored in the `?view=` query parameter so links and the back button work. */
export function useView(): [View, (v: View) => void] {
  const [view, setViewState] = useState<View>(read)

  useEffect(() => {
    const onPop = () => setViewState(read())
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  const setView = useCallback((next: View) => {
    setViewState(next)
    const url = new URL(window.location.href)
    if (next === 'graph') url.searchParams.delete('view')
    else url.searchParams.set('view', next)
    url.hash = ''
    window.history.pushState(null, '', url)
    window.scrollTo({ top: 0 })
  }, [])

  return [view, setView]
}
