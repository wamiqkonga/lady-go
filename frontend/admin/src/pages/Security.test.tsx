import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Security from './Security'

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('Security page', () => {
  it('walks through authenticator setup and confirms with a code', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { secret: 'JBSWY3DPEHPK3PXP', provisioningUri: 'otpauth://x' }, error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { accessToken: 'a.b.c' }, error: null }))
    vi.stubGlobal('fetch', fetchMock)

    render(<Security />)
    await userEvent.click(screen.getByRole('button', { name: 'Set up authenticator' }))
    expect(await screen.findByTestId('mfa-secret')).toHaveTextContent('JBSWY3DPEHPK3PXP')

    const confirm = screen.getByRole('button', { name: 'Confirm and finish' })
    expect(confirm).toBeDisabled()
    await userEvent.type(screen.getByLabelText('Code from the app'), '123456')
    await userEvent.click(confirm)

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Authenticator set up'))
    expect(String(fetchMock.mock.calls[1][0])).toContain('/mfa/self/enroll/confirm')
  })

  it('tells the admin when an authenticator already exists and how to replace it', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({ success: false, data: null, error: { code: 'FORBIDDEN', message: 'An authenticator is already set up for this account.' } }, 403),
      ),
    )

    render(<Security />)
    await userEvent.click(screen.getByRole('button', { name: 'Set up authenticator' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('ask a SUPER_ADMIN to reset it'))
  })

  it('shows a clear message for a wrong confirmation code', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse({ success: true, data: { secret: 'ABC', provisioningUri: 'otpauth://x' }, error: null }))
        .mockResolvedValueOnce(jsonResponse({ success: false, data: null, error: { code: 'UNAUTHORIZED', message: 'Authentication is required.' } }, 401)),
    )

    render(<Security />)
    await userEvent.click(screen.getByRole('button', { name: 'Set up authenticator' }))
    await userEvent.type(await screen.findByLabelText('Code from the app'), '000000')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm and finish' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('was not accepted'))
  })
})
