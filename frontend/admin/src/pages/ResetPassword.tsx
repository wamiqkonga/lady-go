import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { AuthError, resetPassword } from '../lib/auth'

const MIN_LENGTH = 12

/** Reads the one-time token from the link's #fragment (never sent to a server, logs or Referer headers) and removes it
 *  from the address bar straight away. */
function takeTokenFromUrl(): string {
  const match = window.location.hash.match(/token=([A-Za-z0-9_-]+)/)
  if (match) {
    window.history.replaceState(null, '', window.location.pathname)
  }
  return match ? match[1] : ''
}

export default function ResetPassword() {
  const [token] = useState(takeTokenFromUrl)
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [code, setCode] = useState('')
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    document.title = 'Choose a new password - Lady Go Admin'
  }, [])

  const mismatch = confirm.length > 0 && password !== confirm
  const tooShort = password.length > 0 && password.length < MIN_LENGTH
  const canSubmit = !busy && password.length >= MIN_LENGTH && password === confirm && (code === '' || /^[0-9]{6}$/.test(code))

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await resetPassword(token, password, code.trim() || undefined)
      setDone(true)
    } catch (err) {
      if (err instanceof AuthError && err.code === 'RATE_LIMITED') {
        setError('Too many attempts. Wait a few minutes, then try again or request a new link.')
      } else if (err instanceof AuthError && err.code === 'VALIDATION_FAILED') {
        setError('That password is not accepted. Use at least 12 characters.')
      } else if (err instanceof AuthError && err.code === 'UNAUTHORIZED') {
        // A bad/expired/used link and a wrong authenticator code look the same by design.
        setError(
          'This link is invalid, expired or already used, or the authenticator code is wrong. ' +
            'Check the code, or request a new link.',
        )
      } else {
        setError('Something went wrong. Try again.')
      }
    } finally {
      setBusy(false)
    }
  }

  if (done) {
    return (
      <main className="login">
        <h1>Password changed</h1>
        <p role="status">Your password was changed and every signed-in session was ended. Sign in with the new password.</p>
        <p>
          <Link to="/login">Go to sign in</Link>
        </p>
      </main>
    )
  }

  if (!token) {
    return (
      <main className="login">
        <h1>Choose a new password</h1>
        <p role="alert" className="error">
          This page needs the link from your reset email. Open the link again, or request a new one.
        </p>
        <p>
          <Link to="/forgot-password">Request a new link</Link>
        </p>
      </main>
    )
  }

  return (
    <main className="login">
      <h1>Choose a new password</h1>
      <form onSubmit={onSubmit} className="form">
        <label htmlFor="new-password">New password (at least 12 characters)</label>
        <input
          id="new-password"
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
        {tooShort && <p className="muted">{MIN_LENGTH - password.length} more characters needed.</p>}
        <label htmlFor="confirm-password">Repeat the new password</label>
        <input
          id="confirm-password"
          type="password"
          autoComplete="new-password"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
          required
        />
        {mismatch && <p role="alert" className="error">The two passwords do not match.</p>}
        <label htmlFor="reset-code">Authenticator code (if you have set up an authenticator app)</label>
        <input
          id="reset-code"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          value={code}
          onChange={(e) => setCode(e.target.value)}
        />
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <button type="submit" disabled={!canSubmit}>
          {busy ? 'Saving…' : 'Change password'}
        </button>
      </form>
    </main>
  )
}
