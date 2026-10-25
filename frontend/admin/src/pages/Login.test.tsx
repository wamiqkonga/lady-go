import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthError } from '../lib/auth'
import Login from './Login'

const loginMock = vi.hoisted(() => vi.fn())
vi.mock('../lib/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../lib/auth')>()),
  login: loginMock,
}))

afterEach(() => {
  vi.unstubAllEnvs()
  loginMock.mockReset()
})

function renderLogin(from?: string) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/login', state: from ? { from } : undefined }]}>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/dashboard" element={<p>dashboard page</p>} />
        <Route path="/audit-logs" element={<p>audit page</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

async function fillAndSubmit(code?: string) {
  await userEvent.type(screen.getByLabelText('Email'), 'a@b.co')
  await userEvent.type(screen.getByLabelText('Password'), 'pw-123456789')
  if (code) await userEvent.type(screen.getByLabelText('Authenticator code'), code)
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
}

describe('Login', () => {
  describe('with MFA off', () => {
    beforeEach(() => vi.stubEnv('VITE_ADMIN_MFA_REQUIRED', 'false'))

    it('never asks for an authenticator code, even after a wrong password', async () => {
      loginMock.mockRejectedValue(new AuthError('UNAUTHORIZED', 'Authentication is required.'))
      renderLogin()
      expect(screen.queryByLabelText('Authenticator code')).not.toBeInTheDocument()

      await fillAndSubmit()

      expect(await screen.findByRole('alert')).toHaveTextContent(/email and password/i)
      expect(screen.queryByLabelText('Authenticator code')).not.toBeInTheDocument()
      expect(screen.getByRole('alert')).not.toHaveTextContent(/authenticator/i)
    })
  })

  describe('with MFA on', () => {
    beforeEach(() => vi.stubEnv('VITE_ADMIN_MFA_REQUIRED', 'true'))

    it('shows the code field from the start and sends the code with the first attempt', async () => {
      loginMock.mockResolvedValue({ kind: 'authenticated' })
      renderLogin()
      expect(screen.getByLabelText('Authenticator code')).toBeInTheDocument()

      await fillAndSubmit('123456')

      await waitFor(() => expect(loginMock).toHaveBeenCalledWith('a@b.co', 'pw-123456789', '123456'))
    })

    it('does not blame the authenticator code alone when sign-in fails', async () => {
      loginMock.mockRejectedValue(new AuthError('UNAUTHORIZED', 'Authentication is required.'))
      renderLogin()

      await fillAndSubmit('123456')

      expect(await screen.findByRole('alert')).toHaveTextContent(/email, password/i)
    })
  })

  it('returns to the page the user was trying to open', async () => {
    vi.stubEnv('VITE_ADMIN_MFA_REQUIRED', 'false')
    loginMock.mockResolvedValue({ kind: 'authenticated' })
    renderLogin('/audit-logs')

    await fillAndSubmit()

    expect(await screen.findByText('audit page')).toBeInTheDocument()
  })

  it('lands on the dashboard when there is no remembered page', async () => {
    vi.stubEnv('VITE_ADMIN_MFA_REQUIRED', 'false')
    loginMock.mockResolvedValue({ kind: 'authenticated' })
    renderLogin()

    await fillAndSubmit()

    expect(await screen.findByText('dashboard page')).toBeInTheDocument()
  })
})
