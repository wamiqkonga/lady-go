import createClient, { type Middleware } from 'openapi-fetch'
import type { paths } from './api-types'
import { API_BASE_URL } from '../../config'
import { getAccessToken } from './auth'

const authMiddleware: Middleware = {
  async onRequest({ request }) {
    const token = getAccessToken()
    if (token) request.headers.set('Authorization', `Bearer ${token}`)
    return request
  },
}

/** Same flattening as frontend/admin/src/lib/client.ts - springdoc represents Spring's `Pageable` as
 *  one object-shaped query param, but Spring's actual binding expects flat `page`/`size`/`sort`. */
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

/** Same envelope-unwrap shape as frontend/admin/src/lib/client.ts - see that file's comment for why
 *  both `result.data.error` and `result.error.error` have to be checked. */
export function unwrap<T>(result: { data?: Envelope<T>; error?: Envelope<T>; response: Response }): T {
  if (result.data?.data !== undefined && result.data.data !== null) {
    return result.data.data
  }
  const error = result.data?.error ?? result.error?.error
  throw new ApiCallError(error?.code ?? 'UNKNOWN_ERROR', error?.message ?? 'Request failed.', result.response.status)
}
