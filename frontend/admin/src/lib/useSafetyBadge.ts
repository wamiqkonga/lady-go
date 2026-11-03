import { useEffect, useState } from 'react'
import { client, unwrap } from './client'

const REFRESH_MS = 30_000

/** The number of unresolved safety incidents for the navigation badge. Silent on failure: a badge must never break the shell. */
export function useSafetyBadge(): { unresolved: number; needsAcknowledging: number; critical: number } | null {
  const [counts, setCounts] = useState<{ unresolved: number; needsAcknowledging: number; critical: number } | null>(null)

  useEffect(() => {
    let cancelled = false
    async function load() {
      if (document.visibilityState === 'hidden') return
      try {
        const data = unwrap(await client.GET('/api/v1/admin/safety/incidents/open-count'))
        if (!cancelled) {
          setCounts({
            unresolved: data.unresolved ?? 0,
            needsAcknowledging: data.needsAcknowledging ?? 0,
            critical: data.critical ?? 0,
          })
        }
      } catch {
        /* keep the last value */
      }
    }
    void load()
    const timer = window.setInterval(load, REFRESH_MS)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [])

  return counts
}
