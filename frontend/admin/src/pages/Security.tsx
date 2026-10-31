import { useState, type FormEvent } from 'react'
import { AuthError, confirmMfaSetup, startMfaSetup, type MfaEnrolStart } from '../lib/auth'

/**
 * Self-service authenticator setup. Destructive admin actions (pricing changes, MFA reset) require a fresh
 * authenticator code, so an account without one cannot perform them. Once set up it cannot be replaced from
 * here - a SUPER_ADMIN resets it - so a stolen session cannot swap in an attacker's authenticator.
 */
export default function Security() {
  const [setup, setSetup] = useState<MfaEnrolStart | null>(null)
  const [code, setCode] = useState('')
  const [done, setDone] = useState(false)
  const [alreadySet, setAlreadySet] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function begin() {
    setBusy(true)
    setError(null)
    try {
      setSetup(await startMfaSetup())
    } catch (err) {
      if (err instanceof AuthError && err.code === 'FORBIDDEN') setAlreadySet(true)
      else setError('Could not start setup. Try again.')
    } finally {
      setBusy(false)
    }
  }

  async function confirm(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await confirmMfaSetup(code.trim())
      setDone(true)
      setSetup(null)
    } catch {
      setError('That code was not accepted. Check the code in your authenticator app and try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <h1>Security</h1>
      {done && (
        <p role="status">
          Authenticator set up. You can now make pricing changes. You may be asked for a fresh code before sensitive actions.
        </p>
      )}
      {alreadySet && (
        <p role="status">
          An authenticator is already set up for this account. To replace it, ask a SUPER_ADMIN to reset it.
        </p>
      )}
      {!setup && !done && !alreadySet && (
        <>
          <p className="muted">
            Pricing changes and other sensitive actions need a 6-digit code from an authenticator app (Google
            Authenticator, Microsoft Authenticator, Authy).
          </p>
          <div className="row-actions">
            <button type="button" onClick={begin} disabled={busy}>
              Set up authenticator
            </button>
          </div>
        </>
      )}
      {setup && (
        <form onSubmit={confirm} className="form">
          <p>
            In your authenticator app choose "Enter a setup key" and type this key (spaces are fine). Keep it
            private; anyone with it can generate your codes.
          </p>
          <p>
            <code data-testid="mfa-secret">{setup.secret}</code>
          </p>
          <label htmlFor="mfa-code">Code from the app</label>
          <input
            id="mfa-code"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            value={code}
            onChange={(e) => setCode(e.target.value)}
          />
          <div className="row-actions">
            <button type="submit" disabled={busy || !/^[0-9]{6}$/.test(code.trim())}>
              Confirm and finish
            </button>
          </div>
        </form>
      )}
      {error && <p role="alert">{error}</p>}
    </section>
  )
}
