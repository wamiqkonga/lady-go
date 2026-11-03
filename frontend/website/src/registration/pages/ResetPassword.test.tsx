import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ResetPassword from './ResetPassword'

afterEach(() => vi.restoreAllMocks())

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderAt(hash: string) {
  return render(<MemoryRouter initialEntries={[`/captain/reset-password${hash}`]}><ResetPassword /></MemoryRouter>)
}

describe('ResetPassword', () => {
  it('sends the token from the URL fragment in the body and confirms', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json({ success: true, data: null, error: null }))
    vi.stubGlobal('fetch', fetchMock)
    renderAt('#token=abc123')
    await userEvent.type(screen.getByLabelText('New password'), 'my little pink scooter')
    await userEvent.type(screen.getByLabelText('Type it again'), 'my little pink scooter')
    await userEvent.click(screen.getByRole('button', { name: 'Save new password' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Your password was changed'))
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toContain('/api/v1/auth/driver/password/reset')
    expect(url).not.toContain('abc123')
    expect(JSON.parse(init.body)).toEqual({ token: 'abc123', newPassword: 'my little pink scooter' })
  })

  it('explains an expired or used link', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ success: false, data: null, error: { code: 'UNAUTHORIZED', message: 'x' } }, 401)))
    renderAt('#token=old')
    await userEvent.type(screen.getByLabelText('New password'), 'my little pink scooter')
    await userEvent.type(screen.getByLabelText('Type it again'), 'my little pink scooter')
    await userEvent.click(screen.getByRole('button', { name: 'Save new password' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('expired or was already used'))
  })

  it('does not show the form when the link has no token', () => {
    renderAt('')
    expect(screen.getByRole('alert')).toHaveTextContent('not complete')
    expect(screen.queryByLabelText('New password')).toBeNull()
  })
})
