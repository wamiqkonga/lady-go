import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Dashboard from './Dashboard'
import { SessionContext } from '../lib/sessionContext'

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderDashboard() {
  return render(
    <SessionContext.Provider value={{ signedIn: true, signOut: vi.fn() }}>
      <Dashboard />
    </SessionContext.Provider>,
  )
}

describe('Dashboard', () => {
  it('shows a city picker for a brand-new driver with no profile yet', async () => {
    // GET /drivers/me 404s for a driver with no Driver row at all - confirmed against the real
    // backend (DriverService.resolveDriver throws ResourceNotFoundException), not a 200 with
    // verificationStatus "NOT_SUBMITTED". This mock matches that, not an assumption.
    const fetchMock = vi
      .fn()
      .mockImplementation((input: Request) => {
        const url = input.url
        if (url.endsWith('/api/v1/cities')) {
          return Promise.resolve(jsonResponse({ success: true, data: [{ id: 'city-1', name: 'Srinagar', country: 'IN' }], error: null }))
        }
        return Promise.resolve(jsonResponse({ success: false, data: null, error: { code: 'NOT_FOUND', message: 'not found' } }, 404))
      })
    vi.stubGlobal('fetch', fetchMock)

    renderDashboard()

    await waitFor(() => expect(screen.getByText('Which city will you drive in?')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByRole('option', { name: 'Srinagar' })).toBeInTheDocument())
  })

  const ALL_TYPES = ['LICENSE', 'VEHICLE_REGISTRATION', 'VEHICLE_PHOTO', 'INSURANCE', 'FITNESS_CERTIFICATE', 'POLLUTION_CERTIFICATE', 'SELFIE']
  const profile = { success: true, data: { id: 'driver-1', cityId: 'city-1', verificationStatus: 'PENDING_REVIEW' }, error: null }
  const vehicle = { success: true, data: { id: 'v-1', registrationNumber: 'JK01AB1234', vehicleType: 'CAB', verificationStatus: 'PENDING' }, error: null }
  const notFound = { success: false, data: null, error: { code: 'NOT_FOUND', message: 'not found' } }
  const pendingDeclaration = { success: true, data: { currentVersion: 'v1', currentText: 'I declare that...', accepted: false, acceptedVersion: null, acceptedAt: null }, error: null }

  function docs(overrides: Record<string, object> = {}) {
    return {
      success: true,
      error: null,
      data: ALL_TYPES.map((type, i) => ({
        id: `doc-${i}`, type, status: 'PENDING', createdAt: '2026-01-01T00:00:00Z', ...overrides[type],
      })),
    }
  }

  it('resumes on a rejected document and shows the reviewer note as plain text', async () => {
    const documents = docs({ LICENSE: { status: 'REJECTED', reviewNote: 'Photo is blurry' } })
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: Request) => {
      const url = input.url
      if (url.endsWith('/api/v1/drivers/me/vehicle')) return Promise.resolve(jsonResponse(vehicle))
      if (url.endsWith('/api/v1/drivers/me/documents')) return Promise.resolve(jsonResponse(documents))
      if (url.endsWith('/api/v1/drivers/me/declaration')) return Promise.resolve(jsonResponse(pendingDeclaration))
      return Promise.resolve(jsonResponse(profile))
    }))

    renderDashboard()

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Driving licence' })).toBeInTheDocument())
    expect(screen.getByText('Photo is blurry')).toBeInTheDocument()
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '7')
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
    // A rejection note renders as plain text, never markup - no element created from it.
    expect(document.querySelector('.review-note')?.innerHTML).not.toContain('<')
  })

  it('does not call the application "under review" while steps are still missing', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: Request) => {
      const url = input.url
      if (url.endsWith('/api/v1/drivers/me/vehicle')) return Promise.resolve(jsonResponse(notFound, 404))
      if (url.endsWith('/api/v1/drivers/me/documents')) return Promise.resolve(jsonResponse({ success: true, data: [], error: null }))
      if (url.endsWith('/api/v1/drivers/me/declaration')) return Promise.resolve(jsonResponse(pendingDeclaration))
      return Promise.resolve(jsonResponse(profile))
    }))

    renderDashboard()

    await waitFor(() => expect(screen.getByLabelText('Registration number')).toBeInTheDocument())
    expect(screen.getByText('In progress')).toBeInTheDocument()
    expect(screen.queryByText('Under review')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
  })

  it('accepting the declaration needs the checkbox, then calls the accept endpoint with the current version', async () => {
    const acceptedDeclaration = { success: true, data: { currentVersion: 'v1', currentText: 'Terms...', accepted: true, acceptedVersion: 'v1', acceptedAt: '2026-01-02T00:00:00Z' }, error: null }
    let declarationCalls = 0
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: Request, init?: RequestInit) => {
      const url = input.url
      if (url.endsWith('/api/v1/drivers/me/vehicle')) return Promise.resolve(jsonResponse(vehicle))
      if (url.endsWith('/api/v1/drivers/me/documents')) return Promise.resolve(jsonResponse(docs()))
      if (url.endsWith('/api/v1/drivers/me/declaration') && (input.method === 'POST' || init?.method === 'POST')) {
        declarationCalls += 1
        return Promise.resolve(jsonResponse({ success: true, data: { declarationVersion: 'v1', acceptedAt: '2026-01-02T00:00:00Z' }, error: null }))
      }
      if (url.endsWith('/api/v1/drivers/me/declaration')) {
        return Promise.resolve(jsonResponse(declarationCalls > 0 ? acceptedDeclaration : { ...pendingDeclaration, data: { ...pendingDeclaration.data, currentText: 'Terms...' } }))
      }
      return Promise.resolve(jsonResponse(profile))
    }))

    renderDashboard()

    await waitFor(() => expect(screen.getByRole('button', { name: 'I agree' })).toBeDisabled())
    await userEvent.click(screen.getByLabelText(/I have read and understood/))
    await userEvent.click(screen.getByRole('button', { name: 'I agree' }))

    await waitFor(() => expect(declarationCalls).toBe(1))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled())
  })

  it('shows a plain error when accepting the declaration fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: Request, init?: RequestInit) => {
      const url = input.url
      if (url.endsWith('/api/v1/drivers/me/vehicle')) return Promise.resolve(jsonResponse(vehicle))
      if (url.endsWith('/api/v1/drivers/me/documents')) return Promise.resolve(jsonResponse(docs()))
      if (url.endsWith('/api/v1/drivers/me/declaration') && (input.method === 'POST' || init?.method === 'POST')) {
        return Promise.resolve(jsonResponse({ success: false, data: null, error: { code: 'X', message: 'Server unavailable' } }, 500))
      }
      if (url.endsWith('/api/v1/drivers/me/declaration')) return Promise.resolve(jsonResponse(pendingDeclaration))
      return Promise.resolve(jsonResponse(profile))
    }))

    renderDashboard()

    await waitFor(() => expect(screen.getByLabelText(/I have read and understood/)).toBeInTheDocument())
    await userEvent.click(screen.getByLabelText(/I have read and understood/))
    await userEvent.click(screen.getByRole('button', { name: 'I agree' }))

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
  })

  it('waits for the vehicle before resuming, so a saved vehicle is not sent back to step 1', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: Request) => {
      const url = input.url
      if (url.endsWith('/api/v1/drivers/me/vehicle')) {
        return new Promise((resolve) => setTimeout(() => resolve(jsonResponse(vehicle)), 150))
      }
      if (url.endsWith('/api/v1/drivers/me/documents')) return Promise.resolve(jsonResponse({ success: true, data: [], error: null }))
      if (url.endsWith('/api/v1/drivers/me/declaration')) return Promise.resolve(jsonResponse(pendingDeclaration))
      return Promise.resolve(jsonResponse(profile))
    }))

    renderDashboard()

    // Vehicle exists, documents do not: first unfinished step is the licence, not the vehicle.
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Driving licence' })).toBeInTheDocument())
  })

  it('does not treat a failed vehicle load as "no vehicle yet"', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: Request) => {
      const url = input.url
      if (url.endsWith('/api/v1/drivers/me/vehicle')) {
        return Promise.resolve(jsonResponse({ success: false, data: null, error: { code: 'X', message: 'boom' } }, 500))
      }
      if (url.endsWith('/api/v1/drivers/me/documents')) return Promise.resolve(jsonResponse(docs()))
      if (url.endsWith('/api/v1/drivers/me/declaration')) return Promise.resolve(jsonResponse(pendingDeclaration))
      return Promise.resolve(jsonResponse(profile))
    }))

    renderDashboard()

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Could not load your vehicle.'))
    expect(screen.queryByLabelText('Registration number')).not.toBeInTheDocument()
  })

  it('does not say "Application received" to a rejected driver', async () => {
    const rejectedProfile = { success: true, data: { id: 'driver-1', cityId: 'city-1', verificationStatus: 'REJECTED' }, error: null }
    const accepted = { success: true, data: { currentVersion: 'v1', currentText: 'x', accepted: true, acceptedVersion: 'v1', acceptedAt: 'x' }, error: null }
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: Request) => {
      const url = input.url
      if (url.endsWith('/api/v1/drivers/me/vehicle')) return Promise.resolve(jsonResponse(vehicle))
      if (url.endsWith('/api/v1/drivers/me/documents')) return Promise.resolve(jsonResponse(docs()))
      if (url.endsWith('/api/v1/drivers/me/declaration')) return Promise.resolve(jsonResponse(accepted))
      return Promise.resolve(jsonResponse(rejectedProfile))
    }))

    renderDashboard()

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Application update' })).toBeInTheDocument())
    expect(screen.queryByText('Application received')).not.toBeInTheDocument()
  })

  it('keeps the declaration accepted even if the follow-up re-fetch fails', async () => {
    let posted = false
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: Request, init?: RequestInit) => {
      const url = input.url
      if (url.endsWith('/api/v1/drivers/me/vehicle')) return Promise.resolve(jsonResponse(vehicle))
      if (url.endsWith('/api/v1/drivers/me/documents')) return Promise.resolve(jsonResponse(docs()))
      if (url.endsWith('/api/v1/drivers/me/declaration') && (input.method === 'POST' || init?.method === 'POST')) {
        posted = true
        return Promise.resolve(jsonResponse({ success: true, data: { declarationVersion: 'v1', acceptedAt: 'x' }, error: null }))
      }
      if (url.endsWith('/api/v1/drivers/me/declaration')) {
        return posted ? Promise.reject(new TypeError('offline')) : Promise.resolve(jsonResponse(pendingDeclaration))
      }
      return Promise.resolve(jsonResponse(profile))
    }))

    renderDashboard()

    await waitFor(() => expect(screen.getByLabelText(/I have read and understood/)).toBeInTheDocument())
    await userEvent.click(screen.getByLabelText(/I have read and understood/))
    await userEvent.click(screen.getByRole('button', { name: 'I agree' }))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled())
  })
})
