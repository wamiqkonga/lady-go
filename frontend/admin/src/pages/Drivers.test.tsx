import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Drivers from './Drivers'

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const page = (content: unknown[], totalPages = 1) => ({
  success: true,
  data: { content, page: 0, size: 20, totalElements: content.length, totalPages },
  error: null,
})

const asha = {
  id: 'driver-1', name: 'Asha Verma', phoneMasked: '******4321', verificationStatus: 'APPROVED',
  availabilityStatus: 'ONLINE', ratingAvg: 4.5, totalRides: 12, createdAt: '2026-01-01T00:00:00Z',
}

function renderPage() {
  return render(
    <MemoryRouter>
      <Drivers />
    </MemoryRouter>,
  )
}

describe('Drivers list', () => {
  it('shows each driver with a masked phone and a link to the detail page', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(page([asha]))))

    renderPage()

    const link = await screen.findByRole('link', { name: 'Asha Verma' })
    expect(link).toHaveAttribute('href', '/drivers/driver-1')
    expect(screen.getByText('******4321')).toBeInTheDocument()
    // Cells carry data-label so the table can collapse to cards on a phone.
    expect(screen.getByText('******4321').closest('a')).toHaveAttribute('data-label', 'Phone')
    expect(screen.getByText('4.50')).toBeInTheDocument()
  })

  it('shows each status once in words, "No ratings yet" for an unrated driver and a fallback for no name', async () => {
    const unrated = { ...asha, id: 'driver-2', name: null, ratingAvg: 0, verificationStatus: 'SUSPENDED', availabilityStatus: 'SUSPENDED' }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(page([unrated]))))
    renderPage()
    expect(await screen.findByText('Name not provided')).toBeInTheDocument()
    expect(screen.getByText('No ratings yet')).toBeInTheDocument()
    expect(screen.queryByText('0.00')).not.toBeInTheDocument()
    expect(document.querySelectorAll('tbody tr td a[href="/drivers/driver-2"]')).toHaveLength(6)
  })

  it('sends the search text and the status filter to the server', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(page([asha])))
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    await screen.findByRole('link', { name: 'Asha Verma' })

    await userEvent.type(screen.getByLabelText('Search'), 'asha')
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'SUSPENDED')
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    const url = new URL((fetchMock.mock.calls[1][0] as Request).url)
    expect(url.searchParams.get('q')).toBe('asha')
    expect(url.searchParams.get('verificationStatus')).toBe('SUSPENDED')
    expect(url.searchParams.get('page')).toBe('0')
  })

  it('says so when nothing matches', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(page([]))))
    renderPage()
    expect(await screen.findByText('No drivers match.')).toBeInTheDocument()
  })

  it('shows the server error instead of an empty table', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ success: false, data: null, error: { code: 'FORBIDDEN', message: 'Not allowed.' } }, 403)),
    )
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('Not allowed.')
  })

  it('pages forward', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(page([asha], 2)))
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    await screen.findByText('Page 1 of 2')

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(new URL((fetchMock.mock.calls[1][0] as Request).url).searchParams.get('page')).toBe('1')
  })
})
