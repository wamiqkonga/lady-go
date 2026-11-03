import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Login from './Login'

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('Login', () => {
  it('shows a busy loader on Send code until the code screen is visible', async () => {
    let release!: (r: Response) => void
    const pending = new Promise<Response>((resolve) => { release = resolve })
    vi.stubGlobal('fetch', vi.fn().mockReturnValueOnce(pending))
    render(<MemoryRouter><Login /></MemoryRouter>)
    await userEvent.type(screen.getByLabelText('Phone number'), '+919900000001')
    await userEvent.type(screen.getByLabelText('Email'), 'captain@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))

    const busyButton = await screen.findByRole('button', { name: /Sending your code/ })
    expect(busyButton).toBeDisabled()
    expect(busyButton.closest('form')).toHaveAttribute('aria-busy', 'true')
    expect(busyButton.querySelector('.spinner')).not.toBeNull()
    expect(screen.getByRole('status')).toHaveTextContent('Sending your code')
    expect(screen.queryByLabelText('Code')).toBeNull()

    release(jsonResponse({ success: true, data: { message: 'sent' }, error: null }))
    await waitFor(() => expect(screen.getByLabelText('Code')).toBeVisible())
    expect(screen.queryByText(/Sending your code/)).toBeNull()
  })

  it('clears the loader and shows the error when sending the code fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(jsonResponse({ success: false, data: null, error: { code: 'OTP_EMAIL_MISMATCH', message: 'Use the same phone number and email you signed up with.' } }, 409)))
    render(<MemoryRouter><Login /></MemoryRouter>)
    await userEvent.type(screen.getByLabelText('Phone number'), '+919900000001')
    await userEvent.type(screen.getByLabelText('Email'), 'captain@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Use the same phone number'))
    const button = screen.getByRole('button', { name: 'Send code' })
    expect(button).toBeEnabled()
    expect(button.closest('form')).toHaveAttribute('aria-busy', 'false')
  })

  it('sends a code then verifies it, requesting the DRIVER role', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { message: 'If this number is valid, a code was sent.' }, error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { accessToken: 'at-1', refreshToken: 'rt-1' }, error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { passwordSet: true }, error: null }))
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MemoryRouter>
        <Login />
      </MemoryRouter>,
    )

    await userEvent.type(screen.getByLabelText('Phone number'), '+919900000001')
    await userEvent.type(screen.getByLabelText('Email'), 'captain@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))

    await waitFor(() => expect(screen.getByLabelText('Code')).toBeVisible())
    const requestCall = JSON.parse(fetchMock.mock.calls[0][1].body)
    expect(requestCall).toMatchObject({ phone: '+919900000001', email: 'captain@example.com', purpose: 'LOGIN', intendedRole: 'DRIVER' })

    await userEvent.type(screen.getByLabelText('Code'), '123456')
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    const verifyCall = JSON.parse(fetchMock.mock.calls[1][1].body)
    expect(verifyCall).toMatchObject({ phone: '+919900000001', code: '123456', purpose: 'LOGIN', intendedRole: 'DRIVER' })
  })

  it('adds +91 to a plain 10-digit number before asking for the code, and uses it for verifying too', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { message: 'sent' }, error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { accessToken: 'at-1', refreshToken: 'rt-1' }, error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { passwordSet: true }, error: null }))
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MemoryRouter>
        <Login />
      </MemoryRouter>,
    )

    await userEvent.type(screen.getByLabelText('Phone number'), '90866 60267')
    await userEvent.type(screen.getByLabelText('Email'), 'captain@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))

    await waitFor(() => expect(screen.getByLabelText('Code')).toBeVisible())
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toMatchObject({ phone: '+919086660267' })

    await userEvent.type(screen.getByLabelText('Code'), '123456')
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toMatchObject({ phone: '+919086660267' })
  })

  it('explains a number that cannot be right without calling the server', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MemoryRouter>
        <Login />
      </MemoryRouter>,
    )

    await userEvent.type(screen.getByLabelText('Phone number'), '12345')
    await userEvent.type(screen.getByLabelText('Email'), 'captain@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))

    expect(screen.getByRole('alert')).toHaveTextContent('10-digit mobile number')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('shows a clear error for a wrong code and lets the captain retry', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { message: 'sent' }, error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: false, data: null, error: { code: 'VALIDATION_FAILED', message: 'Incorrect code or phone number.' } }, 400))
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MemoryRouter>
        <Login />
      </MemoryRouter>,
    )

    await userEvent.type(screen.getByLabelText('Phone number'), '+919900000002')
    await userEvent.type(screen.getByLabelText('Email'), 'captain@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))
    await waitFor(() => expect(screen.getByLabelText('Code')).toBeVisible())

    await userEvent.type(screen.getByLabelText('Code'), '000000')
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Incorrect code or phone number.'))
  })

  it('never puts the phone number or OTP code in the request URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ success: true, data: { message: 'sent' }, error: null }))
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MemoryRouter>
        <Login />
      </MemoryRouter>,
    )

    await userEvent.type(screen.getByLabelText('Phone number'), '+919900000003')
    await userEvent.type(screen.getByLabelText('Email'), 'captain@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    const url = fetchMock.mock.calls[0][0] as string
    expect(url).not.toContain('+919900000003')
    expect(url).not.toContain('919900000003')
  })

  it('keeps only digits (max 6) in the code field', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ success: true, data: { message: 'sent' }, error: null })))
    render(<MemoryRouter><Login /></MemoryRouter>)
    await userEvent.type(screen.getByLabelText('Phone number'), '+919900000001')
    await userEvent.type(screen.getByLabelText('Email'), 'captain@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))
    await waitFor(() => expect(screen.getByLabelText('Code')).toBeVisible())

    fireEvent.change(screen.getByLabelText('Code'), { target: { value: '12 34-56789' } })

    expect(screen.getByLabelText('Code')).toHaveValue('123456')
  })

  it('lets the captain request a new code only after the 30 second countdown', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ success: true, data: { message: 'sent' }, error: null }))
      vi.stubGlobal('fetch', fetchMock)
      render(<MemoryRouter><Login /></MemoryRouter>)
      await userEvent.type(screen.getByLabelText('Phone number'), '+919900000001')
      await userEvent.type(screen.getByLabelText('Email'), 'captain@example.com')
      await userEvent.click(screen.getByRole('button', { name: 'Send code' }))

      const resend = await screen.findByRole('button', { name: /Send a new code in \d+s/ })
      expect(resend).toBeDisabled()

      // The countdown re-arms a one-second timer on every render, so tick it one second at a time.
      for (let i = 0; i < 31; i++) {
        await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
      }
      const ready = screen.getByRole('button', { name: 'Send a new code' })
      expect(ready).toBeEnabled()

      await userEvent.click(ready)
      await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    } finally {
      vi.useRealTimers()
    }
  })
  const OK = (data: unknown) => jsonResponse({ success: true, data, error: null })
  const tokens = () => OK({ accessToken: 'at-1', refreshToken: 'rt-1' })

  async function reachCodeScreen() {
    await userEvent.type(screen.getByLabelText('Phone number'), '+919900000001')
    await userEvent.type(screen.getByLabelText('Email'), 'captain@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))
    await waitFor(() => expect(screen.getByLabelText('Code')).toBeVisible())
    await userEvent.type(screen.getByLabelText('Code'), '123456')
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))
  }

  it('asks a brand-new captain to create a password after the code, with no way to skip', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(OK({ message: 'sent' }))
      .mockResolvedValueOnce(tokens())
      .mockResolvedValueOnce(OK({ passwordSet: false }))
      .mockResolvedValueOnce(jsonResponse({ success: false, data: null, error: { code: 'RESOURCE_NOT_FOUND', message: 'none' } }, 404))
      .mockResolvedValueOnce(OK(null))
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter><Login /></MemoryRouter>)
    await reachCodeScreen()

    await waitFor(() => expect(screen.getByLabelText('New password')).toBeVisible())
    expect(screen.queryByRole('button', { name: 'Not now' })).toBeNull()

    await userEvent.type(screen.getByLabelText('New password'), 'short')
    await userEvent.type(screen.getByLabelText('Type it again'), 'short')
    await userEvent.click(screen.getByRole('button', { name: 'Save password and continue' }))
    expect(screen.getByRole('alert')).toHaveTextContent('at least 10 characters')

    await userEvent.clear(screen.getByLabelText('New password'))
    await userEvent.clear(screen.getByLabelText('Type it again'))
    await userEvent.type(screen.getByLabelText('New password'), 'my little pink scooter')
    await userEvent.type(screen.getByLabelText('Type it again'), 'my little pink scooter')
    await userEvent.click(screen.getByRole('button', { name: 'Save password and continue' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(5))
    const [url, init] = fetchMock.mock.calls[4]
    expect(url).toContain('/api/v1/auth/driver/password/set')
    expect(init.headers.Authorization).toBe('Bearer at-1')
    expect(JSON.parse(init.body)).toEqual({ newPassword: 'my little pink scooter' })
    expect(url).not.toContain('scooter')
  })

  it('lets an existing captain without a password tap Not now', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(OK({ message: 'sent' }))
      .mockResolvedValueOnce(tokens())
      .mockResolvedValueOnce(OK({ passwordSet: false }))
      .mockResolvedValueOnce(OK({ id: 'driver-1' })))
    render(<MemoryRouter><Login /></MemoryRouter>)
    await reachCodeScreen()
    expect(await screen.findByRole('button', { name: 'Not now' })).toBeEnabled()
  })

  it('shows a server reason (for example a too-common password) and stays on the screen', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(OK({ message: 'sent' }))
      .mockResolvedValueOnce(tokens())
      .mockResolvedValueOnce(OK({ passwordSet: false }))
      .mockResolvedValueOnce(jsonResponse({ success: false, data: null, error: { code: 'RESOURCE_NOT_FOUND', message: 'none' } }, 404))
      .mockResolvedValueOnce(jsonResponse({ success: false, data: null, error: { code: 'VALIDATION_FAILED', message: 'That password is too common. Choose something harder to guess.' } }, 400)))
    render(<MemoryRouter><Login /></MemoryRouter>)
    await reachCodeScreen()
    await userEvent.type(await screen.findByLabelText('New password'), 'password1234')
    await userEvent.type(screen.getByLabelText('Type it again'), 'password1234')
    await userEvent.click(screen.getByRole('button', { name: 'Save password and continue' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('too common'))
    expect(screen.getByLabelText('New password')).toBeVisible()
  })

  it('signs in with a phone number and password', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(tokens())
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter><Login /></MemoryRouter>)
    await userEvent.click(screen.getByRole('button', { name: /Sign in with your password/ }))
    await userEvent.type(screen.getByLabelText('Phone number or email'), '90866 60267')
    await userEvent.type(screen.getByLabelText('Password'), 'my little pink scooter')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toContain('/api/v1/auth/driver/password/login')
    expect(JSON.parse(init.body)).toMatchObject({ identifier: '+919086660267', password: 'my little pink scooter' })
    expect(url).not.toContain('scooter')
  })

  it('shows one plain message for a wrong password and a wait message when rate limited', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(jsonResponse({ success: false, data: null, error: { code: 'UNAUTHORIZED', message: 'Authentication is required.' } }, 401))
      .mockResolvedValueOnce(jsonResponse({ success: false, data: null, error: { code: 'RATE_LIMITED', message: 'Too many requests.' } }, 429)))
    render(<MemoryRouter><Login /></MemoryRouter>)
    await userEvent.click(screen.getByRole('button', { name: /Sign in with your password/ }))
    await userEvent.type(screen.getByLabelText('Phone number or email'), 'captain@example.com')
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password-1')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Phone, email or password is not correct.'))
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Wait a little'))
  })

  it('asks for a reset link and shows the same reply the server gives', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(OK({ message: 'If that account exists, we have emailed a link to choose a new password.' }))
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter><Login /></MemoryRouter>)
    await userEvent.click(screen.getByRole('button', { name: /Sign in with your password/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Forgot password?' }))
    await userEvent.type(screen.getByLabelText('Phone number or email'), 'captain@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Email me a link' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('If that account exists'))
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ identifier: 'captain@example.com' })
  })
})
