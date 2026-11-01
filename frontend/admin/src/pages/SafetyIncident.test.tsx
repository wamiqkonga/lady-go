import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SafetyIncident from './SafetyIncident'

afterEach(() => vi.restoreAllMocks())

function detail(overrides: Record<string, unknown> = {}) {
  return {
    id: 'inc-1', rideId: 'ride-1', bookingId: 'book-1', type: 'SOS', severity: 'CRITICAL', status: 'OPEN',
    reporter: { name: 'Asma Rider', role: 'CUSTOMER', phoneMasked: '******3210' },
    location: { lat: 34.08, lng: 74.8 }, notes: 'Driver took a wrong turn', legalHold: false,
    createdAt: '2026-11-01T10:00:00Z',
    timeline: [{ id: 'e1', eventType: 'CREATED', actorName: 'Asma Rider', actorRole: 'CUSTOMER', createdAt: '2026-11-01T10:00:00Z' }],
    ...overrides,
  }
}

function ok(data: unknown) {
  return new Response(JSON.stringify({ success: true, data, error: null }), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

/** First call loads the incident; later calls answer the next queued response. */
function stubFetch(...responses: Response[]) {
  const queue = [...responses]
  const fetchMock = vi.fn(async () => queue.shift() ?? ok(detail()))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function renderIncident() {
  return render(
    <MemoryRouter initialEntries={['/safety/inc-1']}>
      <Routes><Route path="/safety/:incidentId" element={<SafetyIncident />} /></Routes>
    </MemoryRouter>,
  )
}

function requestBody(fetchMock: ReturnType<typeof vi.fn>, call: number): Promise<string> {
  return (fetchMock.mock.calls[call] as unknown as [Request])[0].clone().text()
}

describe('SafetyIncident', () => {
  it('shows the reporter with a masked phone, the position, a ride link and the timeline', async () => {
    stubFetch(ok(detail()))

    renderIncident()

    await waitFor(() => expect(screen.getByRole('heading', { name: 'SOS' })).toBeInTheDocument())
    expect(screen.getByText('******3210', { exact: false })).toBeInTheDocument()
    expect(screen.getByText(/34\.08000, 74\.80000/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View the ride' })).toHaveAttribute('href', '/rides/book-1')
    expect(screen.getByText('CREATED')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('call 112')
  })

  it('acknowledges an open incident and shows the new status', async () => {
    const fetchMock = stubFetch(ok(detail()), ok(detail({ status: 'ACKNOWLEDGED' })))

    renderIncident()
    await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }))

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Acknowledge' })).not.toBeInTheDocument())
    expect(String((fetchMock.mock.calls[1] as unknown as [Request])[0].url)).toContain('/api/v1/admin/safety/incidents/inc-1/acknowledge')
  })

  it('will not resolve until an outcome is chosen, then sends it', async () => {
    const fetchMock = stubFetch(ok(detail({ status: 'ACKNOWLEDGED' })), ok(detail({ status: 'RESOLVED' })))

    renderIncident()
    const resolve = await screen.findByRole('button', { name: 'Resolve incident' })
    expect(resolve).toBeDisabled()

    await userEvent.selectOptions(screen.getByLabelText('Resolve with outcome'), 'POLICE_INFORMED')
    await userEvent.type(screen.getByLabelText('Note (optional)'), 'Police called')
    expect(resolve).toBeEnabled()
    await userEvent.click(resolve)

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Resolve incident' })).not.toBeInTheDocument())
    expect(JSON.parse(await requestBody(fetchMock, 1))).toEqual({ outcome: 'POLICE_INFORMED', note: 'Police called' })
  })

  it('adds a note and clears the box', async () => {
    const fetchMock = stubFetch(ok(detail()), ok(detail({
      timeline: [{ id: 'e1', eventType: 'CREATED' }, { id: 'e2', eventType: 'NOTE', notes: 'Rider is safe', actorName: 'Agent' }],
    })))

    renderIncident()
    await userEvent.type(await screen.findByLabelText('Add a note'), 'Rider is safe')
    await userEvent.click(screen.getByRole('button', { name: 'Add note' }))

    await waitFor(() => expect(screen.getByText('Rider is safe')).toBeInTheDocument())
    expect(screen.getByLabelText('Add a note')).toHaveValue('')
    expect(JSON.parse(await requestBody(fetchMock, 1))).toEqual({ note: 'Rider is safe' })
  })

  it('shows the server message when an action is refused and keeps the page', async () => {
    stubFetch(
      ok(detail()),
      new Response(JSON.stringify({ success: false, data: null, error: { code: 'VALIDATION_FAILED', message: 'This incident was already acknowledged or resolved.' } }), {
        status: 400, headers: { 'Content-Type': 'application/json' },
      }),
    )

    renderIncident()
    await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }))

    await waitFor(() => expect(screen.getByText('This incident was already acknowledged or resolved.')).toBeInTheDocument())
    expect(screen.getByRole('heading', { name: 'SOS' })).toBeInTheDocument()
  })

  it('offers no actions once an incident is resolved', async () => {
    stubFetch(ok(detail({ status: 'RESOLVED' })))

    renderIncident()

    await waitFor(() => expect(screen.getByRole('heading', { name: 'SOS' })).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Resolve incident' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add note' })).not.toBeInTheDocument()
  })
})
