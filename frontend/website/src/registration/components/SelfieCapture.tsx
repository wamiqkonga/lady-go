import { useEffect, useRef, useState } from 'react'
import { uploadSelfie } from '../lib/upload'
import { ApiCallError } from '../lib/client'
import { validateImageFile } from '../lib/fileValidation'
import { fileToDataUrl } from '../lib/preview'
import DocumentStatusBadge from './DocumentStatusBadge'
import type { components } from '../lib/api-types'

type DriverDocumentResponse = components['schemas']['DriverDocumentResponse']

/** Camera capture via getUserMedia, with a plain file-input fallback for a browser/device that
 *  doesn't support it (or denies permission) - never required, always an alternative path. */
export default function SelfieCapture({
  existing, onUploaded,
}: {
  existing?: DriverDocumentResponse
  onUploaded: (doc: DriverDocumentResponse) => void
}) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const [cameraActive, setCameraActive] = useState(false)
  const [cameraUnavailable, setCameraUnavailable] = useState(false)
  const [file, setFile] = useState<File | null>(null)
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => stopCamera, [])

  function stopCamera() {
    streamRef.current?.getTracks().forEach((track) => track.stop())
    streamRef.current = null
    setCameraActive(false)
  }

  async function startCamera() {
    setError(null)
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'user' } })
      streamRef.current = stream
      if (videoRef.current) videoRef.current.srcObject = stream
      setCameraActive(true)
    } catch {
      setCameraUnavailable(true)
    }
  }

  function capture() {
    const video = videoRef.current
    if (!video) return
    const canvas = document.createElement('canvas')
    canvas.width = video.videoWidth
    canvas.height = video.videoHeight
    canvas.getContext('2d')?.drawImage(video, 0, 0)
    // toDataURL, not toBlob+URL.createObjectURL - a data: URI, not blob:, stays within the strict
    // production CSP's img-src allow-list (see lib/preview.ts's comment).
    const dataUrl = canvas.toDataURL('image/jpeg', 0.9)
    canvas.toBlob((blob) => {
      if (!blob) return
      setFile(new File([blob], 'selfie.jpg', { type: 'image/jpeg' }))
      setPreviewUrl(dataUrl)
      stopCamera()
    }, 'image/jpeg', 0.9)
  }

  function onFileChosen(chosen: File | null) {
    setError(null)
    if (!chosen) return
    const validationError = validateImageFile(chosen)
    if (validationError) {
      setError(validationError)
      return
    }
    setFile(chosen)
    fileToDataUrl(chosen).then(setPreviewUrl)
  }

  async function submit() {
    if (!file) return
    setBusy(true)
    setError(null)
    try {
      const doc = await uploadSelfie(file)
      onUploaded(doc)
      setFile(null)
      setPreviewUrl(null)
    } catch (err) {
      setError(err instanceof ApiCallError ? err.message : 'Upload failed.')
    } finally {
      setBusy(false)
    }
  }

  const canResubmit = !existing || existing.status === 'REJECTED'

  return (
    <div className="section">
      <h2>Selfie</h2>
      <p className="muted">Face the camera in good light, with no sunglasses or mask. Your whole face must be visible.</p>
      {existing && (
        <p>
          <DocumentStatusBadge status={existing.status} />
          {existing.status === 'REJECTED' && existing.reviewNote && (
            <span className="review-note"> {existing.reviewNote}</span>
          )}
        </p>
      )}

      {canResubmit && (
        <>
          {!previewUrl && !cameraActive && (
            <div className="row-actions">
              {!cameraUnavailable && (
                <button type="button" onClick={startCamera}>
                  Use camera
                </button>
              )}
              <label className="file-pick">
                <span>{cameraUnavailable ? 'Camera unavailable. Choose a photo instead' : 'Or choose a photo'}</span>
                <input
                  type="file"
                  accept="image/jpeg,image/png"
                  capture="user"
                  onChange={(e) => onFileChosen(e.target.files?.[0] ?? null)}
                />
              </label>
            </div>
          )}
          {cameraActive && (
            <>
              {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
              <video ref={videoRef} autoPlay playsInline muted className="preview" />
              <div className="row-actions">
                <button type="button" onClick={capture}>Capture</button>
                <button type="button" onClick={stopCamera}>Cancel</button>
              </div>
            </>
          )}
          {previewUrl && (
            <>
              <img src={previewUrl} alt="Selfie preview" className="preview" />
              {error && <p role="alert" className="error">{error}</p>}
              <div className="row-actions">
                <button type="button" onClick={submit} disabled={busy}>
                  {busy ? 'Uploading…' : existing ? 'Resubmit' : 'Upload'}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setFile(null)
                    setPreviewUrl(null)
                  }}
                  disabled={busy}
                >
                  Retake
                </button>
              </div>
            </>
          )}
          {error && !previewUrl && <p role="alert" className="error">{error}</p>}
        </>
      )}
    </div>
  )
}
