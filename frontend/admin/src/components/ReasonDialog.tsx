import { useId, useRef, useState, type FormEvent } from 'react'
import Dialog from './Dialog'

type ReasonProps = {
  title: string
  /** What will happen, in plain words, above the box. */
  description?: string
  label?: string
  confirmLabel: string
  /** Shown under the box when the person submits it empty. */
  requiredMessage?: string
  onConfirm: (reason: string) => void | Promise<void>
  onCancel: () => void
}

/** A required written reason (a rejection note, a suspension reason): replaces window.prompt. */
export function ReasonDialog({ title, description, label = 'Reason (required)', confirmLabel, requiredMessage = 'Write a reason first.', onConfirm, onCancel }: ReasonProps) {
  const fieldId = useId()
  const errorId = useId()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const input = useRef<HTMLTextAreaElement>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!reason.trim()) {
      setError(requiredMessage)
      input.current?.focus()
      return
    }
    setBusy(true)
    try {
      await onConfirm(reason.trim())
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title={title} onClose={onCancel} initialFocusRef={input}>
      <form className="form" onSubmit={submit} noValidate>
        {description && <p className="muted">{description}</p>}
        <label htmlFor={fieldId}>{label}</label>
        <textarea
          id={fieldId}
          ref={input}
          rows={3}
          maxLength={500}
          value={reason}
          onChange={(e) => { setReason(e.target.value); if (error) setError(null) }}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
        />
        {error && <p id={errorId} role="alert" className="error">{error}</p>}
        <div className="row-actions">
          <button type="submit" className="btn-primary" disabled={busy}>{confirmLabel}</button>
          <button type="button" onClick={onCancel} disabled={busy}>Cancel</button>
        </div>
      </form>
    </Dialog>
  )
}

type ConfirmProps = {
  title: string
  description: ReactNodeLike
  confirmLabel: string
  onConfirm: () => void | Promise<void>
  onCancel: () => void
}
type ReactNodeLike = string | React.ReactNode

/** "Are you sure?" for an effectively irreversible action: replaces window.confirm. */
export function ConfirmDialog({ title, description, confirmLabel, onConfirm, onCancel }: ConfirmProps) {
  const [busy, setBusy] = useState(false)
  return (
    <Dialog title={title} onClose={onCancel}>
      <div className="form">
        <p>{description}</p>
        <div className="row-actions">
          <button
            type="button"
            className="btn-primary"
            disabled={busy}
            onClick={async () => { setBusy(true); try { await onConfirm() } finally { setBusy(false) } }}
          >
            {confirmLabel}
          </button>
          <button type="button" onClick={onCancel} disabled={busy}>Cancel</button>
        </div>
      </div>
    </Dialog>
  )
}
