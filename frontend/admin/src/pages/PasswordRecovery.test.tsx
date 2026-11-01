import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ForgotPassword from './ForgotPassword'
import Login from './Login'
import ResetPassword from './ResetPassword'

afterEach(() => {
  vi.restoreAllMocks()
  window.location.hash = ''
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
const ok = { success: true, data: null, error: null }
const failure = (code: string, status: number) =>
  jsonResponse({ success: false, data: null, error: { code, message: 'x' } }, status)

describe('Login', () => {
  it('links to the forgot-password page', () => {
    render(<MemoryRouter><Login /></MemoryRouter>)

    expect(screen.getByRole('link', { name: 'Forgot password?' })).toHaveAttribute('href', '/forgot-password')
  })
})

describe('ForgotPassword', () => {
  it('shows the same confirmation whatever the address, and sends only the email', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(ok))
    vi.stubGlobal('fetch', fetchMock)

    render(<MemoryRouter><ForgotPassword /></MemoryRouter>)
    await userEvent.type(screen.getByLabelText('Admin email'), 'someone@example.test')
    await userEvent.click(screen.getByRole('button', { name: 'Send reset link' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('If that email belongs to an admin account'))
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/v1/auth/admin/password/forgot')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string)).toEqual({ email: 'someone@example.test' })
  })

  it('shows a friendly message when the server is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(failure('INTERNAL_ERROR', 500)))

    render(<MemoryRouter><ForgotPassword /></MemoryRouter>)
    await userEvent.type(screen.getByLabelText('Admin email'), 'a@b.co')
    await userEvent.click(screen.getByRole('button', { name: 'Send reset link' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong'))
  })
})

describe('ResetPassword', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/reset-password#token=abcDEF_123-xyz')
  })

  it('takes the token from the link, removes it from the address bar, and sends it with the new password', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(ok))
    vi.stubGlobal('fetch', fetchMock)

    render(<MemoryRouter><ResetPassword /></MemoryRouter>)
    expect(window.location.hash).toBe('')

    const submit = screen.getByRole('button', { name: 'Change password' })
    expect(submit).toBeDisabled()
    await userEvent.type(screen.getByLabelText(/New password/), 'a-long-new-passphrase')
    await userEvent.type(screen.getByLabelText('Repeat the new password'), 'a-long-new-passphrase')
    await userEvent.type(screen.getByLabelText(/Authenticator code/), '123456')
    await userEvent.click(submit)

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('was changed'))
    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string)).toEqual({
      token: 'abcDEF_123-xyz', newPassword: 'a-long-new-passphrase', totpCode: '123456',
    })
    expect(screen.getByRole('link', { name: 'Go to sign in' })).toHaveAttribute('href', '/login')
  })

  it('does not enable submit for a short or mismatched password', async () => {
    vi.stubGlobal('fetch', vi.fn())

    render(<MemoryRouter><ResetPassword /></MemoryRouter>)
    await userEvent.type(screen.getByLabelText(/New password/), 'short')
    expect(screen.getByText(/more characters needed/)).toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText(/New password/))
    await userEvent.type(screen.getByLabelText(/New password/), 'a-long-new-passphrase')
    await userEvent.type(screen.getByLabelText('Repeat the new password'), 'something-different-1')

    expect(screen.getByText('The two passwords do not match.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Change password' })).toBeDisabled()
  })

  it('does not reveal whether the link or the code was wrong', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(failure('UNAUTHORIZED', 401)))

    render(<MemoryRouter><ResetPassword /></MemoryRouter>)
    await userEvent.type(screen.getByLabelText(/New password/), 'a-long-new-passphrase')
    await userEvent.type(screen.getByLabelText('Repeat the new password'), 'a-long-new-passphrase')
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }))

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('invalid, expired or already used, or the authenticator code is wrong'),
    )
  })

  it('tells the user to slow down when rate limited', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(failure('RATE_LIMITED', 429)))

    render(<MemoryRouter><ResetPassword /></MemoryRouter>)
    await userEvent.type(screen.getByLabelText(/New password/), 'a-long-new-passphrase')
    await userEvent.type(screen.getByLabelText('Repeat the new password'), 'a-long-new-passphrase')
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Too many attempts'))
  })

  it('without a token in the link it explains and offers a new link', () => {
    window.history.replaceState(null, '', '/reset-password')

    render(<MemoryRouter><ResetPassword /></MemoryRouter>)

    expect(screen.getByRole('alert')).toHaveTextContent('needs the link from your reset email')
    expect(screen.getByRole('link', { name: 'Request a new link' })).toHaveAttribute('href', '/forgot-password')
  })
})
