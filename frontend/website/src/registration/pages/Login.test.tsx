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
  it('sends a code then verifies it, requesting the DRIVER role', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { message: 'If this number is valid, a code was sent.' }, error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { accessToken: 'at-1', refreshToken: 'rt-1' }, error: null }))
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MemoryRouter>
        <Login />
      </MemoryRouter>,
    )

    await userEvent.type(screen.getByLabelText('Phone number'), '+919900000001')
    await userEvent.type(screen.getByLabelText('Email'), 'driver@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))

    await waitFor(() => expect(screen.getByLabelText('Code')).toBeVisible())
    const requestCall = JSON.parse(fetchMock.mock.calls[0][1].body)
    expect(requestCall).toMatchObject({ phone: '+919900000001', email: 'driver@example.com', purpose: 'LOGIN', intendedRole: 'DRIVER' })

    await userEvent.type(screen.getByLabelText('Code'), '123456')
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    const verifyCall = JSON.parse(fetchMock.mock.calls[1][1].body)
    expect(verifyCall).toMatchObject({ phone: '+919900000001', code: '123456', purpose: 'LOGIN', intendedRole: 'DRIVER' })
  })

  it('adds +91 to a plain 10-digit number before asking for the code, and uses it for verifying too', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { message: 'sent' }, error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { accessToken: 'at-1', refreshToken: 'rt-1' }, error: null }))
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MemoryRouter>
        <Login />
      </MemoryRouter>,
    )

    await userEvent.type(screen.getByLabelText('Phone number'), '90866 60267')
    await userEvent.type(screen.getByLabelText('Email'), 'driver@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))

    await waitFor(() => expect(screen.getByLabelText('Code')).toBeVisible())
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toMatchObject({ phone: '+919086660267' })

    await userEvent.type(screen.getByLabelText('Code'), '123456')
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
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
    await userEvent.type(screen.getByLabelText('Email'), 'driver@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))

    expect(screen.getByRole('alert')).toHaveTextContent('10-digit mobile number')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('shows a clear error for a wrong code and lets the driver retry', async () => {
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
    await userEvent.type(screen.getByLabelText('Email'), 'driver@example.com')
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
    await userEvent.type(screen.getByLabelText('Email'), 'driver@example.com')
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
    await userEvent.type(screen.getByLabelText('Email'), 'driver@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))
    await waitFor(() => expect(screen.getByLabelText('Code')).toBeVisible())

    fireEvent.change(screen.getByLabelText('Code'), { target: { value: '12 34-56789' } })

    expect(screen.getByLabelText('Code')).toHaveValue('123456')
  })

  it('lets the driver request a new code only after the 30 second countdown', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ success: true, data: { message: 'sent' }, error: null }))
      vi.stubGlobal('fetch', fetchMock)
      render(<MemoryRouter><Login /></MemoryRouter>)
      await userEvent.type(screen.getByLabelText('Phone number'), '+919900000001')
      await userEvent.type(screen.getByLabelText('Email'), 'driver@example.com')
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
})
