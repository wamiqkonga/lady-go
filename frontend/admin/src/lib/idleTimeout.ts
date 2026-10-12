/**
 * Idle timeout (docs/product/admin-web-design.md section 7) - 15 minutes of no mouse/keyboard/touch
 * activity logs the admin out, independent of whether the access/refresh tokens are still valid.
 * This is a judgment call on the exact duration (not specified by name in the approved decisions,
 * only "idle timeout" as a requirement) - flagging it as a default worth confirming, same as the
 * other security-relevant defaults, rather than deciding it silently.
 */
const IDLE_TIMEOUT_MS = 15 * 60 * 1000
const ACTIVITY_EVENTS = ['mousemove', 'keydown', 'click', 'scroll', 'touchstart'] as const

export function startIdleTimeout(onIdle: () => void): () => void {
  let timer: ReturnType<typeof setTimeout>

  const reset = () => {
    clearTimeout(timer)
    timer = setTimeout(onIdle, IDLE_TIMEOUT_MS)
  }

  ACTIVITY_EVENTS.forEach((event) => window.addEventListener(event, reset, { passive: true }))
  reset()

  return () => {
    clearTimeout(timer)
    ACTIVITY_EVENTS.forEach((event) => window.removeEventListener(event, reset))
  }
}
