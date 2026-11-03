import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SafetyQueue from './SafetyQueue'

afterEach(() => vi.restoreAllMocks())

function page(content: unknown[]) {
  return { success: true, data: { content, page: 0, size: 50, totalElements: content.length, totalPages: 1 }, error: null }
}

function stubFetch(responder: (url: string) => unknown) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    return new Response(JSON.stringify(responder(url)), { status: 200, headers: { 'Content-Type': 'application/json' } })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const sos = {
  id: 'inc-1', rideId: 'ride-1', type: 'SOS', severity: 'CRITICAL', status: 'OPEN',
  reporter: { name: 'Asma Rider', role: 'CUSTOMER', phoneMasked: '******3210' },
  hasLocation: true, createdAt: new Date(Date.now() - 5 * 60_000).toISOString(),
}

describe('SafetyQueue', () => {
  it('lists unresolved incidents with a link to each and warns about an open SOS', async () => {
    stubFetch(() => page([sos]))

    render(<MemoryRouter><SafetyQueue /></MemoryRouter>)

    await waitFor(() => expect(screen.getByRole('link', { name: 'SOS' })).toHaveAttribute('href', '/safety/inc-1'))
    expect(screen.getByText('CRITICAL')).toBeInTheDocument()
    expect(screen.getByText(/Asma Rider/)).toBeInTheDocument()
    expect(screen.getByText('******3210')).toBeInTheDocument()
    expect(screen.getByText('5 min ago')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('call 112')
  })

  it('says so when the queue is empty and shows no SOS warning', async () => {
    stubFetch(() => page([]))

    render(<MemoryRouter><SafetyQueue /></MemoryRouter>)

    await waitFor(() => expect(screen.getByText('No open safety incidents.')).toBeInTheDocument())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('asks for resolved incidents when the Resolved tab is chosen', async () => {
    const fetchMock = stubFetch(() => page([]))

    render(<MemoryRouter><SafetyQueue /></MemoryRouter>)
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    await userEvent.click(screen.getByRole('button', { name: 'Resolved' }))

    await waitFor(() => expect(screen.getByText('No resolved incidents yet.')).toBeInTheDocument())
    expect(fetchMock.mock.calls.some(([input]) => String((input as Request).url ?? input).includes('status=RESOLVED'))).toBe(true)
  })

  it('shows the server message when loading fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ success: false, data: null, error: { code: 'FORBIDDEN', message: 'Not allowed.' } }), {
        status: 403, headers: { 'Content-Type': 'application/json' },
      }),
    ))

    render(<MemoryRouter><SafetyQueue /></MemoryRouter>)

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Not allowed.'))
  })
})
