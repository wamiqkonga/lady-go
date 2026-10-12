import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )
}

/** Every route render triggers SessionProvider's silent-refresh bootstrap - mock it rejected
 *  (no valid refresh cookie, the normal case for a test with no real backend) unless a test
 *  overrides it. */
function mockUnauthenticatedBootstrap() {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: () => Promise.resolve({ success: false, data: null, error: { code: 'UNAUTHORIZED' } }),
    }),
  )
}

afterEach(() => vi.restoreAllMocks())

describe('admin routes, signed out', () => {
  it('redirects the root to login when there is no session', async () => {
    mockUnauthenticatedBootstrap()
    renderAt('/')
    await waitFor(() => expect(screen.getByLabelText('Email')).toBeInTheDocument())
  })

  it.each(['/dashboard', '/driver-verification', '/rides', '/audit-logs'])(
    'redirects %s to login when there is no session',
    async (path) => {
      mockUnauthenticatedBootstrap()
      renderAt(path)
      await waitFor(() => expect(screen.getByLabelText('Email')).toBeInTheDocument())
    },
  )

  it('renders a real, functional sign-in form (not the pre-Phase-3 disabled stub)', async () => {
    mockUnauthenticatedBootstrap()
    renderAt('/login')
    await waitFor(() => expect(screen.getByLabelText('Email')).toBeInTheDocument())
    expect(screen.getByLabelText('Email')).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled()
  })
})

describe('admin routes, signed in', () => {
  function mockAuthenticatedBootstrap() {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ success: true, data: { accessToken: 'test-token' }, error: null }),
      }),
    )
  }

  it('redirects the root to the dashboard', async () => {
    mockAuthenticatedBootstrap()
    renderAt('/')
    await waitFor(() => expect(screen.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeInTheDocument())
  })

  it('shows every dashboard tile from the admin flow spec without fake numbers', async () => {
    mockAuthenticatedBootstrap()
    renderAt('/dashboard')
    await waitFor(() => expect(screen.getByLabelText('Active drivers: no data yet')).toBeInTheDocument())
    expect(screen.getByLabelText('System alerts: no data yet')).toBeInTheDocument()
  })

  it.each([
    ['/live-map', 'Live map'],
    ['/rides', 'Rides'],
    ['/drivers', 'Drivers'],
    ['/customers', 'Customers'],
    ['/pricing', 'Pricing and configuration'],
    ['/safety', 'Safety incidents'],
    ['/audit-logs', 'Audit log'],
  ])('renders %s', async (path, heading) => {
    mockAuthenticatedBootstrap()
    renderAt(path)
    await waitFor(() => expect(screen.getByRole('heading', { level: 1, name: heading })).toBeInTheDocument())
  })
})
