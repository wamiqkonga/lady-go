import { useEffect, useState } from 'react'
import { fetchDocumentImage } from '../lib/upload'

/** Thumbnail of what the captain already sent, fetched through the authenticated API. A failure shows a
 *  short note instead of a broken image; it never blocks the rest of the step. */
export default function SubmittedPreview({ documentId, label }: { documentId: string; label: string }) {
  const [state, setState] = useState<{ id: string; url: string | null; failed: boolean }>({ id: '', url: null, failed: false })

  useEffect(() => {
    let cancelled = false
    fetchDocumentImage(documentId)
      .then((url) => !cancelled && setState({ id: documentId, url, failed: false }))
      .catch(() => !cancelled && setState({ id: documentId, url: null, failed: true }))
    return () => {
      cancelled = true
    }
  }, [documentId])

  if (state.id !== documentId) return <p className="muted">Loading your photo…</p>
  if (state.failed || !state.url) return <p className="muted">Your photo was received, but we could not show it here right now.</p>
  return <img src={state.url} alt={`${label} you submitted`} className="preview thumb" />
}
