import { API_BASE_URL } from '../config'

/**
 * Session model (docs/product/admin-web-design.md section 7): the access token lives in memory
 * only (a module-level variable, never localStorage/sessionStorage - gone on tab close or reload,
 * by design, so an XSS payload that runs later in the same session can still reach it, but nothing
 * persists it beyond that). The refresh token never reaches JS at all - it travels only in the
 * HttpOnly cookie the backend sets; this module never reads or stores it. The paired CSRF cookie is
 * deliberately NOT HttpOnly - this module reads it and echoes it back as a header, which is the
 * whole point of the double-submit pattern.
 */

const CSRF_COOKIE_NAME = 'ladygo_admin_csrf'
const CSRF_HEADER_NAME = 'X-CSRF-Token'

let accessToken: string | null = null
const listeners = new Set<(token: string | null) => void>()

function setAccessToken(token: string | null) {
  accessToken = token
  listeners.forEach((listener) => listener(token))
}

export function getAccessToken(): string | null {
  return accessToken
}

// E2E-only hook (VITE_E2E_EXPOSE_SESSION, never set in .env.example or any normal dev/build config -
// see docs/development/admin-web-manual-test.md's E2E section) so the Playwright suite can issue a
// directly-authenticated fetch for an endpoint with no UI yet (admin config), to prove the ADMIN-vs-
// SUPER_ADMIN role gate from inside a real logged-in browser session rather than via a raw backend
// curl call. Never exposes anything web-storage based - only this in-memory value, through a global
// that plain application code never reads.
if (import.meta.env.VITE_E2E_EXPOSE_SESSION) {
  ;(window as unknown as { __ladygoAccessToken?: () => string | null }).__ladygoAccessToken = getAccessToken
}

export function onSessionChange(listener: (token: string | null) => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function readCsrfCookie(): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${CSRF_COOKIE_NAME}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : null
}

type Envelope<T> = {
  success: boolean
  data: T | null
  error: { code: string; message: string } | null
}

async function postJson<T>(path: string, body?: unknown, csrfProtected = false): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (csrfProtected) {
    const csrf = readCsrfCookie()
    if (csrf) headers[CSRF_HEADER_NAME] = csrf
  }
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: 'POST',
    credentials: 'include', // required for the refresh/CSRF cookies to be sent and set at all
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const envelope = (await response.json().catch(() => null)) as Envelope<T> | null
  if (!response.ok || !envelope || !envelope.success) {
    throw new AuthError(envelope?.error?.code ?? 'UNKNOWN_ERROR', envelope?.error?.message ?? 'Request failed.')
  }
  return envelope.data as T
}

export class AuthError extends Error {
  readonly code: string
  constructor(code: string, message: string) {
    super(message)
    this.name = 'AuthError'
    this.code = code
  }
}

export type LoginResult =
  | { kind: 'authenticated' }
  | { kind: 'enrolmentRequired'; enrolmentToken: string; remainingSteps: string[] }

export async function login(email: string, password: string, totpCode?: string): Promise<LoginResult> {
  const data = await postJson<Record<string, unknown>>('/api/v1/auth/admin/login', {
    email,
    password,
    totpCode,
  })
  if (typeof data.accessToken === 'string') {
    setAccessToken(data.accessToken)
    scheduleProactiveRefresh()
    return { kind: 'authenticated' }
  }
  return {
    kind: 'enrolmentRequired',
    enrolmentToken: data.enrolmentToken as string,
    remainingSteps: (data.remainingSteps as string[]) ?? [],
  }
}

let bootstrapInFlight: Promise<boolean> | null = null

/** Silent session restore on app load - the refresh cookie may still be valid even though the
 *  in-memory access token is gone (a reload always clears memory). Failure here just means "not
 *  logged in", not an error worth surfacing.
 *
 *  Coalesces concurrent callers onto one in-flight request. React StrictMode (dev only) double-
 *  invokes mount effects, so SessionProvider's effect calling this on every mount fires it twice in
 *  quick succession; the refresh token rotates on every use (RefreshTokenService.rotate), so without
 *  this, the second call would present a cookie the first call already rotated out from under it,
 *  get rejected, and overwrite the access token the first call just successfully set - silently
 *  signing a just-restored session back out. Without coalescing this is a real race in any build,
 *  not just dev (StrictMode only changes how often it's hit); two genuinely concurrent callers -
 *  e.g. two tabs restoring at once - would hit the same rotation collision. */
export async function bootstrapSession(): Promise<boolean> {
  if (bootstrapInFlight) {
    return bootstrapInFlight
  }
  bootstrapInFlight = (async () => {
    try {
      const data = await postJson<{ accessToken: string }>('/api/v1/auth/admin/refresh', undefined, true)
      setAccessToken(data.accessToken)
      scheduleProactiveRefresh()
      return true
    } catch {
      setAccessToken(null)
      return false
    } finally {
      bootstrapInFlight = null
    }
  })()
  return bootstrapInFlight
}

export async function logout(): Promise<void> {
  stopProactiveRefresh()
  try {
    await postJson<void>('/api/v1/auth/admin/logout', undefined, true)
  } finally {
    setAccessToken(null)
  }
}

let refreshTimer: ReturnType<typeof setInterval> | null = null

/** Refreshes well before the access token's own TTL (10 minutes by default,
 *  admin.access.token.ttl.minutes) rather than waiting for a 401 and retrying - simpler to reason
 *  about correctly than request-level retry-after-refresh, at the cost of one extra network call
 *  per interval while the admin is actively using the console. */
function scheduleProactiveRefresh() {
  stopProactiveRefresh()
  refreshTimer = setInterval(() => {
    bootstrapSession()
  }, 5 * 60 * 1000)
}

function stopProactiveRefresh() {
  if (refreshTimer !== null) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}
