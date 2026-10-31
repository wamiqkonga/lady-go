import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ConfigScreen from './ConfigScreen'
import { SessionContext } from '../lib/sessionContext'
import { StepUpProvider } from '../lib/StepUpProvider'

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const entries = {
  success: true,
  data: [
    {
      id: 'cfg-1', configKey: 'driver.search.radius.m', configValue: '5000', valueType: 'INTEGER',
      scope: 'GLOBAL', minValue: '100', maxValue: '50000', updatedAt: '2026-01-01T00:00:00Z',
    },
  ],
  error: null,
}

function renderAs(role: 'ADMIN' | 'SUPER_ADMIN') {
  return render(
    <MemoryRouter>
      <SessionContext.Provider value={{ signedIn: true, role, signOut: vi.fn() }}>
        <StepUpProvider>
          <ConfigScreen />
        </StepUpProvider>
      </SessionContext.Provider>
    </MemoryRouter>,
  )
}

describe('ConfigScreen', () => {
  it('shows the server-enforced bounds for every entry', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(entries)))

    renderAs('SUPER_ADMIN')

    await waitFor(() => expect(screen.getByText('driver.search.radius.m')).toBeInTheDocument())
    expect(screen.getByText('100–50000')).toBeInTheDocument()
  })

  it('hides the edit control for a plain ADMIN (cosmetic - the backend still enforces 403)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(entries)))

    renderAs('ADMIN')

    await waitFor(() => expect(screen.getByText('driver.search.radius.m')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.getByText(/Read-only/)).toBeInTheDocument()
  })

  it('a SUPER_ADMIN must give a reason, sees old→new on confirm, and the value is sent with it', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(entries))
      .mockResolvedValueOnce(
        jsonResponse({
          success: true,
          data: { id: 'cfg-1', configKey: 'driver.search.radius.m', configValue: '6000', valueType: 'INTEGER', scope: 'GLOBAL', minValue: '100', maxValue: '50000' },
          error: null,
        }),
      )
      .mockResolvedValueOnce(jsonResponse({ ...entries, data: [{ ...entries.data[0], configValue: '6000' }] }))
    vi.stubGlobal('fetch', fetchMock)

    renderAs('SUPER_ADMIN')
    await waitFor(() => expect(screen.getByText('driver.search.radius.m')).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const input = screen.getByDisplayValue('5000')
    await userEvent.clear(input)
    await userEvent.type(input, '6000')

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(screen.getByText('5000', { selector: 'strong' })).toBeInTheDocument()
    expect(screen.getByText('6000', { selector: 'strong' })).toBeInTheDocument()

    const confirmButton = screen.getByRole('button', { name: 'Confirm' })
    expect(confirmButton).toBeDisabled() // no reason yet.

    await userEvent.type(screen.getByPlaceholderText(/Reason for this change/), 'seasonal demand adjustment')
    await userEvent.click(confirmButton)

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    const putRequest = fetchMock.mock.calls[1][0] as Request
    expect(putRequest.url).toContain('/admin/config/cfg-1')
    expect(JSON.parse(await putRequest.clone().text())).toEqual({ value: '6000', reason: 'seasonal demand adjustment' })
  })

  it('surfaces a server validation error (e.g. an out-of-bounds value) instead of silently failing', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(entries))
      .mockResolvedValueOnce(
        jsonResponse({ success: false, data: null, error: { code: 'VALIDATION_FAILED', message: 'Value for driver.search.radius.m must be between 100 and 50000.' } }, 400),
      )
    vi.stubGlobal('fetch', fetchMock)

    renderAs('SUPER_ADMIN')
    await waitFor(() => expect(screen.getByText('driver.search.radius.m')).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const input = screen.getByDisplayValue('5000')
    await userEvent.clear(input)
    await userEvent.type(input, '999999')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await userEvent.type(screen.getByPlaceholderText(/Reason for this change/), 'testing bounds')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('must be between 100 and 50000'))
  })

  async function editAndConfirm() {
    await waitFor(() => expect(screen.getByText('driver.search.radius.m')).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const input = screen.getByDisplayValue('5000')
    await userEvent.clear(input)
    await userEvent.type(input, '6000')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await userEvent.type(screen.getByPlaceholderText(/Reason for this change/), 'seasonal demand')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }))
  }

  const stepUpRequired = {
    success: false, data: null, error: { code: 'STEP_UP_REQUIRED', message: 'Confirm your identity with a fresh authenticator code to continue.' },
  }
  const saved = {
    success: true,
    data: { id: 'cfg-1', configKey: 'driver.search.radius.m', configValue: '6000', valueType: 'INTEGER', scope: 'GLOBAL' },
    error: null,
  }

  it('asks for an authenticator code when the server demands a step-up, then retries the save once', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(entries)) // initial load
      .mockResolvedValueOnce(jsonResponse(stepUpRequired, 403)) // first PUT
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { accessToken: 'new.token.value' }, error: null })) // step-up
      .mockResolvedValueOnce(jsonResponse(saved)) // retried PUT
      .mockResolvedValueOnce(jsonResponse(entries)) // reload
    vi.stubGlobal('fetch', fetchMock)

    renderAs('SUPER_ADMIN')
    await editAndConfirm()

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('Enter the 6-digit code')
    await userEvent.type(screen.getByLabelText('Authenticator code'), '123456')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(5))
    const stepUpCall = fetchMock.mock.calls[2]
    expect(String(stepUpCall[0])).toContain('/api/v1/auth/admin/step-up')
    expect(JSON.parse(stepUpCall[1].body as string)).toEqual({ code: '123456' })
    const retried = fetchMock.mock.calls[3][0] as Request
    expect(retried.method).toBe('PUT')
  })

  it('cancelling the step-up prompt changes nothing and shows that', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(entries))
      .mockResolvedValueOnce(jsonResponse(stepUpRequired, 403))
    vi.stubGlobal('fetch', fetchMock)

    renderAs('SUPER_ADMIN')
    await editAndConfirm()
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Nothing was changed'))
    expect(fetchMock).toHaveBeenCalledTimes(2) // no step-up call, no retry.
  })

  it('an admin without an authenticator is pointed to the Security page', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(entries))
      .mockResolvedValueOnce(
        jsonResponse({ success: false, data: null, error: { code: 'MFA_ENROLMENT_REQUIRED', message: 'Set up an authenticator app on the Security page before doing this.' } }, 403),
      )
    vi.stubGlobal('fetch', fetchMock)

    renderAs('SUPER_ADMIN')
    await editAndConfirm()

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Security page'))
    expect(screen.getByRole('link', { name: /set up an authenticator/i })).toHaveAttribute('href', '/security')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
