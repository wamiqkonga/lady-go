import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DriverDocumentQueue from './DriverDocumentQueue'

afterEach(() => vi.restoreAllMocks())

function mockFetch(body: unknown, status = 200) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }),
    ),
  )
}

describe('DriverDocumentQueue', () => {
  it('lists pending documents with a link to the driver review', async () => {
    mockFetch({
      success: true,
      data: {
        content: [{ id: 'doc-1', driverId: 'driver-123', type: 'LICENSE', status: 'PENDING', createdAt: '2026-01-01T00:00:00Z' }],
        page: 0,
        size: 50,
        totalElements: 1,
        totalPages: 1,
      },
      error: null,
    })

    render(
      <MemoryRouter>
        <DriverDocumentQueue />
      </MemoryRouter>,
    )

    await waitFor(() => expect(screen.getByText('LICENSE')).toBeInTheDocument())
    expect(screen.getByRole('link', { name: /driver-1/ })).toHaveAttribute('href', '/driver-verification/driver-123')
  })

  it('shows the driver name, masked phone, and flags an expired document', async () => {
    mockFetch({
      success: true,
      data: {
        content: [{
          id: 'doc-1', driverId: 'driver-123', driverName: 'Asha Verma', driverPhoneMasked: '******4321',
          type: 'INSURANCE', status: 'APPROVED', expiresAt: '2020-01-01', createdAt: '2026-01-01T00:00:00Z',
        }],
        page: 0,
        size: 50,
        totalElements: 1,
        totalPages: 1,
      },
      error: null,
    })

    render(
      <MemoryRouter>
        <DriverDocumentQueue />
      </MemoryRouter>,
    )

    await waitFor(() => expect(screen.getByText('Asha Verma')).toBeInTheDocument())
    expect(screen.getByText('******4321')).toBeInTheDocument()
    expect(screen.getByText('expired')).toBeInTheDocument()
  })

  it('shows an empty state when nothing is pending', async () => {
    mockFetch({ success: true, data: { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }, error: null })

    render(
      <MemoryRouter>
        <DriverDocumentQueue />
      </MemoryRouter>,
    )

    await waitFor(() => expect(screen.getByText('No documents pending review.')).toBeInTheDocument())
  })

  it('surfaces a backend error instead of hanging on "Loading…"', async () => {
    mockFetch({ success: false, data: null, error: { code: 'FORBIDDEN', message: 'Not allowed.' } }, 403)

    render(
      <MemoryRouter>
        <DriverDocumentQueue />
      </MemoryRouter>,
    )

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Not allowed.'))
  })
})
