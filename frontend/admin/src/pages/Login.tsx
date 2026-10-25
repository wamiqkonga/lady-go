import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { AuthError, login } from '../lib/auth'

import ThemeToggle from '../components/ThemeToggle'

// Mirrors the backend LADYGO_ADMIN_MFA_REQUIRED. Off for the pilot: sign-in is email + password only.
const mfaRequired = () => import.meta.env.VITE_ADMIN_MFA_REQUIRED === 'true'

export default function Login() {
  const navigate = useNavigate()
  const location = useLocation()
  const withCode = mfaRequired()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [totpCode, setTotpCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  // RequireAuth remembers the page the user was sent away from; only an in-app path is honoured.
  const from = (location.state as { from?: string } | null)?.from
  const destination = from && from.startsWith('/') && !from.startsWith('//') && from !== '/login' ? from : '/dashboard'

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      const result = await login(email, password, withCode ? totpCode.trim() : undefined)
      if (result.kind === 'authenticated') {
        navigate(destination, { replace: true })
        return
      }
      // MFA enrolment isn't yet reachable from this screen - every pilot admin is bootstrapped
      // with MFA already confirmed, so this is a defensive message, not a built flow.
      setError('This account still needs to finish MFA enrolment. Contact another admin.')
    } catch (err) {
      if (err instanceof AuthError && err.code === 'UNAUTHORIZED') {
        // The backend gives an identical UNAUTHORIZED for a wrong password, a wrong code and a reused
        // code by design, so the message must not guess which one it was.
        setError(
          withCode
            ? 'Sign-in failed. Check your email, password and authenticator code.'
            : 'Sign-in failed. Check your email and password.',
        )
      } else if (err instanceof AuthError) {
        setError(err.message)
      } else {
        setError('Something went wrong. Try again.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="login">
      <h1>Lady Go Admin</h1>
      <ThemeToggle />
      <form onSubmit={onSubmit} className="form">
        <label htmlFor="email">Email</label>
        <input
          id="email"
          type="email"
          autoComplete="username"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
        <label htmlFor="password">Password</label>
        <input
          id="password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
        {withCode && (
          <>
            <label htmlFor="totp">Authenticator code</label>
            <input
              id="totp"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              value={totpCode}
              onChange={(e) => setTotpCode(e.target.value)}
              required
            />
          </>
        )}
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <button type="submit" disabled={submitting}>
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </main>
  )
}
