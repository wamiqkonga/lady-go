import { API_BASE_URL } from '../config'

/**
 * Session model: both the access token AND the refresh token live in memory only, never
 * localStorage/sessionStorage/a cookie. This differs from frontend/admin's pattern (refresh token in
 * an HttpOnly cookie) because the customer/driver OTP endpoints this app calls return the refresh
 * token in the response body, not a cookie (that API is shared with the mobile apps, which have no
 * cookie jar in the same sense) - there is no cookie option to use here. The tradeoff: a page reload
 * loses the session entirely and requires a fresh OTP, since nothing durable is ever written. Already-
 * submitted application data (profile/vehicle/documents/declaration) is not lost on a reload - it
 * lives on the server and is re-fetched - only the "am I logged in right now" state is memory-only.
 */

let accessToken: string | null = null
let refreshToken: string | null = null
const listeners = new Set<(token: string | null) => void>()

function setTokens(access: string | null, refresh: string | null) {
  accessToken = access
  refreshToken = refresh
  listeners.forEach((listener) => listener(access))
}

export function getAccessToken(): string | null {
  return accessToken
}

export function onSessionChange(listener: (token: string | null) => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

type Envelope<T> = {
  success: boolean
  data: T | null
  error: { code: string; message: string } | null
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
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

/** One per browser session, not persisted - only used by the backend to distinguish refresh-token
 *  "families" for reuse detection; it carries no identity of its own. */
const deviceId = crypto.randomUUID()

export async function requestOtp(phone: string, challengeToken?: string): Promise<void> {
  await postJson<{ message: string }>('/api/v1/auth/otp/request', {
    phone, purpose: 'LOGIN', intendedRole: 'DRIVER', challengeToken,
  })
}

export async function verifyOtp(phone: string, code: string): Promise<void> {
  const data = await postJson<Record<string, unknown>>('/api/v1/auth/otp/verify', {
    phone, code, purpose: 'LOGIN', intendedRole: 'DRIVER', deviceId,
  })
  if (typeof data.accessToken === 'string' && typeof data.refreshToken === 'string') {
    setTokens(data.accessToken, data.refreshToken)
    scheduleProactiveRefresh()
    return
  }
  throw new AuthError('UNEXPECTED_RESPONSE', 'Login did not return tokens.')
}

export async function refresh(): Promise<boolean> {
  if (!refreshToken) return false
  try {
    const data = await postJson<{ accessToken: string; refreshToken: string }>('/api/v1/auth/refresh', {
      refreshToken,
    })
    setTokens(data.accessToken, data.refreshToken)
    return true
  } catch {
    setTokens(null, null)
    return false
  }
}

export async function logout(): Promise<void> {
  stopProactiveRefresh()
  const tokenToRevoke = refreshToken
  setTokens(null, null)
  if (tokenToRevoke) {
    try {
      await fetch(`${API_BASE_URL}/api/v1/auth/logout`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: tokenToRevoke, logoutAll: false }),
      })
    } catch {
      // Best-effort - tokens are already cleared client-side either way.
    }
  }
}

let refreshTimer: ReturnType<typeof setInterval> | null = null

/** Refreshes well before the access token's own TTL (10 minutes by default,
 *  access.token.ttl.minutes) - same reasoning as frontend/admin's scheduleProactiveRefresh. */
function scheduleProactiveRefresh() {
  stopProactiveRefresh()
  refreshTimer = setInterval(() => {
    refresh()
  }, 5 * 60 * 1000)
}

function stopProactiveRefresh() {
  if (refreshTimer !== null) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}
