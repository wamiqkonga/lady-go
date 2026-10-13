import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RideOversight from './RideOversight'

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ridesPage = {
  success: true,
  data: {
    content: [
      {
        bookingId: 'booking-1', bookingStatus: 'RIDE_STARTED',
        customer: { name: 'Priya Shah', phoneMasked: '******1234' },
        driver: { name: 'Rahul Dev', phoneMasked: '******5678' },
        finalFare: 250, openFlagCount: 1, createdAt: '2026-01-01T00:00:00Z',
      },
    ],
    page: 0, size: 50, totalElements: 1, totalPages: 1,
  },
  error: null,
}

const flagsPage = {
  success: true,
  data: {
    content: [
      {
        id: 'flag-1', bookingId: 'booking-1', type: 'RIDER_SAFETY_CANCEL',
        customer: { name: 'Priya Shah', phoneMasked: '******1234' },
        driver: { name: 'Rahul Dev', phoneMasked: '******5678' },
        createdAt: '2026-01-01T00:00:00Z',
      },
    ],
    page: 0, size: 50, totalElements: 1, totalPages: 1,
  },
  error: null,
}

function renderPage() {
  return render(
    <MemoryRouter>
      <RideOversight />
    </MemoryRouter>,
  )
}

describe('RideOversight', () => {
  it('lists rides with masked contacts, fare and an open-flag badge, linking to the detail page', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(ridesPage)))

    renderPage()

    await waitFor(() => expect(screen.getByRole('link', { name: 'RIDE_STARTED' })).toBeInTheDocument())
    expect(screen.getByText(/Priya Shah/)).toBeInTheDocument()
    expect(screen.getByText(/\*{6}1234/)).toBeInTheDocument()
    expect(screen.getByText('250')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'RIDE_STARTED' })).toHaveAttribute('href', '/rides/booking-1')
  })

  it('switches to the flags tab and submits a required review note', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(ridesPage))
      .mockResolvedValueOnce(jsonResponse(flagsPage))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { id: 'flag-1', adminReviewedAt: '2026-01-02T00:00:00Z' }, error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }, error: null }))
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    await waitFor(() => expect(screen.getByRole('link', { name: 'RIDE_STARTED' })).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: 'Flags' }))
    await waitFor(() => expect(screen.getByText('RIDER_SAFETY_CANCEL', { selector: 'strong' })).toBeInTheDocument())

    const reviewButton = screen.getByRole('button', { name: 'Mark reviewed' })
    expect(reviewButton).toBeDisabled() // no note yet - the backend requires one, so the UI does too.

    await userEvent.type(screen.getByPlaceholderText('Review note (required)'), 'checked, false alarm')
    await userEvent.click(reviewButton)

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4))
    const reviewRequest = fetchMock.mock.calls[2][0] as Request
    expect(reviewRequest.url).toContain('/admin/ride-flags/flag-1/review')
    expect(JSON.parse(await reviewRequest.clone().text())).toEqual({ note: 'checked, false alarm' })
  })

  it('filters rides by open-flag state via a query param', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(ridesPage))
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    await waitFor(() => expect(screen.getByRole('link', { name: 'RIDE_STARTED' })).toBeInTheDocument())

    await userEvent.selectOptions(screen.getByLabelText('Flagged'), 'Has open flags')

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    const request = fetchMock.mock.calls[1][0] as Request
    expect(request.url).toContain('hasOpenFlags=true')
  })
})
