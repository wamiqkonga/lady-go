import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Dashboard, { REFRESH_MS } from './Dashboard'

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const weeks = [
  '2026-09-14', '2026-09-21', '2026-09-28', '2026-10-05', '2026-10-12', '2026-10-19', '2026-10-26', '2026-11-02',
].map((weekStart, i) => ({ weekStart, completed: [0, 0, 1, 2, 4, 8, 6, 3][i] }))

const healthy = {
  generatedAt: '2026-11-03T10:00:00Z',
  timeZone: 'Asia/Kolkata',
  today: '2026-11-03',
  drivers: { active: 7, online: 3, onlineMaxAgeMinutes: 10 },
  rides: { active: 2, pendingBookings: 1, completedToday: 9, cancelledToday: 2, cashFaresTodayRupees: 1850.5 },
  customers: { total: 41, newLast7Days: 6 },
  verification: { waiting: 2, waitingOverTarget: 0, medianWaitHours: 5.5, longestWaitHours: 9, targetHours: 24 },
  safety: {
    unresolved: 1, needsAcknowledging: 0, critical: 0, sosMedianAckMinutes: 2, sosAckSample: 4,
    oldestUnacknowledgedSosMinutes: null, sosTargetMinutes: 5,
  },
  adoption: {
    captainsRegistered: 12, captainsCompletedAllSteps: 9, captainsApproved: 7, ridersWhoCompletedARide: 18, weeklyRides: weeks,
  },
  loginAlerts: { accountsWithRepeatedFailures: 0, failureThreshold: 10, windowMinutes: 10 },
}

const ok = (data: unknown) => ({ success: true, error: null, data })

function renderDashboard() {
  return render(
    <MemoryRouter>
      <Dashboard />
    </MemoryRouter>,
  )
}

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
  document.dispatchEvent(new Event('visibilitychange'))
}

function tile(label: string) {
  return screen.getByLabelText(new RegExp(`^${label}:`)).closest('.tile') as HTMLElement
}

beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }))
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  setHidden(false)
})

