import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Customers from './Customers'
import CustomerDetail from './CustomerDetail'
import { SessionContext } from '../lib/sessionContext'
import { StepUpProvider } from '../lib/StepUpProvider'

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const listPage = {
  success: true,
  data: {
    content: [
      { id: 'cust-1', name: 'Aisha Khan', phoneMasked: '********1234', status: 'ACTIVE', createdAt: '2026-01-01T00:00:00Z' },
      { id: 'cust-2', name: 'Zara Mir', phoneMasked: '********5678', status: 'SUSPENDED', createdAt: '2026-01-02T00:00:00Z' },
    ],
    page: 0, size: 50, totalElements: 2, totalPages: 1,
  },
  error: null,
}

const activeDetail = {
  id: 'cust-1', name: 'Aisha Khan', phoneMasked: '********1234', status: 'ACTIVE', createdAt: '2026-01-01T00:00:00Z',
  totalRides: 2, completedRides: 1, cancelledRides: 1,
  rides: [{ bookingId: 'b-1', status: 'COMPLETED', createdAt: '2026-01-03T00:00:00Z', estimatedFare: 18000, finalFare: 18500, currency: 'INR' }],
}

const stepUpRequired = {
  success: false, data: null, error: { code: 'STEP_UP_REQUIRED', message: 'Confirm your identity with a fresh authenticator code to continue.' },
}

function renderDetail(role: string) {
  return render(
    <MemoryRouter initialEntries={['/customers/cust-1']}>
      <SessionContext.Provider value={{ signedIn: true, role, signOut: vi.fn() }}>
        <StepUpProvider>
          <Routes>
            <Route path="customers/:customerId" element={<CustomerDetail />} />
          </Routes>
        </StepUpProvider>
      </SessionContext.Provider>
    </MemoryRouter>,
  )
}

describe('Customers list', () => {
  it('shows masked phones and status, links to the detail page, and searches by the typed text', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(listPage))
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter><Customers /></MemoryRouter>)

    await waitFor(() => expect(screen.getByRole('link', { name: 'Aisha Khan' })).toHaveAttribute('href', '/customers/cust-1'))
    expect(screen.getByText('********1234')).toBeInTheDocument()
    expect(screen.getByText('SUSPENDED', { selector: '.status-badge' })).toBeInTheDocument()
    // Every cell carries a label so the table can collapse to cards on a phone.
    expect(screen.getAllByText('********1234')[0]).toHaveAttribute('data-label', 'Phone')

    await userEvent.type(screen.getByPlaceholderText(/Name, phone or customer id/), 'zara')
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => {
      const last = fetchMock.mock.calls.at(-1)![0] as Request
      expect(last.url).toContain('q=zara')
    })
  })

  it('shows an empty state and a server error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(jsonResponse({ success: true, data: { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }, error: null })))
    const { unmount } = render(<MemoryRouter><Customers /></MemoryRouter>)
    await waitFor(() => expect(screen.getByText('No customers match.')).toBeInTheDocument())
    unmount()

    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(jsonResponse({ success: false, data: null, error: { code: 'FORBIDDEN', message: 'You do not have permission to perform this action.' } }, 403)))
    render(<MemoryRouter><Customers /></MemoryRouter>)
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('permission'))
  })
})

describe('Customer detail', () => {
  it('shows counts and ride history with the fare', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ success: true, data: activeDetail, error: null })))
    renderDetail('SUPPORT')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Aisha Khan' })).toBeInTheDocument())
    expect(screen.getByRole('link', { name: 'COMPLETED' })).toHaveAttribute('href', '/rides/b-1')
    expect(screen.getByText(/185\.00/)).toBeInTheDocument()
    // SUPPORT is read-only (cosmetic - the backend answers 403 anyway).
    expect(screen.queryByRole('button', { name: 'Suspend customer' })).not.toBeInTheDocument()
  })

  it('suspend needs a reason, then the step-up prompt, then retries and shows SUSPENDED', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ success: true, data: activeDetail, error: null })) // load
      .mockResolvedValueOnce(jsonResponse(stepUpRequired, 403)) // first suspend
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { accessToken: 'new.token.value' }, error: null })) // step-up
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { ...activeDetail, status: 'SUSPENDED' }, error: null })) // retry
    vi.stubGlobal('fetch', fetchMock)
    renderDetail('ADMIN')

    await userEvent.click(await screen.findByRole('button', { name: 'Suspend customer' }))
    const confirmButton = screen.getByRole('button', { name: 'Suspend customer' })
    expect(confirmButton).toBeDisabled() // no reason yet.
    await userEvent.type(screen.getByLabelText(/Reason/), 'repeated abuse')
    await userEvent.click(confirmButton)

    const dialog = await screen.findByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Authenticator code'), '123456')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(screen.getByText('SUSPENDED', { selector: '.status-badge' })).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Re-activate customer' })).toBeInTheDocument()
    const retried = fetchMock.mock.calls[3][0] as Request
    expect(retried.url).toContain('/admin/customers/cust-1/suspend')
    expect(JSON.parse(await retried.clone().text())).toEqual({ reason: 'repeated abuse' })
  })

  it('re-activate sends the reason to the activate endpoint', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { ...activeDetail, status: 'SUSPENDED' }, error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: activeDetail, error: null }))
    vi.stubGlobal('fetch', fetchMock)
    renderDetail('SUPER_ADMIN')

    await userEvent.click(await screen.findByRole('button', { name: 'Re-activate customer' }))
    await userEvent.type(screen.getByLabelText(/Reason/), 'appeal accepted')
    await userEvent.click(screen.getByRole('button', { name: 'Re-activate customer' }))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Suspend customer' })).toBeInTheDocument())
    expect((fetchMock.mock.calls[1][0] as Request).url).toContain('/admin/customers/cust-1/activate')
  })

  it('shows a server error and an authenticator pointer when MFA is not set up', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ success: true, data: activeDetail, error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: false, data: null, error: { code: 'MFA_ENROLMENT_REQUIRED', message: 'Set up an authenticator app on the Security page before doing this.' } }, 403))
    vi.stubGlobal('fetch', fetchMock)
    renderDetail('ADMIN')

    await userEvent.click(await screen.findByRole('button', { name: 'Suspend customer' }))
    await userEvent.type(screen.getByLabelText(/Reason/), 'abuse')
    await userEvent.click(screen.getByRole('button', { name: 'Suspend customer' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Security page'))
    expect(screen.getByRole('link', { name: /set up your authenticator/i })).toHaveAttribute('href', '/security')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
