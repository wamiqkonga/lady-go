import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LiveMap from './LiveMap'
import type { MapMarker } from '../components/LiveMapCanvas'

// Leaflet needs real layout; the canvas wrapper is replaced by a stub that exposes its markers.
const canvasProps: { markers: MapMarker[]; selectedId: string | null; onSelect: (id: string) => void } = {
  markers: [], selectedId: null, onSelect: () => {},
}
vi.mock('../components/LiveMapCanvas', () => ({
  default: (props: typeof canvasProps) => {
    Object.assign(canvasProps, props)
    return <div data-testid="map" data-markers={props.markers.length} />
  },
}))

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const base = {
  generatedAt: '2026-11-01T10:00:00Z', maxAgeMinutes: 10, limit: 200,
  driversTruncated: false, ridesTruncated: false, incidentsTruncated: false,
}
const populated = {
  success: true, error: null,
  data: {
    ...base,
    drivers: [
      { driverId: 'd1', name: 'Aisha K.', availability: 'ONLINE', latitude: 34.08, longitude: 74.8, positionAt: '2026-11-01T09:59:50Z', ageSeconds: 10 },
      { driverId: 'd2', name: 'Bilal M.', availability: 'ON_RIDE', latitude: 34.09, longitude: 74.81, positionAt: '2026-11-01T09:55:00Z', ageSeconds: 300 },
    ],
    rides: [{
      bookingId: 'b1', status: 'RIDE_STARTED', driverId: 'd2', driverName: 'Bilal M.', customerName: 'Zainab M.',
      pickupAddress: 'Lal Chowk', pickupLatitude: 34.08, pickupLongitude: 74.8,
      dropAddress: 'Hazratbal', dropLatitude: 34.13, dropLongitude: 74.87,
    }],
    incidents: [
      { incidentId: 'i1', rideId: 'r1', type: 'SOS', severity: 'HIGH', status: 'OPEN', createdAt: '2026-11-01T09:58:00Z', latitude: 34.1, longitude: 74.82 },
      { incidentId: 'i2', rideId: 'r1', type: 'OTHER', severity: 'LOW', status: 'OPEN', createdAt: '2026-11-01T09:00:00Z', latitude: null, longitude: null },
    ],
  },
}
const empty = { success: true, error: null, data: { ...base, drivers: [], rides: [], incidents: [] } }

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
  document.dispatchEvent(new Event('visibilitychange'))
}

beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }))
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  setHidden(false)
})

describe('LiveMap', () => {
  it('shows a clear empty state when nothing is live', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(empty)))
    render(<LiveMap />)
    await waitFor(() => expect(screen.getByText(/Nothing to show right now/)).toBeInTheDocument())
  })

  it('lists drivers, rides and incidents and maps one marker per positioned item', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(populated)))
    render(<LiveMap />)
    await waitFor(() => expect(screen.getByText(/Aisha K\./)).toBeInTheDocument())
    expect(screen.getByRole('heading', { level: 2, name: 'Drivers (2)' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Active rides (1)' })).toBeInTheDocument()
    expect(screen.getByText(/no position/)).toBeInTheDocument() // incident without coordinates is listed, not pinned
    // 2 drivers + 1 pickup + 1 incident with a position
    expect(canvasProps.markers).toHaveLength(4)
    expect(canvasProps.markers.map((m) => m.kind).sort()).toEqual(['driver', 'driver-busy', 'incident', 'pickup'])
  })

  it('warns about stale positions and dims their marker', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(populated)))
    render(<LiveMap />)
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(/1 driver on the map has not reported a position/))
    expect(canvasProps.markers.find((m) => m.id === 'driver:d2')?.stale).toBe(true)
    expect(canvasProps.markers.find((m) => m.id === 'driver:d1')?.stale).toBe(false)
  })

  it('shows details when a list item is clicked and adds the drop marker for a selected ride', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(populated)))
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    render(<LiveMap />)
    await waitFor(() => expect(screen.getByText(/RIDE STARTED|ride started/i)).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: /ride started · Lal Chowk/i }))
    const details = screen.getByRole('region', { name: 'Details' })
    expect(details).toHaveTextContent('Drop: Hazratbal')
    expect(details).toHaveTextContent('Customer: Zainab M.')
    expect(canvasProps.markers.some((m) => m.kind === 'drop')).toBe(true)
  })

  it('selects an item when its map marker is clicked', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(populated)))
    render(<LiveMap />)
    await waitFor(() => expect(canvasProps.markers).toHaveLength(4))
    act(() => canvasProps.onSelect('driver:d1'))
    expect(screen.getByRole('region', { name: 'Details' })).toHaveTextContent('Aisha K.')
  })

  it('polls every 10 seconds', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(empty)))
    vi.stubGlobal('fetch', fetchMock)
    render(<LiveMap />)
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    await act(() => vi.advanceTimersByTimeAsync(10_000))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    await act(() => vi.advanceTimersByTimeAsync(10_000))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
  })

  it('pauses polling while the tab is hidden and refreshes when it is visible again', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(empty)))
    vi.stubGlobal('fetch', fetchMock)
    render(<LiveMap />)
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    act(() => setHidden(true))
    await act(() => vi.advanceTimersByTimeAsync(60_000))
    expect(fetchMock).toHaveBeenCalledTimes(1)
    act(() => setHidden(false))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
  })

  it('keeps the last data and shows an error when a refresh fails', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(populated))
      .mockResolvedValue(jsonResponse({ success: false, data: null, error: { code: 'SERVER_ERROR', message: 'boom' } }, 500))
    vi.stubGlobal('fetch', fetchMock)
    render(<LiveMap />)
    await waitFor(() => expect(screen.getByText(/Aisha K\./)).toBeInTheDocument())
    await act(() => vi.advanceTimersByTimeAsync(10_000))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/Showing the last data received/))
    expect(screen.getByText(/Aisha K\./)).toBeInTheDocument()
  })
})