describe('Dashboard', () => {
  it('shows each tile as loading until the first answer arrives', async () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})))
    renderDashboard()
    expect(screen.getByLabelText('Active drivers: Loading…')).toBeInTheDocument()
    expect(tile('Active drivers')).toHaveAttribute('aria-busy', 'true')
  })

  it('shows the live figures, with cash fares labelled as cash and not revenue', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(ok(healthy))))
    renderDashboard()
    await waitFor(() => expect(screen.getByLabelText('Active drivers: 7')).toBeInTheDocument())
    expect(screen.getByLabelText('Online drivers: 3')).toBeInTheDocument()
    expect(screen.getByLabelText('Active rides: 2')).toBeInTheDocument()
    expect(screen.getByLabelText('Pending bookings: 1')).toBeInTheDocument()
    expect(screen.getByLabelText('Completed rides (today): 9')).toBeInTheDocument()
    expect(screen.getByLabelText('Cancelled rides (today): 2')).toBeInTheDocument()
    expect(screen.getByLabelText('Customers: 41')).toBeInTheDocument()
    expect(screen.getByLabelText('Cash fares collected (today): ₹1,850.50')).toBeInTheDocument()
    expect(tile('Cash fares collected \\(today\\)')).toHaveTextContent('Not Lady Go revenue')
    expect(screen.queryByText(/^Revenue/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('Captains registered: 12')).toBeInTheDocument()
    expect(screen.getByLabelText('Captains who finished every step: 9')).toBeInTheDocument()
    expect(screen.getByLabelText('Captains approved: 7')).toBeInTheDocument()
    expect(screen.getByLabelText('Riders who completed a ride: 18')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('links every tile to the screen that explains it', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(ok(healthy))))
    renderDashboard()
    await waitFor(() => expect(screen.getByLabelText('Active drivers: 7')).toBeInTheDocument())
    const href = (label: string) => tile(label).getAttribute('href')
    expect(href('Open safety incidents')).toBe('/safety')
    expect(href('Driver verification requests')).toBe('/driver-verification')
    expect(href('Active drivers')).toBe('/drivers')
    expect(href('Customers')).toBe('/customers')
    expect(href('Pending bookings')).toBe('/rides')
    expect(href('Completed rides \\(today\\)')).toBe('/rides')
    expect(href('Active rides')).toBe('/live-map')
    expect(href('Online drivers')).toBe('/live-map')
  })

  it('is red, in words as well as colour, when a driver review has waited past the target', async () => {
    const late = { ...healthy, verification: { ...healthy.verification, waiting: 3, waitingOverTarget: 1, longestWaitHours: 31.5 } }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(ok(late))))
    renderDashboard()
    await waitFor(() => expect(screen.getByLabelText('Driver verification requests: 3')).toBeInTheDocument())
    const t = tile('Driver verification requests')
    expect(t).toHaveClass('tile-bad')
    expect(within(t).getByText('Over target')).toBeInTheDocument()
    expect(t).toHaveTextContent('1 waiting over 24 h')
    expect(tile('Driver review wait \\(median\\)')).toHaveClass('tile-bad')
    expect(tile('Active drivers')).not.toHaveClass('tile-bad')
  })

  it('is red when an SOS has waited past the acknowledgement target', async () => {
    const sos = {
      ...healthy,
      safety: { ...healthy.safety, unresolved: 2, needsAcknowledging: 1, critical: 1, oldestUnacknowledgedSosMinutes: 7.5 },
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(ok(sos))))
    renderDashboard()
    await waitFor(() => expect(screen.getByLabelText('Open safety incidents: 2')).toBeInTheDocument())
    expect(tile('Open safety incidents')).toHaveClass('tile-bad')
    expect(tile('Open safety incidents')).toHaveTextContent('1 to acknowledge · 1 critical')
    const median = tile('SOS acknowledged in \\(median\\)')
    expect(median).toHaveClass('tile-bad')
    expect(median).toHaveTextContent('an SOS has waited 7.5 min with nobody acknowledging it')
  })

  it('stays calm while an open SOS is still inside the target', async () => {
    const fresh = { ...healthy, safety: { ...healthy.safety, unresolved: 1, needsAcknowledging: 1, oldestUnacknowledgedSosMinutes: 1.5 } }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(ok(fresh))))
    renderDashboard()
    await waitFor(() => expect(screen.getByLabelText('Open safety incidents: 1')).toBeInTheDocument())
    expect(tile('Open safety incidents')).not.toHaveClass('tile-bad')
  })

  it('says "none waiting" and "no SOS yet" rather than showing a zero it cannot measure', async () => {
    const quiet = {
      ...healthy,
      verification: { waiting: 0, waitingOverTarget: 0, medianWaitHours: null, longestWaitHours: null, targetHours: 24 },
      safety: { ...healthy.safety, unresolved: 0, sosMedianAckMinutes: null, sosAckSample: 0 },
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(ok(quiet))))
    renderDashboard()
    await waitFor(() => expect(screen.getByLabelText('Driver review wait (median): none waiting')).toBeInTheDocument())
    expect(screen.getByLabelText('SOS acknowledged in (median): no SOS yet')).toBeInTheDocument()
  })

  it('explains what the system alerts tile counts, and flags it when it is not zero', async () => {
    const alerts = { ...healthy, loginAlerts: { accountsWithRepeatedFailures: 2, failureThreshold: 10, windowMinutes: 10 } }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(ok(alerts))))
    renderDashboard()
    await waitFor(() => expect(screen.getByLabelText('System alerts: 2')).toBeInTheDocument())
    const t = tile('System alerts')
    expect(t).toHaveTextContent('Admin sign-in addresses with 10+ failed attempts in 10 min')
    expect(t).toHaveTextContent('Nothing else is monitored here')
    expect(t).toHaveClass('tile-bad')
    expect(within(t).getByText('Needs a look')).toBeInTheDocument()
  })

  it('draws the weekly rides as a list that reads as text', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(ok(healthy))))
    renderDashboard()
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Rides completed per week' })).toBeInTheDocument())
    const items = screen.getAllByRole('listitem')
    expect(items).toHaveLength(8)
    expect(items[7]).toHaveTextContent('Week of 2026-11-02')
    expect(items[7]).toHaveTextContent('3')
    expect(items[5]).toHaveTextContent('8')
    expect(screen.getByText(/24 in total/)).toBeInTheDocument()
  })

  it('shows a clear empty state before anyone has signed up', async () => {
    const nothing = {
      ...healthy,
      drivers: { active: 0, online: 0, onlineMaxAgeMinutes: 10 },
      rides: { active: 0, pendingBookings: 0, completedToday: 0, cancelledToday: 0, cashFaresTodayRupees: 0 },
      customers: { total: 0, newLast7Days: 0 },
      verification: { waiting: 0, waitingOverTarget: 0, medianWaitHours: null, longestWaitHours: null, targetHours: 24 },
      safety: { ...healthy.safety, unresolved: 0, sosMedianAckMinutes: null, sosAckSample: 0 },
      adoption: { captainsRegistered: 0, captainsCompletedAllSteps: 0, captainsApproved: 0, ridersWhoCompletedARide: 0, weeklyRides: weeks.map((w) => ({ ...w, completed: 0 })) },
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(ok(nothing))))
    renderDashboard()
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('No activity yet'))
    expect(screen.getByLabelText('Active drivers: 0')).toBeInTheDocument()
  })

  it('shows every tile as unavailable, with a retry, when the first load fails', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ success: false, data: null, error: { code: 'INTERNAL_ERROR', message: 'Server error' } }, 500))
      .mockResolvedValue(jsonResponse(ok(healthy)))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderDashboard()
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Server error'))
    expect(screen.getByLabelText('Active drivers: Unavailable')).toBeInTheDocument()
    expect(screen.getByLabelText('Open safety incidents: Unavailable')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(screen.getByLabelText('Active drivers: 7')).toBeInTheDocument())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('keeps the last figures on screen when a refresh fails', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(ok(healthy)))
      .mockResolvedValue(jsonResponse({ success: false, data: null, error: { code: 'INTERNAL_ERROR', message: 'Server error' } }, 500))
    vi.stubGlobal('fetch', fetchMock)
    renderDashboard()
    await waitFor(() => expect(screen.getByLabelText('Active drivers: 7')).toBeInTheDocument())
    await act(async () => {
      await vi.advanceTimersByTimeAsync(REFRESH_MS)
    })
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Showing the last figures that loaded'))
    expect(screen.getByLabelText('Active drivers: 7')).toBeInTheDocument()
  })

  it('refreshes every 30 seconds, pauses while the tab is hidden and catches up when it returns', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(ok(healthy))))
    vi.stubGlobal('fetch', fetchMock)
    renderDashboard()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(REFRESH_MS)
    })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))

    act(() => setHidden(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(REFRESH_MS * 3)
    })
    expect(fetchMock).toHaveBeenCalledTimes(2)

    act(() => setHidden(false))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
  })
})
