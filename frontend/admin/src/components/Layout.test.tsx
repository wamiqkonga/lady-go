import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SessionContext } from '../lib/sessionContext'
import { refreshSafetyBadge } from '../lib/useSafetyBadge'
import Layout from './Layout'

afterEach(() => vi.restoreAllMocks())

function countsBody(over: Record<string, unknown> = {}) {
  return { success: true, data: { unresolved: 0, needsAcknowledging: 0, critical: 0, ...over }, error: null }
}

function stub(bodies: unknown[]) {
  const queue = [...bodies]
  const fetchMock = vi.fn(async () =>
    new Response(JSON.stringify(queue.length > 1 ? queue.shift() : queue[0]), { status: 200, headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function renderLayout(path = '/dashboard') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionContext.Provider value={{ signedIn: true, role: 'SUPPORT', signOut: vi.fn() }}>
        <Routes>
          <Route element={<Layout />}>
            <Route path="dashboard" element={<p>Dashboard page</p>} />
            <Route path="safety" element={<p>Safety page</p>} />
          </Route>
        </Routes>
      </SessionContext.Provider>
    </MemoryRouter>,
  )
}

describe('Layout', () => {
  it('shows no banner when there is no critical SOS', async () => {
    const fetchMock = stub([countsBody({ unresolved: 2, critical: 0 })])
    renderLayout()
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(await screen.findByLabelText('2 unresolved')).toBeInTheDocument()
    expect(screen.queryByText(/SOS open/)).not.toBeInTheDocument()
  })

  it('shows a red SOS banner on every page, with its age and a link to the Safety queue', async () => {
    stub([countsBody({ unresolved: 1, critical: 1, oldestCriticalAt: new Date(Date.now() - 4 * 60_000).toISOString() })])
    renderLayout('/dashboard')
    const banner = await screen.findByRole('status')
    expect(banner).toHaveTextContent('SOS open: 1, raised 4 min ago.')
    expect(banner).toHaveClass('sos-banner')
    expect(screen.getByRole('link', { name: 'Open the queue' })).toHaveAttribute('href', '/safety')
    expect(screen.getByText('Dashboard page')).toBeInTheDocument()
  })

  it('re-reads the open count at once when asked to, so the badge and banner never lag', async () => {
    stub([countsBody({ unresolved: 1, critical: 1 }), countsBody({ unresolved: 0, critical: 0 })])
    renderLayout()
    expect(await screen.findByRole('status')).toBeInTheDocument()
    act(() => refreshSafetyBadge())
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument())
    expect(screen.queryByLabelText(/unresolved/)).not.toBeInTheDocument()
  })

  it('has a scroll cue element on each side of the menu strip and marks the current page', async () => {
    stub([countsBody()])
    renderLayout('/safety')
    expect(screen.getByRole('link', { name: 'Safety' })).toHaveClass('active')
    expect(screen.getByTestId('nav-fade-right')).toBeInTheDocument()
    expect(screen.getByTestId('nav-fade-left')).toBeInTheDocument()
  })
})
