import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

function ok(data: unknown) {
  return new Response(JSON.stringify({ success: true, data, error: null }), { status: 200, headers: { 'Content-Type': 'application/json' } })
}
function rejected() {
  return new Response(JSON.stringify({ success: false, data: null, error: { code: 'UNAUTHORIZED', message: 'no' } }), { status: 401 })
}

// A fresh module per test: auth.ts keeps the session in module-level variables.
async function load() {
  vi.resetModules()
  return import('./auth')
}

beforeEach(() => sessionStorage.clear())
afterEach(() => vi.restoreAllMocks())

describe('session restore on reload', () => {
  it('signs in, keeps only the refresh token in sessionStorage (never localStorage), and a reload restores the session', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(ok({ accessToken: 'a1', refreshToken: 'r1' }))
      .mockResolvedValueOnce(ok({ accessToken: 'a2', refreshToken: 'r2' })))
    const first = await load()
    await first.passwordLogin('9900000000', 'pw')
    expect(sessionStorage.getItem('ladygo_captain_rt')).toBe('r1')
    expect(localStorage.length).toBe(0)

    const afterReload = await load() // memory gone, sessionStorage kept
    expect(afterReload.getAccessToken()).toBeNull()
    expect(await afterReload.restoreSession()).toBe(true)
    expect(afterReload.getAccessToken()).toBe('a2')
    expect(sessionStorage.getItem('ladygo_captain_rt')).toBe('r2')
  })

  it('two simultaneous restores make one refresh call', async () => {
    sessionStorage.setItem('ladygo_captain_rt', 'r1')
    const fetchMock = vi.fn().mockResolvedValue(ok({ accessToken: 'a2', refreshToken: 'r2' }))
    vi.stubGlobal('fetch', fetchMock)
    const auth = await load()
    const results = await Promise.all([auth.restoreSession(), auth.restoreSession()])
    expect(results).toEqual([true, true])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('a rejected or revoked refresh token ends the session and removes it', async () => {
    sessionStorage.setItem('ladygo_captain_rt', 'revoked')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(rejected()))
    const auth = await load()
    expect(await auth.restoreSession()).toBe(false)
    expect(auth.getAccessToken()).toBeNull()
    expect(sessionStorage.getItem('ladygo_captain_rt')).toBeNull()
  })

  it('a network failure keeps the stored token so a retry can work', async () => {
    sessionStorage.setItem('ladygo_captain_rt', 'r1')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
    const auth = await load()
    expect(await auth.restoreSession()).toBe(false)
    expect(sessionStorage.getItem('ladygo_captain_rt')).toBe('r1')
  })

  it('sign-out revokes server-side and clears storage; the next load stays signed out', async () => {
    sessionStorage.setItem('ladygo_captain_rt', 'r1')
    const fetchMock = vi.fn().mockResolvedValue(ok({ accessToken: 'a2', refreshToken: 'r2' }))
    vi.stubGlobal('fetch', fetchMock)
    const auth = await load()
    await auth.restoreSession()
    await auth.logout()
    expect(sessionStorage.getItem('ladygo_captain_rt')).toBeNull()
    const logoutCall = fetchMock.mock.calls.at(-1)!
    expect(String(logoutCall[0])).toContain('/api/v1/auth/logout')
    expect(JSON.parse(logoutCall[1].body)).toMatchObject({ refreshToken: 'r2' })
    const again = await load()
    expect(await again.restoreSession()).toBe(false)
  })
})
