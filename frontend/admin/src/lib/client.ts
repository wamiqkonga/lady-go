import createClient, { type Middleware } from 'openapi-fetch'
import type { paths } from './api-types'
import { API_BASE_URL } from '../config'
import { getAccessToken } from './auth'

const authMiddleware: Middleware = {
  async onRequest({ request }) {
    const token = getAccessToken()
    if (token) request.headers.set('Authorization', `Bearer ${token}`)
    return request
  },
}

/**
 * springdoc represents Spring's `Pageable` as one object-shaped query param named `pageable`
 * (for documentation purposes), but Spring's actual binding expects flat `page`/`size`/`sort`
 * params - openapi-fetch's default serializer would otherwise send a nested/bracketed form Spring
 * doesn't understand. Flattens that one param; everything else passes through as a plain `key=value`.
 */
export function querySerializer(query: Record<string, unknown>): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null) continue
    if (key === 'pageable' && typeof value === 'object') {
      const pageable = value as { page?: number; size?: number; sort?: string[] }
      if (pageable.page !== undefined) params.set('page', String(pageable.page))
      if (pageable.size !== undefined) params.set('size', String(pageable.size))
      pageable.sort?.forEach((s) => params.append('sort', s))
    } else {
      params.set(key, String(value))
    }
  }
  return params.toString()
}

export const client = createClient<paths>({
  baseUrl: API_BASE_URL,
  querySerializer,
  // openapi-fetch captures its `fetch` option ONCE, as a default-parameter value, at createClient()
  // time - since this client is a module-level singleton, that means binding to globalThis.fetch as
  // it existed at import time. Wrapping it keeps the lookup dynamic, so a test's vi.stubGlobal('fetch',
  // ...) - which runs long after this module was first imported - is actually honored.
  fetch: (...args: Parameters<typeof fetch>) => globalThis.fetch(...args),
})
client.use(authMiddleware)

export class ApiCallError extends Error {
  readonly code: string
  readonly status?: number
  constructor(code: string, message: string, status?: number) {
    super(message)
    this.name = 'ApiCallError'
    this.code = code
    this.status = status
  }
}

type Envelope<T> = {
  data?: T | null
  error?: { code?: string; message?: string } | null
}

/**
 * Every endpoint returns the standard envelope ({@code ApiResponse<T>}) - but openapi-fetch's own
 * `data`/`error` split is HTTP-status-based, not envelope-based: on 2xx it parses the body into
 * `result.data` (the full envelope, including its own nested `.data`); on non-2xx it parses the SAME
 * kind of body into `result.error` instead (untyped, since the OpenAPI spec only declares a 200
 * response) and leaves `result.data` undefined. Both cases have to be unwrapped here, or every
 * actual error response (401/403/404/400/...) falls through to the generic "Request failed."
 * fallback instead of the backend's real code/message.
 */
export function unwrap<T>(result: { data?: Envelope<T>; error?: Envelope<T>; response: Response }): T {
  if (result.data?.data !== undefined && result.data.data !== null) {
    return result.data.data
  }
  const error = result.data?.error ?? result.error?.error
  throw new ApiCallError(error?.code ?? 'UNKNOWN_ERROR', error?.message ?? 'Request failed.', result.response.status)
}
