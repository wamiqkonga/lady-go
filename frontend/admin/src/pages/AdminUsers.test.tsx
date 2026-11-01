import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AdminUsers from './AdminUsers'
import Layout from '../components/Layout'
import { SessionContext } from '../lib/sessionContext'
import { StepUpProvider } from '../lib/StepUpProvider'

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const staff = (over: Record<string, unknown> = {}) => ({
  id: 'u-1', name: 'Asha Support', maskedEmail: 'a***@example.com', role: 'SUPPORT', status: 'ACTIVE',
  mfaEnrolled: false, lastLoginAt: null, locked: false, pendingInvite: false, createdAt: '2026-10-01T00:00:00Z', ...over,
})

function renderList(role = 'SUPER_ADMIN') {
  return render(
    <MemoryRouter>
      <SessionContext.Provider value={{ signedIn: true, role, signOut: vi.fn() }}>
        <StepUpProvider>
          <AdminUsers />
        </StepUpProvider>
      </SessionContext.Provider>
    </MemoryRouter>,
  )
}

describe('AdminUsers', () => {
  it('lists staff with a masked email, role, status and MFA state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      success: true,
      data: [staff(), staff({ id: 'u-2', name: 'Ravi', pendingInvite: true, role: 'ADMIN' }), staff({ id: 'u-3', name: 'Locked One', locked: true, mfaEnrolled: true })],
      error: null,
    })))

    renderList()

    await waitFor(() => expect(screen.getByText('Asha Support')).toBeInTheDocument())
    expect(screen.getAllByText('a***@example.com')).toHaveLength(3)
    expect(screen.getByText('Invited')).toBeInTheDocument()
    expect(screen.getByText(/Locked$/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Asha Support' })).toHaveAttribute('href', '/users/u-1')
    // every cell carries its label, which the phone layout shows on each card.
    expect(document.querySelectorAll('td[data-label="Role"]').length).toBe(3)
  })

  it('sends an invitation only after a reason is given, then reloads the list', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [], error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: staff({ id: 'u-9', name: 'New Person' }), error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [staff({ id: 'u-9', name: 'New Person' })], error: null }))
    vi.stubGlobal('fetch', fetchMock)

    renderList()
    await waitFor(() => expect(screen.getByRole('form', { name: 'Invite a staff member' })).toBeInTheDocument())

    await userEvent.type(screen.getByLabelText('Full name'), 'New Person')
    await userEvent.type(screen.getByLabelText('Email'), 'new@example.com')
    await userEvent.selectOptions(screen.getByLabelText('Role'), 'ADMIN')
    const send = screen.getByRole('button', { name: 'Send invitation' })
    expect(send).toBeDisabled() // no reason yet.
    await userEvent.type(screen.getByLabelText('Reason (required)'), 'Joins the safety desk')
    await userEvent.click(send)

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Invitation sent to new@example.com'))
    const post = fetchMock.mock.calls[1][0] as Request
    expect(post.method).toBe('POST')
    expect(post.url).toMatch(/\/api\/v1\/admin\/users$/)
    expect(JSON.parse(await post.clone().text())).toEqual({
      email: 'new@example.com', name: 'New Person', role: 'ADMIN', reason: 'Joins the safety desk',
    })
    await waitFor(() => expect(screen.getByText('New Person')).toBeInTheDocument())
  })

  it('asks for an authenticator code when the server demands a step-up, then retries the invite once', async () => {
    const stepUpRequired = { success: false, data: null, error: { code: 'STEP_UP_REQUIRED', message: 'Confirm your identity' } }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [], error: null }))
      .mockResolvedValueOnce(jsonResponse(stepUpRequired, 403))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { accessToken: 'tok' }, error: null })) // step-up
      .mockResolvedValueOnce(jsonResponse({ success: true, data: staff(), error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [staff()], error: null }))
    vi.stubGlobal('fetch', fetchMock)

    renderList()
    await waitFor(() => expect(screen.getByLabelText('Full name')).toBeInTheDocument())
    await userEvent.type(screen.getByLabelText('Full name'), 'Asha')
    await userEvent.type(screen.getByLabelText('Email'), 'asha@example.com')
    await userEvent.type(screen.getByLabelText('Reason (required)'), 'Hire')
    await userEvent.click(screen.getByRole('button', { name: 'Send invitation' }))

    const dialog = await screen.findByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Authenticator code'), '123456')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(screen.getByRole('status')).toBeInTheDocument())
    expect(fetchMock).toHaveBeenCalledTimes(5)
  })

  it('shows the server message when the invite is refused', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [], error: null }))
      .mockResolvedValueOnce(jsonResponse({ success: false, data: null, error: { code: 'VALIDATION_FAILED', message: 'An account with this email already exists.' } }, 400))
    vi.stubGlobal('fetch', fetchMock)

    renderList()
    await waitFor(() => expect(screen.getByLabelText('Full name')).toBeInTheDocument())
    await userEvent.type(screen.getByLabelText('Full name'), 'Dup')
    await userEvent.type(screen.getByLabelText('Email'), 'dup@example.com')
    await userEvent.type(screen.getByLabelText('Reason (required)'), 'Hire')
    await userEvent.click(screen.getByRole('button', { name: 'Send invitation' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('already exists'))
  })
})

describe('Layout navigation', () => {
  function renderNav(role: string) {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({})))
    return render(
      <MemoryRouter>
        <SessionContext.Provider value={{ signedIn: true, role, signOut: vi.fn() }}>
          <Layout />
        </SessionContext.Provider>
      </MemoryRouter>,
    )
  }

  it('shows the Staff entry to a SUPER_ADMIN only', () => {
    renderNav('SUPER_ADMIN')
    expect(screen.getByRole('link', { name: 'Staff' })).toBeInTheDocument()
  })

  it.each(['ADMIN', 'SUPPORT'])('hides the Staff entry from %s', (role) => {
    renderNav(role)
    expect(screen.queryByRole('link', { name: 'Staff' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Audit log' })).toBeInTheDocument()
  })
})
