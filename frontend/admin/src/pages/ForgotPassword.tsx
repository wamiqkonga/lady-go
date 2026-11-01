import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { AuthError, requestPasswordReset } from '../lib/auth'

/** The confirmation is identical for every address on purpose: it must not reveal which emails are admins. */
export default function ForgotPassword() {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await requestPasswordReset(email.trim())
      setSent(true)
    } catch (err) {
      setError(
        err instanceof AuthError && err.code === 'VALIDATION_FAILED'
          ? 'Enter a valid email address.'
          : 'Something went wrong. Try again in a moment.',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="login">
      <h1>Reset your password</h1>
      {sent ? (
        <p role="status">
          If that email belongs to an admin account, a reset link is on its way. It expires soon and works only once.
          Check your spam folder if it does not arrive.
        </p>
      ) : (
        <form onSubmit={onSubmit} className="form">
          <label htmlFor="forgot-email">Admin email</label>
          <input
            id="forgot-email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
          <button type="submit" disabled={busy || !email.trim()}>
            {busy ? 'Sending…' : 'Send reset link'}
          </button>
        </form>
      )}
      <p>
        <Link to="/login">Back to sign in</Link>
      </p>
    </main>
  )
}
