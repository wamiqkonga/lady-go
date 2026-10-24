import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AuditLog from './AuditLog'

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const page = (entries: unknown[]) => ({
  success: true,
  data: { content: entries, page: 0, size: 50, totalElements: entries.length, totalPages: 1 },
  error: null,
})

describe('AuditLog', () => {
  it('lists entries and renders metadata as plain text, never as markup', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          page([
            {
              id: 'log-1', actorRole: 'SUPER_ADMIN', action: 'CONFIGURATION_CHANGED',
              targetType: 'configuration', targetId: 'cfg-1',
              metadata: { key: 'driver.search.radius.m', oldValue: '5000', newValue: '<img src=x onerror=alert(1)>6000' },
              createdAt: '2026-01-01T00:00:00Z',
            },
          ]),
        ),
      ),
    )

    render(<AuditLog />)

    await waitFor(() => expect(screen.getByText('CONFIGURATION_CHANGED')).toBeInTheDocument())
    expect(screen.getByText(/<img src=x onerror=alert\(1\)>6000/)).toBeInTheDocument()
    expect(document.querySelector('td img')).toBeNull()
  })

  it('re-queries with an action filter', async () => {
    // A fresh Response per call - its body is a single-use stream, so reusing one mocked instance
    // across two real fetches (initial load, then the filtered re-query) breaks the second call.
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(page([]))))
    vi.stubGlobal('fetch', fetchMock)

    render(<AuditLog />)
    await waitFor(() => expect(screen.getByText('No entries match these filters.')).toBeInTheDocument())

    await userEvent.type(screen.getByPlaceholderText(/CONFIGURATION_CHANGED/), 'RIDE_FLAG_REVIEWED')
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    const request = fetchMock.mock.calls[1][0] as Request
    expect(request.url).toContain('action=RIDE_FLAG_REVIEWED')
  })

  it('shows a dash when an entry has no metadata, never an empty object', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          page([{ id: 'log-1', actorRole: 'ADMIN', action: 'DRIVER_APPROVED', createdAt: '2026-01-01T00:00:00Z' }]),
        ),
      ),
    )

    render(<AuditLog />)

    await waitFor(() => expect(screen.getByText('DRIVER_APPROVED')).toBeInTheDocument())
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })
})
