import { useRef, useState } from 'react'
import { client, unwrap, ApiCallError } from '../lib/client'
import Dialog from './Dialog'

const MIN_ZOOM = 0.5
const MAX_ZOOM = 4

/**
 * "View" fetches a fresh short-lived signed URL (this call is what writes the access log) and opens the
 * watermarked derivative in a dialog: fitted to the screen, with zoom and rotate. The URL is never cached.
 */
export default function DocumentViewer({ documentId, title }: { documentId: string; title: string }) {
  const [url, setUrl] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [imageFailed, setImageFailed] = useState(false)
  const [zoom, setZoom] = useState(1)
  const [turns, setTurns] = useState(0)
  const opener = useRef<HTMLButtonElement>(null)

  async function open() {
    setError(null)
    setImageFailed(false)
    setLoading(true)
    try {
      setUrl(unwrap(await client.GET('/api/v1/admin/driver-documents/{id}/view', { params: { path: { id: documentId } } })))
      setZoom(1)
      setTurns(0)
    } catch (err) {
      setError(err instanceof ApiCallError ? err.message : 'Failed to load image.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <button ref={opener} type="button" onClick={open} disabled={loading} aria-label={`View ${title}`}>
        {loading ? 'Opening…' : 'View'}
      </button>
      {error && <p role="alert">{error}</p>}
      {url && (
        <Dialog title={title} onClose={() => setUrl(null)} wide>
          <div className="viewer-toolbar" role="toolbar" aria-label="Image controls">
            <button type="button" onClick={() => setZoom((z) => Math.max(MIN_ZOOM, +(z - 0.25).toFixed(2)))} disabled={zoom <= MIN_ZOOM}>Zoom out</button>
            <button type="button" onClick={() => setZoom((z) => Math.min(MAX_ZOOM, +(z + 0.25).toFixed(2)))} disabled={zoom >= MAX_ZOOM}>Zoom in</button>
            <button type="button" onClick={() => setZoom(1)}>Fit to screen</button>
            <button type="button" onClick={() => setTurns((t) => (t + 1) % 4)}>Rotate</button>
            <span className="muted" aria-live="polite">{Math.round(zoom * 100)}%</span>
            <button type="button" className="btn-primary viewer-close" onClick={() => setUrl(null)}>Close</button>
          </div>
          <div className="viewer-stage">
            {imageFailed ? (
              <div role="alert">
                <p>Could not load this photo. Try again.</p>
                <button type="button" onClick={open} disabled={loading}>Retry</button>
              </div>
            ) : (
            <img
              onError={() => setImageFailed(true)}
              src={url}
              alt={title}
              className="viewer-image"
              style={{ transform: `rotate(${turns * 90}deg) scale(${zoom})` }}
              data-zoom={zoom}
              data-turns={turns}
            />
            )}
          </div>
        </Dialog>
      )}
    </>
  )
}
