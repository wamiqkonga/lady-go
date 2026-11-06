import { render, screen, waitFor, within } from '@testing-library/react'
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

    await waitFor(() => expect(screen.getByText('Changed a setting')).toBeInTheDocument())
    expect(screen.getByText(/<img src=x onerror=alert\(1\)>6000/)).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('{"key"')
    expect(document.querySelector('td img')).toBeNull()
  })

  it('re-queries with an action filter', async () => {
    // A fresh Response per call - its body is a single-use stream, so reusing one mocked instance
    // across two real fetches (initial load, then the filtered re-query) breaks the second call.
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(page([]))))
    vi.stubGlobal('fetch', fetchMock)

    render(<AuditLog />)
    await waitFor(() => expect(screen.getByText('No entries match these filters.')).toBeInTheDocument())

    await userEvent.selectOptions(screen.getByLabelText('Action'), 'RIDE_FLAG_REVIEWED')
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

    await waitFor(() => expect(screen.getByText('Approved a driver')).toBeInTheDocument())
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })

  it('shows readable key/value details and the actor by name, or by masked email when unnamed', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          page([
            {
              id: 'log-1', actorName: 'Ayesha Admin', actorEmailMasked: 'a***@lady-go.test', actorRole: 'ADMIN',
              action: 'DRIVER_SUSPENDED', targetType: 'drivers', targetId: 'abcdef12-0000', metadata: { reason: 'Complaint verified' },
              createdAt: '2026-01-01T00:00:00Z',
            },
            { id: 'log-2', actorEmailMasked: 'z***@lady-go.test', actorRole: 'SUPPORT', action: 'DRIVER_DOCUMENT_VIEWED', createdAt: '2026-01-01T00:00:00Z' },
          ]),
        ),
      ),
    )
    render(<AuditLog />)
    expect(await screen.findByText('Ayesha Admin')).toBeInTheDocument()
    expect(screen.getByText('z***@lady-go.test')).toBeInTheDocument()
    const table = within(screen.getByRole('table'))
    expect(table.getByText('Suspended a driver')).toBeInTheDocument()
    expect(table.getByText('Driver')).toBeInTheDocument()
    expect(screen.getByText('Reason')).toBeInTheDocument()
    expect(screen.getByText('Complaint verified')).toBeInTheDocument()
  })

  it('sends an email typed into the Actor filter as actorEmail and anything else as actorId', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(page([]))))
    vi.stubGlobal('fetch', fetchMock)
    render(<AuditLog />)
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    await userEvent.type(screen.getByLabelText('Actor (email or ID)'), 'boss@lady-go.test')
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    const byEmail = (fetchMock.mock.calls[1][0] as Request).url
    expect(byEmail).toContain('actorEmail=boss%40lady-go.test')
    expect(byEmail).not.toContain('actorId=')

    await userEvent.clear(screen.getByLabelText('Actor (email or ID)'))
    await userEvent.type(screen.getByLabelText('Actor (email or ID)'), '11111111-2222-3333-4444-555555555555')
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    expect((fetchMock.mock.calls[2][0] as Request).url).toContain('actorId=11111111-2222-3333-4444-555555555555')
  })
})
