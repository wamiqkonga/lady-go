import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DriverReview from './DriverReview'

afterEach(() => vi.restoreAllMocks())

const reviewBody = {
  success: true,
  data: {
    driverId: 'driver-123',
    verificationStatus: 'PENDING_REVIEW',
    declarationAccepted: true,
    vehicle: { id: 'vehicle-1', registrationNumber: 'JK01AB1234', vehicleType: 'CAB', verificationStatus: 'APPROVED' },
    documents: [{ id: 'doc-1', driverId: 'driver-123', type: 'LICENSE', status: 'PENDING', createdAt: '2026-01-01T00:00:00Z' }],
    readyForApproval: false,
    missingRequirements: ['LICENSE not yet approved'],
  },
  error: null,
}

function renderReview() {
  return render(
    <MemoryRouter initialEntries={['/driver-verification/driver-123']}>
      <Routes>
        <Route path="/driver-verification/:driverId" element={<DriverReview />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('DriverReview', () => {
  function jsonResponse(body: unknown, status = 200) {
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
  }

  it('shows the vehicle, documents and missing requirements', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reviewBody)))

    renderReview()

    await waitFor(() => expect(screen.getByText(/JK01AB1234/)).toBeInTheDocument())
    expect(screen.getByText('LICENSE not yet approved')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve driver' })).toBeDisabled()
  })

  it('fetches a watermarked derivative URL and renders it only after "View" is clicked', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(reviewBody))
      .mockResolvedValueOnce(
        jsonResponse({ success: true, data: 'https://storage.example/driver-document-derivatives/abc', error: null }),
      )
    vi.stubGlobal('fetch', fetchMock)

    renderReview()
    await waitFor(() => expect(screen.getByRole('button', { name: 'View' })).toBeInTheDocument())
    expect(screen.queryByRole('img')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'View' }))

    await waitFor(() => expect(screen.getByRole('img')).toHaveAttribute('src', expect.stringContaining('derivatives')))
  })
})
