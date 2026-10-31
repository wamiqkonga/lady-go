import { useCallback, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { AuthError, stepUp } from './auth'
import { ApiCallError } from './client'
import { StepUpContext } from './stepUpContext'

type Waiting = { resolve: () => void; reject: (reason: Error) => void }

/**
 * Wraps a destructive admin action so a missing/expired step-up is handled in one place: the backend answers
 * 403 STEP_UP_REQUIRED, this asks for a fresh authenticator code, and the action is retried once. The backend
 * remains the only control - nothing here decides whether an action is allowed.
 */
export function StepUpProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false)
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const waiting = useRef<Waiting | null>(null)

  const askForCode = useCallback(
    () =>
      new Promise<void>((resolve, reject) => {
        waiting.current = { resolve, reject }
        setCode('')
        setError(null)
        setOpen(true)
      }),
    [],
  )

  const runWithStepUp = useCallback(
    async <T,>(action: () => Promise<T>): Promise<T> => {
      try {
        return await action()
      } catch (err) {
        if (!(err instanceof ApiCallError) || err.code !== 'STEP_UP_REQUIRED') throw err
      }
      await askForCode()
      return action()
    },
    [askForCode],
  )

  function close() {
    setOpen(false)
    waiting.current = null
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await stepUp(code.trim())
      waiting.current?.resolve()
      close()
    } catch (err) {
      if (err instanceof AuthError && err.code === 'UNAUTHORIZED') {
        // Identical for a wrong code and a code already used (replay guard) - do not guess which.
        setError('That code was not accepted. Wait for the next code from your authenticator app and try again.')
      } else if (err instanceof AuthError && err.code === 'RATE_LIMITED') {
        setError('Too many attempts. Wait a few minutes and try again.')
      } else {
        setError('Could not confirm the code. Try again.')
      }
    } finally {
      setBusy(false)
    }
  }

  function onCancel() {
    waiting.current?.reject(new ApiCallError('STEP_UP_CANCELLED', 'Confirmation cancelled. Nothing was changed.'))
    close()
  }

  return (
    <StepUpContext.Provider value={{ runWithStepUp }}>
      {children}
      {open && (
        <div className="dialog-backdrop">
          <div role="dialog" aria-modal="true" aria-labelledby="stepup-title" className="dialog">
            <h2 id="stepup-title">Confirm it is you</h2>
            <p className="muted">Enter the 6-digit code from your authenticator app to continue.</p>
            <form onSubmit={onSubmit} className="form">
              <label htmlFor="stepup-code">Authenticator code</label>
              <input
                id="stepup-code"
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="[0-9]{6}"
                maxLength={6}
                value={code}
                onChange={(e) => setCode(e.target.value)}
                autoFocus
              />
              {error && <p role="alert">{error}</p>}
              <div className="row-actions">
                <button type="submit" disabled={busy || !/^[0-9]{6}$/.test(code.trim())}>
                  Confirm
                </button>
                <button type="button" onClick={onCancel} disabled={busy}>
                  Cancel
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </StepUpContext.Provider>
  )
}
