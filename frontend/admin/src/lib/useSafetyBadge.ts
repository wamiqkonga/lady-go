import { useEffect, useState } from 'react'
import { client, unwrap } from './client'

const REFRESH_MS = 30_000
const REFRESH_EVENT = 'ladygo:safety-badge-refresh'

export type SafetyCounts = { unresolved: number; needsAcknowledging: number; critical: number; oldestCriticalAt?: string }

/** Ask the navigation badge and the SOS banner to re-read the open count now (after acknowledging, noting or resolving). */
export function refreshSafetyBadge(): void {
  window.dispatchEvent(new Event(REFRESH_EVENT))
}

/** The unresolved safety counts for the navigation badge and SOS banner. Silent on failure: a badge must never break the shell. */
export function useSafetyBadge(): SafetyCounts | null {
  const [counts, setCounts] = useState<SafetyCounts | null>(null)

  useEffect(() => {
    let cancelled = false
    async function load(force = false) {
      if (!force && document.visibilityState === 'hidden') return
      try {
        const data = unwrap(await client.GET('/api/v1/admin/safety/incidents/open-count'))
        if (!cancelled) {
          setCounts({
            unresolved: data.unresolved ?? 0,
            needsAcknowledging: data.needsAcknowledging ?? 0,
            critical: data.critical ?? 0,
            oldestCriticalAt: data.oldestCriticalAt,
          })
        }
      } catch {
        /* keep the last value */
      }
    }
    const onRefresh = () => void load(true)
    void load()
    const timer = window.setInterval(() => void load(), REFRESH_MS)
    window.addEventListener(REFRESH_EVENT, onRefresh)
    return () => {
      cancelled = true
      window.clearInterval(timer)
      window.removeEventListener(REFRESH_EVENT, onRefresh)
    }
  }, [])

  return counts
}
