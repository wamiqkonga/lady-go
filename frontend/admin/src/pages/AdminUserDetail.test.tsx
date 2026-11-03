import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AdminUserDetail from './AdminUserDetail'
import { SessionContext } from '../lib/sessionContext'
import { StepUpProvider } from '../lib/StepUpProvider'

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const staff = (over: Record<string, unknown> = {}) => ({
  id: 'u-1', name: 'Asha Support', maskedEmail: 'a***@example.com', role: 'SUPPORT', status: 'ACTIVE',
  mfaEnrolled: true, lastLoginAt: null, locked: false, pendingInvite: false, createdAt: '2026-10-01T00:00:00Z', ...over,
})
const ok = (data: unknown) => jsonResponse({ success: true, data, error: null })

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/users/u-1']}>
      <SessionContext.Provider value={{ signedIn: true, role: 'SUPER_ADMIN', signOut: vi.fn() }}>
        <StepUpProvider>
          <Routes>
            <Route path="users/:id" element={<AdminUserDetail />} />
          </Routes>
        </StepUpProvider>
      </SessionContext.Provider>
    </MemoryRouter>,
  )
}

async function bodyOf(fetchMock: ReturnType<typeof vi.fn>, call: number) {
  const request = fetchMock.mock.calls[call][0] as Request
  return { url: request.url, method: request.method, body: request.method === 'POST' ? JSON.parse((await request.clone().text()) || 'null') : null }
}

describe('AdminUserDetail', () => {
  it('changes the role only with a reason, sending the chosen role', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(ok(staff()))
      .mockResolvedValueOnce(ok(staff({ role: 'ADMIN' })))
      .mockResolvedValueOnce(ok(staff({ role: 'ADMIN' })))
    vi.stubGlobal('fetch', fetchMock)

    renderDetail()
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Asha Support' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: 'Change role' }))
    await userEvent.selectOptions(screen.getByLabelText('New role'), 'ADMIN')
    const confirm = screen.getByRole('button', { name: 'Confirm change role' })
    expect(confirm).toBeDisabled()
    await userEvent.type(screen.getByLabelText('Reason (required)'), 'Promoted to shift lead')
    await userEvent.click(confirm)

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    const call = await bodyOf(fetchMock, 1)
    expect(call.url).toContain('/api/v1/admin/users/u-1/role')
    expect(call.body).toEqual({ role: 'ADMIN', reason: 'Promoted to shift lead' })
  })

  it('does not offer the current role as a target', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(ok(staff())))
    renderDetail()
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Asha Support' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: 'Change role' }))
    const options = Array.from(screen.getByLabelText('New role').querySelectorAll('option')).map((o) => o.value)
    expect(options).toEqual(['ADMIN', 'SUPER_ADMIN'])
  })

  it('deactivates with a reason; a deactivated account offers Reactivate instead', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(ok(staff()))
      .mockResolvedValueOnce(ok(staff({ status: 'SUSPENDED' })))
      .mockResolvedValueOnce(ok(staff({ status: 'SUSPENDED' })))
    vi.stubGlobal('fetch', fetchMock)

    renderDetail()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Deactivate' })).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Reactivate' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    await userEvent.type(screen.getByLabelText('Reason (required)'), 'Left the company')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm deactivate' }))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Reactivate' })).toBeInTheDocument())
    expect((await bodyOf(fetchMock, 1)).body).toEqual({ reason: 'Left the company' })
  })

  it('shows Unlock only for a locked account and sends its reason', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(ok(staff({ locked: true })))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: null, error: null }))
      .mockResolvedValueOnce(ok(staff()))
    vi.stubGlobal('fetch', fetchMock)

    renderDetail()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Unlock sign-in' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: 'Unlock sign-in' }))
    await userEvent.type(screen.getByLabelText('Reason (required)'), 'Verified by phone')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm unlock sign-in' }))

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Unlock sign-in' })).not.toBeInTheDocument())
    const call = await bodyOf(fetchMock, 1)
    expect(call.url).toContain('/api/v1/admin/users/u-1/unlock')
    expect(call.body).toEqual({ reason: 'Verified by phone' })
  })

  it('hides Unlock for an unlocked account', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(ok(staff())))
    renderDetail()
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Asha Support' })).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Unlock sign-in' })).not.toBeInTheDocument()
  })

  it('resets the authenticator after an explicit confirmation, then asks for the step-up code first if required', async () => {
    const stepUpRequired = { success: false, data: null, error: { code: 'STEP_UP_REQUIRED', message: 'Confirm' } }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(ok(staff()))
      .mockResolvedValueOnce(jsonResponse(stepUpRequired, 403))
      .mockResolvedValueOnce(ok({ accessToken: 'tok' }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: null, error: null }))
      .mockResolvedValueOnce(ok(staff({ mfaEnrolled: false })))
    vi.stubGlobal('fetch', fetchMock)

    renderDetail()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Reset authenticator' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: 'Reset authenticator' }))
    expect(screen.queryByLabelText('Reason (required)')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Confirm reset authenticator' }))

    const dialog = await screen.findByRole('dialog')
    await userEvent.type(dialog.querySelector('input')!, '123456')
    await userEvent.click(dialog.querySelector('button[type="submit"]') ?? screen.getByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Reset authenticator' })).not.toBeInTheDocument())
    expect((await bodyOf(fetchMock, 3)).url).toContain('/users/u-1/mfa/reset')
  })

  it('shows the refusal when the server blocks the action (e.g. last SUPER_ADMIN) and changes nothing', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(ok(staff({ role: 'SUPER_ADMIN' })))
      .mockResolvedValueOnce(jsonResponse({ success: false, data: null, error: { code: 'FORBIDDEN', message: 'This is the last active SUPER_ADMIN, so it cannot be demoted or deactivated.' } }, 403))
    vi.stubGlobal('fetch', fetchMock)

    renderDetail()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Deactivate' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    await userEvent.type(screen.getByLabelText('Reason (required)'), 'test')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm deactivate' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('last active SUPER_ADMIN'))
    expect(fetchMock).toHaveBeenCalledTimes(2) // no reload after a refusal.
  })
})
