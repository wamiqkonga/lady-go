import { useEffect, useId, useRef, type ReactNode } from 'react'

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

type Props = {
  /** Names the dialog for screen readers; also shown as its heading. */
  title: string
  onClose: () => void
  children: ReactNode
  /** The element that gets focus first (defaults to the first focusable element in the dialog). */
  initialFocusRef?: React.RefObject<HTMLElement | null>
  /** Wider dialog, for an image viewer. */
  wide?: boolean
}

/**
 * The one modal for the portal: labelled, focus is trapped inside, Escape and the backdrop close it, and focus
 * goes back to whatever opened it. Replaces window.prompt / window.confirm, which cannot be tested and do not
 * work in embedded browsers.
 */
export default function Dialog({ title, onClose, children, initialFocusRef, wide }: Props) {
  const titleId = useId()
  const ref = useRef<HTMLDivElement>(null)
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null
    const node = ref.current
    const first = initialFocusRef?.current ?? node?.querySelector<HTMLElement>(FOCUSABLE) ?? node
    first?.focus()
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.stopPropagation()
        onCloseRef.current()
        return
      }
      if (e.key !== 'Tab' || !node) return
      const items = Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE))
      if (items.length === 0) {
        e.preventDefault()
        return
      }
      const head = items[0]
      const tail = items[items.length - 1]
      if (e.shiftKey && (document.activeElement === head || document.activeElement === node)) {
        e.preventDefault()
        tail.focus()
      } else if (!e.shiftKey && document.activeElement === tail) {
        e.preventDefault()
        head.focus()
      } else if (!node.contains(document.activeElement)) {
        e.preventDefault()
        head.focus()
      }
    }
    document.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('keydown', onKey, true)
      opener?.focus?.()
    }
    // Run once per open: the focus handling must not restart when the parent re-renders.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="dialog-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div ref={ref} className={`dialog${wide ? ' dialog-wide' : ''}`} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}>
        <h2 id={titleId} className="dialog-title">{title}</h2>
        {children}
      </div>
    </div>
  )
}
