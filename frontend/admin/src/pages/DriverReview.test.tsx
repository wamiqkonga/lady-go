import { render, screen, waitFor, within } from '@testing-library/react'
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

  it('shows documents the captain did not provide, marking the optional ones', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reviewBody)))

    renderReview()

    await waitFor(() => expect(screen.getByText(/JK01AB1234/)).toBeInTheDocument())
    // Required but missing: the RC and the selfie. Optional and missing: the fitness certificate.
    expect(screen.getAllByText(/not provided/)).toHaveLength(3)
    expect(screen.getAllByText('(not provided - optional)')).toHaveLength(1)
    expect(screen.getByText('Selfie')).toBeInTheDocument()
  })

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
    await waitFor(() => expect(screen.getAllByRole('button', { name: /^View / })).toHaveLength(3))
    expect(screen.queryByRole('img')).not.toBeInTheDocument()

    await userEvent.click(screen.getAllByRole('button', { name: /^View / })[0])

    await waitFor(() => expect(screen.getByRole('img')).toHaveAttribute('src', expect.stringContaining('derivatives')))
    expect(screen.getByRole('dialog', { name: 'Driving licence' })).toBeInTheDocument()
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
      await userEvent.click(screen.getByRole('button', { name: 'Yes, approve driver' }))

      expect(await screen.findByText('Driver is not ready.')).toBeInTheDocument()
      expect(screen.getByRole('heading', { name: 'Driver review' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Approve driver' })).toBeEnabled()
    })

    it('does not send a blank rejection reason and says why', async () => {
      const fetchMock = vi.fn().mockResolvedValue(jsonResponse(reviewBody))
      vi.stubGlobal('fetch', fetchMock)
      const prompt = vi.spyOn(window, 'prompt')

      renderReview()
      await waitFor(() => expect(screen.getAllByRole('button', { name: 'Reject' })).toHaveLength(1))
      await userEvent.click(screen.getByRole('button', { name: 'Reject' }))
      await userEvent.click(screen.getByRole('button', { name: 'Reject document' }))

      expect(await screen.findByText(/Write a reason/)).toBeInTheDocument()
      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(prompt).not.toHaveBeenCalled()
    })
  })

  describe('the decision panel', () => {
    const ready = { ...reviewBody, data: { ...reviewBody.data, readyForApproval: true, missingRequirements: [] } }

    it('asks for confirmation, then replaces the buttons with the outcome and a success message', async () => {
      const approved = {
        ...ready,
        data: { ...ready.data, verificationStatus: 'APPROVED', decidedAt: '2026-03-02T10:00:00Z', decidedByName: 'Ayesha Admin' },
      }
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(ready))
        .mockResolvedValueOnce(jsonResponse({ success: true, data: null, error: null }))
        .mockResolvedValueOnce(jsonResponse(approved))
      vi.stubGlobal('fetch', fetchMock)

      renderReview()
      await waitFor(() => expect(screen.getByRole('button', { name: 'Approve driver' })).toBeEnabled())
      await userEvent.click(screen.getByRole('button', { name: 'Approve driver' }))
      // Nothing is sent until the person confirms.
      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(screen.getByRole('dialog', { name: 'Approve this driver?' })).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Yes, approve driver' }))

      expect(await screen.findByText(/Driver approved\./)).toBeInTheDocument()
      expect(screen.getByText(/Approved on .* by Ayesha Admin\./)).toBeInTheDocument()
      expect(screen.queryByText(/Ready for approval/)).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Approve driver' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Reject driver' })).not.toBeInTheDocument()
    })

    it('Cancel in the confirm step sends nothing', async () => {
      const fetchMock = vi.fn().mockResolvedValue(jsonResponse(ready))
      vi.stubGlobal('fetch', fetchMock)
      renderReview()
      await waitFor(() => expect(screen.getByRole('button', { name: 'Approve driver' })).toBeEnabled())
      await userEvent.click(screen.getByRole('button', { name: 'Approve driver' }))
      await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(screen.getByRole('button', { name: 'Approve driver' })).toBeInTheDocument()
    })

    it('shows the rejection reason after a rejection and sends the typed reason', async () => {
      const rejected = {
        ...ready,
        data: { ...ready.data, verificationStatus: 'REJECTED', decidedAt: '2026-03-02T10:00:00Z', decidedByEmailMasked: 'a***@lady-go.test', rejectionReason: 'Selfie is blurry' },
      }
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(ready))
        .mockResolvedValueOnce(jsonResponse({ success: true, data: null, error: null }))
        .mockResolvedValueOnce(jsonResponse(rejected))
      vi.stubGlobal('fetch', fetchMock)

      renderReview()
      await waitFor(() => expect(screen.getByRole('button', { name: 'Reject driver' })).toBeEnabled())
      await userEvent.click(screen.getByRole('button', { name: 'Reject driver' }))
      await userEvent.type(screen.getByLabelText('Reason (required)'), 'Selfie is blurry')
      await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Reject driver' }))

      expect(await screen.findByText(/Driver rejected/)).toBeInTheDocument()
      expect(JSON.parse(fetchMock.mock.calls[1][0].body ? await fetchMock.mock.calls[1][0].clone().text() : '{}')).toEqual({ note: 'Selfie is blurry' })
      expect(screen.getByText(/Rejected on .* by a\*\*\*@lady-go\.test\. Reason: Selfie is blurry/)).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Reject driver' })).not.toBeInTheDocument()
    })
  })

  it('names each document, says whether it is needed and offers no review of legacy ones', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      ...reviewBody,
      data: {
        ...reviewBody.data,
        documents: [
          { id: 'd1', driverId: 'driver-123', type: 'VEHICLE_REGISTRATION', status: 'PENDING', createdAt: '2026-01-01T00:00:00Z' },
          { id: 'd2', driverId: 'driver-123', type: 'INSURANCE', status: 'PENDING', createdAt: '2026-01-01T00:00:00Z' },
          { id: 'd3', driverId: 'driver-123', type: 'VEHICLE_PHOTO', status: 'PENDING', createdAt: '2026-01-01T00:00:00Z' },
        ],
      },
    })))
    renderReview()
    await waitFor(() => expect(screen.getByText('Vehicle registration (RC) photo')).toBeInTheDocument())
    expect(screen.queryByText('VEHICLE_REGISTRATION')).not.toBeInTheDocument()
    expect(screen.getAllByText('Not required for two-wheelers')).toHaveLength(2)
    expect(screen.getAllByText('Required').length).toBeGreaterThan(0)
    // Only the required RC photo can be approved or rejected; legacy types are de-emphasised and have no buttons.
    expect(screen.getAllByRole('button', { name: 'Approve' })).toHaveLength(1)
    expect(screen.getByText('Insurance').closest('li')).toHaveClass('doc-not-required')
  })
})
