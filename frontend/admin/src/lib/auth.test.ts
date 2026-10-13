import { afterEach, describe, expect, it, vi } from 'vitest'
import { bootstrapSession, getAccessToken } from './auth'

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('bootstrapSession', () => {
  it('coalesces two concurrent calls into a single /refresh request', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ success: true, data: { accessToken: 'token-1' }, error: null }))
    vi.stubGlobal('fetch', fetchMock)

    // React StrictMode double-invokes SessionProvider's mount effect, firing this twice near-
    // simultaneously - this is exactly that shape, not a sequential pair of calls.
    const [first, second] = await Promise.all([bootstrapSession(), bootstrapSession()])

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(first).toBe(true)
    expect(second).toBe(true)
    expect(getAccessToken()).toBe('token-1')
  })

  it('a second call after the first has settled makes its own fresh request', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { accessToken: 'token-1' }, error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { accessToken: 'token-2' }, error: null }))
    vi.stubGlobal('fetch', fetchMock)

    await bootstrapSession()
    await bootstrapSession()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(getAccessToken()).toBe('token-2')
  })

  it('a failed refresh clears the access token even when coalesced', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ success: false, data: null, error: { code: 'UNAUTHORIZED', message: 'x' } }, 401))
    vi.stubGlobal('fetch', fetchMock)

    const [first, second] = await Promise.all([bootstrapSession(), bootstrapSession()])

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(first).toBe(false)
    expect(second).toBe(false)
    expect(getAccessToken()).toBeNull()
  })
})
