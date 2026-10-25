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
    driverName: 'Asha Verma',
    driverPhoneMasked: '******4321',
    verificationStatus: 'PENDING_REVIEW',
    declarationAccepted: true,
    vehicle: { id: 'vehicle-1', registrationNumber: 'JK01AB1234', vehicleType: 'CAB', verificationStatus: 'APPROVED' },
    documents: [
      { id: 'doc-1', driverId: 'driver-123', type: 'LICENSE', status: 'PENDING', createdAt: '2026-01-01T00:00:00Z' },
      {
        id: 'doc-2', driverId: 'driver-123', type: 'INSURANCE', status: 'APPROVED', expiresAt: '2020-01-01',
        createdAt: '2026-01-01T00:00:00Z',
      },
      {
        id: 'doc-3', driverId: 'driver-123', type: 'VEHICLE_PHOTO', status: 'REJECTED',
        reviewNote: '<img src=x onerror=alert(1)>blurry', createdAt: '2026-01-01T00:00:00Z',
      },
    ],
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
    expect(screen.getByText(/Asha Verma/)).toBeInTheDocument()
    expect(screen.getByText(/\*{6}4321/)).toBeInTheDocument()
    expect(screen.getByText(/expires 2020-01-01 \(expired\)/)).toBeInTheDocument()

    // A rejection note is rendered as plain text, never interpreted as markup - React escapes text
    // children by construction, so this proves no dangerouslySetInnerHTML (or similar) crept in.
    expect(screen.getByText(/<img src=x onerror=alert\(1\)>blurry/)).toBeInTheDocument()
    expect(document.querySelector('.review-note img')).toBeNull()
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
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'View' })).toHaveLength(3))
    expect(screen.queryByRole('img')).not.toBeInTheDocument()

    await userEvent.click(screen.getAllByRole('button', { name: 'View' })[0])

    await waitFor(() => expect(screen.getByRole('img')).toHaveAttribute('src', expect.stringContaining('derivatives')))
  })
  describe('when the server rejects a review action', () => {
    const conflict = {
      success: false,
      data: null,
      error: { code: 'CONFLICT', message: 'This document was already reviewed.' },
    }

    it('shows the server message for a failed document approval instead of treating it as success', async () => {
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(reviewBody))
        .mockResolvedValueOnce(jsonResponse(conflict, 409))
      vi.stubGlobal('fetch', fetchMock)

      renderReview()
      await waitFor(() => expect(screen.getAllByRole('button', { name: 'Approve' })).toHaveLength(1))
      await userEvent.click(screen.getByRole('button', { name: 'Approve' }))

      expect(await screen.findByText('This document was already reviewed.')).toBeInTheDocument()
      // No reload was triggered: the only fetches were the page load and the approve call.
      expect(fetchMock).toHaveBeenCalledTimes(2)
    })

    it('keeps the review page on screen when approving the driver fails', async () => {
      const ready = { ...reviewBody, data: { ...reviewBody.data, readyForApproval: true, missingRequirements: [] } }
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(ready))
        .mockResolvedValueOnce(jsonResponse({ ...conflict, error: { code: 'CONFLICT', message: 'Driver is not ready.' } }, 409))
      vi.stubGlobal('fetch', fetchMock)

      renderReview()
      await waitFor(() => expect(screen.getByRole('button', { name: 'Approve driver' })).toBeEnabled())
      await userEvent.click(screen.getByRole('button', { name: 'Approve driver' }))

      expect(await screen.findByText('Driver is not ready.')).toBeInTheDocument()
      expect(screen.getByRole('heading', { name: 'Driver review' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Approve driver' })).toBeEnabled()
    })

    it('does not send a blank rejection reason', async () => {
      const fetchMock = vi.fn().mockResolvedValue(jsonResponse(reviewBody))
      vi.stubGlobal('fetch', fetchMock)
      vi.spyOn(window, 'prompt').mockReturnValue('   ')

      renderReview()
      await waitFor(() => expect(screen.getAllByRole('button', { name: 'Reject' })).toHaveLength(1))
      await userEvent.click(screen.getByRole('button', { name: 'Reject' }))

      expect(fetchMock).toHaveBeenCalledTimes(1)
    })
  })
})
