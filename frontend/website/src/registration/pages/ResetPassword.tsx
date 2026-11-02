import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import NewPasswordFields from '../components/NewPasswordFields'
import { AuthError, resetPassword } from '../lib/auth'
import { passwordProblem } from '../lib/passwordRules'

/** Opened from the emailed link (/captain/reset-password#token=...). The token is in the URL fragment so it never
 *  reaches a server log or a Referer header; it is read once and only ever sent in the request body. */
export default function ResetPassword() {
  const { hash } = useLocation()
  const token = new URLSearchParams(hash.replace(/^#/, '')).get('token') ?? ''
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  async function submit() {
    const problem = passwordProblem(password, confirm)
    if (problem) {
      setError(problem)
      return
    }
    setBusy(true)
    setError(null)
    try {
      await resetPassword(token, password)
      setDone(true)
    } catch (err) {
      setError(
        err instanceof AuthError && err.code === 'UNAUTHORIZED'
          ? 'This link has expired or was already used. Ask for a new one.'
          : err instanceof AuthError ? err.message : 'Could not change the password.',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="app-shell">
      <div className="topbar">
        <Link to="/captain" className="brand">Lady Go — Drive with us</Link>
      </div>
      <div className="content login">
        <div className="auth-card">
          <h1>Choose a new password</h1>
          {done ? (
            <>
              <p role="status">Your password was changed. You were signed out everywhere, so sign in with the new one.</p>
              <Link className="cta" to="/captain/registration">Sign in</Link>
            </>
          ) : !token ? (
            <>
              <p role="alert" className="error">This link is not complete. Open the link from your email again, or ask for a new one.</p>
              <Link to="/captain/registration">Back to sign in</Link>
            </>
          ) : (
            <form className="form" aria-busy={busy} onSubmit={(e) => { e.preventDefault(); submit() }}>
              <NewPasswordFields password={password} confirm={confirm} onPassword={setPassword} onConfirm={setConfirm} />
              {error && <p role="alert" className="error">{error}</p>}
              <button type="submit" disabled={busy || !password || !confirm}>
                {busy ? (<><span className="spinner" aria-hidden="true" /> Saving…</>) : 'Save new password'}
              </button>
            </form>
          )}
        </div>
      </div>
    </div>
  )
}
