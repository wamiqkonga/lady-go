import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiRequestError, apiGet } from './api'

afterEach(() => vi.restoreAllMocks())

function mockFetch(status: number, body: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      json: () => Promise.resolve(body),
    }),
  )
}

describe('apiGet', () => {
  it('unwraps data from a successful envelope', async () => {
    mockFetch(200, { success: true, data: { status: 'UP' }, message: null, error: null, timestamp: 'x' })
    await expect(apiGet<{ status: string }>('/api/v1/system/ping')).resolves.toEqual({ status: 'UP' })
  })

  it('throws ApiRequestError with the backend error code', async () => {
    mockFetch(401, {
      success: false,
      data: null,
      message: 'Authentication is required.',
      error: { code: 'UNAUTHORIZED', message: 'Authentication is required.', details: [], requestId: 'req-1' },
      timestamp: 'x',
    })
    await expect(apiGet('/api/v1/admin/dashboard')).rejects.toMatchObject({
      status: 401,
      code: 'UNAUTHORIZED',
      requestId: 'req-1',
    })
  })

  it('handles non-JSON error responses safely', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: false, status: 502, json: () => Promise.reject(new Error('bad json')) }),
    )
    const err = await apiGet('/x').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiRequestError)
    expect((err as ApiRequestError).code).toBe('UNKNOWN_ERROR')
  })
})
