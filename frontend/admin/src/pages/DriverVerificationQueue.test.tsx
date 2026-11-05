import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DriverVerificationQueue, { formatWaiting, summariseItems } from './DriverVerificationQueue'

afterEach(() => vi.restoreAllMocks())

const page = (content: unknown[], extra: Record<string, unknown> = {}) => ({
  success: true,
  data: { content, page: 0, size: 20, totalElements: content.length, totalPages: 1, ...extra },
  error: null,
})

function mockFetch(body: unknown, status = 200) {
  const fn = vi.fn().mockImplementation(
    async () => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }),
  )
  vi.stubGlobal('fetch', fn)
  return fn
}

const row = (over: Record<string, unknown> = {}) => ({
  driverId: 'driver-123',
  driverName: 'Asha Verma',
  phoneMasked: '******4321',
  city: 'Srinagar',
  vehicleNumber: 'JK01AB1234',
  verificationStatus: 'PENDING_REVIEW',
  requiredItems: { approved: 1, pending: 2, rejected: 0, missing: 0 },
  optionalDocumentsProvided: true,
  readyForReview: true,
  submittedAt: '2026-11-01T00:00:00Z',
  waitingSeconds: 5 * 3600 + 20 * 60,
  overdue: false,
  ...over,
})

const renderPage = () =>
  render(
    <MemoryRouter>
      <DriverVerificationQueue />
    </MemoryRouter>,
  )

describe('DriverVerificationQueue', () => {
  it('lists captains with counts, a plain wait time and a link to the review page', async () => {
    mockFetch(page([row()]))
    renderPage()

    expect(await screen.findByRole('link', { name: 'Asha Verma' })).toHaveAttribute('href', '/driver-verification/driver-123')
    expect(screen.getByText('******4321')).toBeInTheDocument()
    expect(screen.getByText('JK01AB1234')).toBeInTheDocument()
    expect(screen.getByText('2 pending · 1 approved')).toBeInTheDocument()
    expect(screen.getByText('5h 20m')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Review' })).toHaveAttribute('href', '/driver-verification/driver-123')
    expect(screen.queryByText(/over 24h/)).not.toBeInTheDocument()
  })

  it('flags captains waiting more than 24 hours', async () => {
    mockFetch(page([row({ overdue: true, waitingSeconds: 30 * 3600 })]))
    renderPage()
    expect(await screen.findByText('1d 6h · over 24h')).toHaveClass('warn')
  })

  it('keeps the document queue one click away', async () => {
    mockFetch(page([]))
    renderPage()
    expect(await screen.findByRole('link', { name: 'Documents' })).toHaveAttribute('href', '/driver-verification/documents')
  })

  it('says plainly when nobody is waiting', async () => {
    mockFetch(page([], { totalElements: 0, totalPages: 0 }))
    renderPage()
    expect(await screen.findByText(/No captains are waiting for review/)).toBeInTheDocument()
  })

  it('shows the error with a retry that loads again', async () => {
    const fn = mockFetch({ success: false, data: null, error: { message: 'Boom' } }, 500)
    renderPage()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    fn.mockImplementation(async () => new Response(JSON.stringify(page([row()])), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('link', { name: 'Asha Verma' })).toBeInTheDocument()
  })

  it('sends the search and the filters to the server', async () => {
    const fn = mockFetch(page([row()]))
    renderPage()
    await screen.findByRole('link', { name: 'Asha Verma' })

    await userEvent.type(screen.getByLabelText('Search'), 'asha')
    await userEvent.click(screen.getByLabelText('Waiting over 24 hours'))
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))

    await waitFor(() => {
      const url = String((fn.mock.calls.at(-1)![0] as Request).url)
      expect(url).toContain('q=asha')
      expect(url).toContain('overdueOnly=true')
      expect(url).toContain('status=PENDING_REVIEW')
    })
  })
})

describe('summariseItems', () => {
  it('leaves out zero counts', () => {
    expect(summariseItems({ approved: 1, pending: 0, rejected: 1, missing: 1 })).toBe('1 rejected · 1 missing · 1 approved')
  })
})

describe('formatWaiting', () => {
  it('uses the two biggest units', () => {
    expect(formatWaiting(20)).toBe('under 1m')
    expect(formatWaiting(125)).toBe('2m')
    expect(formatWaiting(3 * 3600 + 5 * 60)).toBe('3h 5m')
    expect(formatWaiting(50 * 3600)).toBe('2d 2h')
  })
})
