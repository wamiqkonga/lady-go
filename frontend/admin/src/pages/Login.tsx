import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AuthError, login } from '../lib/auth'

export default function Login() {
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [totpCode, setTotpCode] = useState('')
  const [needsTotp, setNeedsTotp] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      const result = await login(email, password, needsTotp ? totpCode : undefined)
      if (result.kind === 'authenticated') {
        navigate('/dashboard', { replace: true })
        return
      }
      // MFA enrolment isn't yet reachable from this screen - every pilot admin is bootstrapped
      // with MFA already confirmed, so this is a defensive message, not a built flow.
      setError('This account still needs to finish MFA enrolment. Contact another admin.')
    } catch (err) {
      if (err instanceof AuthError && err.code === 'UNAUTHORIZED' && !needsTotp && password) {
        // Password was accepted-shaped but the account has MFA enabled - ask for the code.
        // (The backend gives an identical UNAUTHORIZED for bad password vs missing TOTP by
        // design, so this is a UX nicety, not a security signal: wrong TOTP also lands here.)
        setNeedsTotp(true)
        setError('Enter your authenticator code.')
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
        {needsTotp && (
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
