import { render, screen, waitFor } from '@testing-library/react'
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
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))

    await waitFor(() => expect(screen.getByLabelText('Code')).toBeVisible())
    const requestCall = JSON.parse(fetchMock.mock.calls[0][1].body)
    expect(requestCall).toMatchObject({ phone: '+919900000001', purpose: 'LOGIN', intendedRole: 'DRIVER' })

    await userEvent.type(screen.getByLabelText('Code'), '123456')
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    const verifyCall = JSON.parse(fetchMock.mock.calls[1][1].body)
    expect(verifyCall).toMatchObject({ phone: '+919900000001', code: '123456', purpose: 'LOGIN', intendedRole: 'DRIVER' })
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
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    const url = fetchMock.mock.calls[0][0] as string
    expect(url).not.toContain('+919900000003')
    expect(url).not.toContain('919900000003')
  })
})
