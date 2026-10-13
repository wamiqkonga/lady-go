import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RideDetail from './RideDetail'

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const detailBody = {
  success: true,
  data: {
    bookingId: 'booking-1', bookingStatus: 'COMPLETED', paymentMethod: 'CASH',
    customer: { name: 'Priya Shah', phoneMasked: '******1234' },
    driver: { name: 'Rahul Dev', phoneMasked: '******5678' },
    pickupAddress: '12 MG Road, Pune', finalFare: 250, estimatedFare: 220,
    cashConfirmedAt: '2026-01-01T10:05:00Z', cashDisputedAt: null,
    flags: [{ id: 'flag-1', bookingId: 'booking-1', type: 'EARLY_END', riderResponse: 'ACKNOWLEDGED' }],
    timeline: [
      { fromStatus: 'RIDE_STARTED', toStatus: 'RIDE_COMPLETED', actorType: 'DRIVER', reason: 'ride_completed', createdAt: '2026-01-01T10:00:00Z' },
    ],
  },
  error: null,
}

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/rides/booking-1']}>
      <Routes>
        <Route path="/rides/:bookingId" element={<RideDetail />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('RideDetail', () => {
  it('shows masked contacts, pickup address, cash state and the timeline', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(detailBody)))

    renderDetail()

    await waitFor(() => expect(screen.getByText(/12 MG Road, Pune/)).toBeInTheDocument())
    expect(screen.getByText(/Priya Shah/)).toBeInTheDocument()
    expect(screen.getByText(/\*{6}1234/)).toBeInTheDocument()
    expect(screen.getByText(/\*{6}5678/)).toBeInTheDocument()
    expect(screen.getByText(/Cash confirmed/)).toBeInTheDocument()
    expect(screen.getByText('RIDE_STARTED')).toBeInTheDocument()
    expect(screen.getByText('ride_completed')).toBeInTheDocument()

    // Never the full customer/driver phone number anywhere on the page.
    expect(document.body.textContent).not.toMatch(/\b\d{10}\b/)
  })

  it('shows an already-reviewed flag note as plain text, not as an editable form', async () => {
    const reviewed = {
      ...detailBody,
      data: {
        ...detailBody.data,
        flags: [{
          id: 'flag-1', bookingId: 'booking-1', type: 'EARLY_END',
          adminReviewedBy: 'admin-1', adminReviewedAt: '2026-01-02T00:00:00Z',
          adminReviewNote: '<script>alert(1)</script>already handled',
        }],
      },
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reviewed)))

    renderDetail()

    await waitFor(() => expect(screen.getByText(/already handled/)).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Mark reviewed' })).not.toBeInTheDocument()
    expect(document.querySelector('.review-note script')).toBeNull()
  })
})
