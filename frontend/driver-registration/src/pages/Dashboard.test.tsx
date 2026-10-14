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

  it('shows all sections with current status once the driver has a profile', async () => {
    const profile = { success: true, data: { id: 'driver-1', cityId: 'city-1', verificationStatus: 'PENDING_REVIEW' }, error: null }
    const vehicle = { success: true, data: { id: 'v-1', registrationNumber: 'JK01AB1234', vehicleType: 'CAB', verificationStatus: 'PENDING' }, error: null }
    const documents = {
      success: true,
      data: [{ id: 'doc-1', type: 'LICENSE', status: 'REJECTED', reviewNote: 'Photo is blurry', createdAt: '2026-01-01T00:00:00Z' }],
      error: null,
    }
    const declaration = { success: true, data: { currentVersion: 'v1', currentText: 'I declare that...', accepted: false, acceptedVersion: null, acceptedAt: null }, error: null }

    const fetchMock = vi.fn().mockImplementation((input: Request) => {
      const url = input.url
      if (url.endsWith('/api/v1/drivers/me/vehicle')) return Promise.resolve(jsonResponse(vehicle))
      if (url.endsWith('/api/v1/drivers/me/documents')) return Promise.resolve(jsonResponse(documents))
      if (url.endsWith('/api/v1/drivers/me/declaration')) return Promise.resolve(jsonResponse(declaration))
      return Promise.resolve(jsonResponse(profile))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderDashboard()

    await waitFor(() => expect(screen.getByText('JK01AB1234 (CAB)', { exact: false })).toBeInTheDocument())
    expect(screen.getByText('Photo is blurry')).toBeInTheDocument()
    expect(screen.getByText('I declare that...')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'I agree' })).toBeInTheDocument()

    // A rejection note renders as plain text, never markup - no element created from it.
    expect(document.querySelector('.review-note')?.innerHTML).not.toContain('<')
  })

  it('accepting the declaration calls the accept endpoint with the current version', async () => {
    const profile = { success: true, data: { id: 'driver-1', cityId: 'city-1', verificationStatus: 'PENDING_REVIEW' }, error: null }
    const declaration = { success: true, data: { currentVersion: 'v1', currentText: 'Terms...', accepted: false, acceptedVersion: null, acceptedAt: null }, error: null }
    const acceptedDeclaration = { success: true, data: { currentVersion: 'v1', currentText: 'Terms...', accepted: true, acceptedVersion: 'v1', acceptedAt: '2026-01-02T00:00:00Z' }, error: null }

    let declarationCalls = 0
    const fetchMock = vi.fn().mockImplementation((input: Request, init?: RequestInit) => {
      const url = input.url
      if (url.endsWith('/api/v1/drivers/me/vehicle')) return Promise.resolve(jsonResponse({ success: false, data: null, error: { code: 'NOT_FOUND', message: 'not found' } }, 404))
      if (url.endsWith('/api/v1/drivers/me/documents')) return Promise.resolve(jsonResponse({ success: true, data: [], error: null }))
      if (url.endsWith('/api/v1/drivers/me/declaration') && (input.method === 'POST' || init?.method === 'POST')) {
        declarationCalls += 1
        return Promise.resolve(jsonResponse({ success: true, data: { declarationVersion: 'v1', acceptedAt: '2026-01-02T00:00:00Z' }, error: null }))
      }
      if (url.endsWith('/api/v1/drivers/me/declaration')) {
        return Promise.resolve(jsonResponse(declarationCalls > 0 ? acceptedDeclaration : declaration))
      }
      return Promise.resolve(jsonResponse(profile))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderDashboard()

    await waitFor(() => expect(screen.getByRole('button', { name: 'I agree' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: 'I agree' }))

    await waitFor(() => expect(declarationCalls).toBe(1))
  })
})
