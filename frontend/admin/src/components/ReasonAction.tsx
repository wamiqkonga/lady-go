import { useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ApiCallError } from '../lib/client'
import { useStepUp } from '../lib/stepUpContext'

type Props = {
  label: string
  /** Shown above the reason box so the person knows exactly what will happen. */
  description?: string
  /** False for an action whose endpoint takes no reason: the person still confirms before the step-up prompt. */
  askReason?: boolean
  /** Extra inputs (for example the new role) rendered inside the confirmation form. */
  children?: ReactNode
  /** The API call. Runs after the step-up check, and once more only if the server asked for a fresh code. */
  perform: (reason: string) => Promise<unknown>
  onDone: () => void
}

/** Every destructive staff action: button, then a required written reason, then (via runWithStepUp) the authenticator code. */
export default function ReasonAction({ label, description, askReason = true, children, perform, onDone }: Props) {
  const { runWithStepUp } = useStepUp()
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [needsSetup, setNeedsSetup] = useState(false)

  function close() {
    setOpen(false)
    setReason('')
    setError(null)
    setNeedsSetup(false)
  }

  async function confirm() {
    setBusy(true)
    setError(null)
    setNeedsSetup(false)
    try {
      await runWithStepUp(() => perform(reason.trim()))
      close()
      onDone()
    } catch (err) {
      if (err instanceof ApiCallError && err.code === 'MFA_ENROLMENT_REQUIRED') setNeedsSetup(true)
      setError(err instanceof ApiCallError ? err.message : 'That did not work. Try again.')
    } finally {
      setBusy(false)
    }
  }

  if (!open) {
    return (
      <button type="button" onClick={() => setOpen(true)}>
        {label}
      </button>
    )
  }

  const id = `reason-${label.replace(/\W+/g, '-').toLowerCase()}`
  return (
    <div className="reason-action panel" role="group" aria-label={label}>
      {description && <p className="muted">{description}</p>}
      {children}
      {askReason && (
        <>
          <label htmlFor={id}>Reason (required)</label>
          <input id={id} type="text" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
        </>
      )}
      <div className="row-actions">
        <button type="button" onClick={confirm} disabled={busy || (askReason && !reason.trim())}>
          Confirm {label.toLowerCase()}
        </button>
        <button type="button" onClick={close} disabled={busy}>
          Cancel
        </button>
      </div>
      {error && <p role="alert">{error}</p>}
      {needsSetup && <Link to="/security">Go to Security to set up an authenticator</Link>}
    </div>
  )
}
