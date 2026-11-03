import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DriverDetail from './DriverDetail'
import { SessionContext } from '../lib/sessionContext'
import { StepUpProvider } from '../lib/StepUpProvider'

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function driverBody(verificationStatus: string, availabilityStatus = 'OFFLINE') {
  return {
    success: true,
    error: null,
    data: {
      id: 'driver-1', name: 'Asha Verma', phoneMasked: '******4321', verificationStatus, availabilityStatus,
      ratingAvg: 4.5, ratingCount: 3, totalRides: 2, createdAt: '2026-01-01T00:00:00Z',
      vehicle: { id: 'v1', registrationNumber: 'JK01AB1234', vehicleType: 'BIKE', verificationStatus: 'APPROVED' },
      documents: { total: 2, pending: 0, approved: 2, rejected: 0, reviewPath: '/driver-verification/driver-1' },
      recentRides: [{ bookingId: 'b1', status: 'COMPLETED', createdAt: '2026-01-02T00:00:00Z', finalFare: 15000, currency: 'INR' }],
      earnings: { rides: 2, gross: 30000, commission: 3000, net: 27000 },
    },
  }
}

const stepUpRequired = {
  success: false, data: null, error: { code: 'STEP_UP_REQUIRED', message: 'Confirm it is you.' },
}

function renderAs(role: string) {
  return render(
    <MemoryRouter initialEntries={['/drivers/driver-1']}>
      <SessionContext.Provider value={{ signedIn: true, role, signOut: vi.fn() }}>
        <StepUpProvider>
          <Routes>
            <Route path="/drivers/:driverId" element={<DriverDetail />} />
          </Routes>
        </StepUpProvider>
      </SessionContext.Provider>
    </MemoryRouter>,
  )
}

describe('DriverDetail', () => {
  it('shows the profile, earnings, recent rides and a link to the document review screen', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(driverBody('APPROVED', 'ONLINE'))))

    renderAs('SUPPORT')

    expect(await screen.findByRole('heading', { name: 'Asha Verma' })).toBeInTheDocument()
    expect(screen.getByText(/JK01AB1234/)).toBeInTheDocument()
    expect(screen.getByText(/gross ₹300.00/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open document review' })).toHaveAttribute('href', '/driver-verification/driver-1')
    expect(screen.getByRole('link', { name: /1\/2\/2026|2\/1\/2026|2026/ })).toHaveAttribute('href', '/rides/b1')
  })

  it('hides suspend for SUPPORT (cosmetic - the backend still enforces the role)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(driverBody('APPROVED'))))
    renderAs('SUPPORT')
    await screen.findByRole('heading', { name: 'Asha Verma' })
    expect(screen.queryByRole('button', { name: 'Suspend driver' })).not.toBeInTheDocument()
  })

  it('will not submit a suspension without a reason', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(driverBody('APPROVED')))
    vi.stubGlobal('fetch', fetchMock)
    renderAs('ADMIN')

    await userEvent.click(await screen.findByRole('button', { name: 'Suspend driver' }))
    await userEvent.click(screen.getByRole('button', { name: 'Confirm suspend' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Enter a reason')
    expect(fetchMock).toHaveBeenCalledTimes(1) // only the initial load.
  })

  it('asks for a reason, then the step-up code, then suspends and reloads', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(driverBody('APPROVED', 'ONLINE'))) // load
      .mockResolvedValueOnce(jsonResponse(stepUpRequired, 403)) // first suspend attempt
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { accessToken: 'upgraded', expiresInSeconds: 600 }, error: null })) // step-up
      .mockResolvedValueOnce(jsonResponse({ success: true, data: null, error: null })) // suspend retried
      .mockResolvedValueOnce(jsonResponse(driverBody('SUSPENDED', 'SUSPENDED'))) // reload
    vi.stubGlobal('fetch', fetchMock)
    renderAs('ADMIN')

    await userEvent.click(await screen.findByRole('button', { name: 'Suspend driver' }))
    await userEvent.type(screen.getByLabelText('Reason'), 'Complaint verified')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm suspend' }))

    const dialog = await screen.findByRole('dialog')
    await userEvent.type(dialog.querySelector('input')!, '123456')
    await userEvent.click(dialog.querySelector('button[type="submit"]')!)

    await waitFor(() => expect(screen.getByRole('button', { name: 'Re-activate driver' })).toBeInTheDocument())
    const suspendCall = fetchMock.mock.calls[3][0] as Request
    expect(suspendCall.url).toContain('/admin/drivers/driver-1/suspend')
    expect(JSON.parse(await suspendCall.clone().text())).toEqual({ reason: 'Complaint verified' })
  })

  it('shows the server message when the action fails and keeps the form open', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(driverBody('SUSPENDED', 'SUSPENDED')))
      .mockResolvedValueOnce(
        jsonResponse({ success: false, data: null, error: { code: 'VALIDATION_FAILED', message: 'Only a suspended driver can be re-activated.' } }, 400),
      )
    vi.stubGlobal('fetch', fetchMock)
    renderAs('SUPER_ADMIN')

    await userEvent.click(await screen.findByRole('button', { name: 'Re-activate driver' }))
    await userEvent.type(screen.getByLabelText('Reason'), 'Cleared')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm activate' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Only a suspended driver can be re-activated.')
    expect(screen.getByLabelText('Reason')).toBeInTheDocument()
  })

  it('shows an error for an unknown driver', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ success: false, data: null, error: { code: 'RESOURCE_NOT_FOUND', message: 'Not found.' } }, 404)),
    )
    renderAs('ADMIN')
    expect(await screen.findByRole('alert')).toHaveTextContent('Not found.')
  })
})
