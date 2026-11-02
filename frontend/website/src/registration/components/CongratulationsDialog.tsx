import { useEffect, useRef } from 'react'
import { SUPPORT_EMAIL } from '../lib/support'

const FOCUSABLE = 'a[href], button:not([disabled]), input, select, textarea, [tabindex]:not([tabindex="-1"])'

/**
 * Modal shown once when a captain finishes every required registration step. Accessible: role="dialog" with a
 * label, focus moves in and is trapped, Escape and the Done button close it, focus returns to where it came from.
 * It promises no review time: none has been decided.
 */
export default function CongratulationsDialog({ onClose }: { onClose: () => void }) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const doneRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null
    doneRef.current?.focus()
    return () => previouslyFocused?.focus?.()
  }, [])

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'Escape') {
      e.stopPropagation()
      onClose()
      return
    }
    if (e.key !== 'Tab') return
    const items = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? [])
    if (items.length === 0) return
    const first = items[0]
    const last = items[items.length - 1]
    const active = document.activeElement
    if (e.shiftKey && (active === first || !dialogRef.current?.contains(active))) {
      e.preventDefault()
      last.focus()
    } else if (!e.shiftKey && (active === last || !dialogRef.current?.contains(active))) {
      e.preventDefault()
      first.focus()
    }
  }

  return (
    <div className="dialog-backdrop">
      <div
        ref={dialogRef}
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="congrats-title"
        aria-describedby="congrats-body"
        onKeyDown={onKeyDown}
      >
        <h2 id="congrats-title">Congratulations, captain!</h2>
        <div id="congrats-body">
          <p>
            You finished every step of your registration. That takes real effort, and we are glad you want to drive
            with Lady Go. Women riders in Kashmir are waiting for captains like you.
          </p>
          <p>
            <strong>What happens next:</strong> our team will now review your application and documents. You do not
            need to do anything else. Come back to this page any time to see where things stand.
          </p>
          <p>
            Want to know more? Contact support at{' '}
            <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a>.
          </p>
        </div>
        <button ref={doneRef} type="button" onClick={onClose}>Done</button>
      </div>
    </div>
  )
}
