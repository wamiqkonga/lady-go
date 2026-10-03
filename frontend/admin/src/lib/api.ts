import { API_BASE_URL } from '../config'

/** Mirrors the backend response envelope (see docs/architecture/backend-architecture.md). */
export type ApiError = {
  code: string
  message: string
  details: Array<{ field: string; message: string }>
  requestId?: string
}

export type ApiResponse<T> = {
  success: boolean
  data: T | null
  message: string | null
  error: ApiError | null
  timestamp: string
}

export class ApiRequestError extends Error {
  readonly status: number
  readonly code: string
  readonly requestId?: string

  constructor(status: number, code: string, message: string, requestId?: string) {
    super(message)
    this.name = 'ApiRequestError'
    this.status = status
    this.code = code
    this.requestId = requestId
  }
}

/**
 * Calls the backend and unwraps the standard envelope.
 * Authentication (access tokens, refresh) is added in Phase 3.
 */
export async function apiGet<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    method: 'GET',
    headers: { Accept: 'application/json', ...(init?.headers ?? {}) },
  })

  let body: ApiResponse<T> | null = null
  try {
    body = (await response.json()) as ApiResponse<T>
  } catch {
    body = null
  }

  if (!response.ok || !body || !body.success) {
    throw new ApiRequestError(
      response.status,
      body?.error?.code ?? 'UNKNOWN_ERROR',
      body?.error?.message ?? 'Request failed.',
      body?.error?.requestId,
    )
  }
  return body.data as T
}
