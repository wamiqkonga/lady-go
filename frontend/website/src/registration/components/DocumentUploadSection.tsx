import { useState } from 'react'
import { uploadDocument } from '../lib/upload'
import { ApiCallError } from '../lib/client'
import { validateImageFile } from '../lib/fileValidation'
import { fileToDataUrl } from '../lib/preview'
import DocumentStatusBadge from './DocumentStatusBadge'
import type { components } from '../lib/api-types'

type DocumentType = NonNullable<components['schemas']['DriverDocumentResponse']['type']>
type DriverDocumentResponse = components['schemas']['DriverDocumentResponse']

/** Today's date in the driver's own timezone as YYYY-MM-DD (toISOString would give the UTC date). */
function localToday(): string {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

export default function DocumentUploadSection({
  type, label, tip, existing, needsNumber, needsExpiry, onUploaded,
}: {
  type: DocumentType
  label: string
  tip?: string
  existing?: DriverDocumentResponse
  needsNumber?: boolean
  needsExpiry?: boolean
  onUploaded: (doc: DriverDocumentResponse) => void
}) {
  const [file, setFile] = useState<File | null>(null)
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [number, setNumber] = useState('')
  const [expiresAt, setExpiresAt] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [today] = useState(localToday)

  function onFileChosen(chosen: File | null) {
    setError(null)
    if (!chosen) {
      setFile(null)
      setPreviewUrl(null)
      return
    }
    const validationError = validateImageFile(chosen)
    if (validationError) {
      setError(validationError)
      setFile(null)
      setPreviewUrl(null)
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
      const doc = await uploadDocument(file, type, {
        number: needsNumber ? number : undefined,
        expiresAt: needsExpiry ? expiresAt : undefined,
      })
      onUploaded(doc)
      onFileChosen(null)
      setNumber('')
      setExpiresAt('')
    } catch (err) {
      setError(err instanceof ApiCallError ? err.message : 'Upload failed.')
    } finally {
      setBusy(false)
    }
  }

  const canResubmit = !existing || existing.status === 'REJECTED'

  return (
    <div className="section">
      <h2>{label}</h2>
      {tip && <p className="muted">{tip}</p>}
      {existing && (
        <p>
          <DocumentStatusBadge status={existing.status} />
          {existing.status === 'REJECTED' && existing.reviewNote && (
            // React escapes text children by construction - never dangerouslySetInnerHTML, so a
            // hostile reviewer note renders as literal text, never runs.
            <span className="review-note"> {existing.reviewNote}</span>
          )}
        </p>
      )}
      {existing?.documentNumber && <p className="muted">Number: {existing.documentNumber}</p>}
      {existing?.expiresAt && <p className="muted">Expires: {existing.expiresAt}</p>}

      {canResubmit && (
        <>
          <div className="form">
            <label htmlFor={`file-${type}`}>Photo (JPEG or PNG, up to 8 MB)</label>
            <input
              id={`file-${type}`}
              type="file"
              accept="image/jpeg,image/png"
              onChange={(e) => onFileChosen(e.target.files?.[0] ?? null)}
            />
            {previewUrl && <img src={previewUrl} alt="Preview" className="preview" />}
            {needsNumber && (
              <>
                <label htmlFor={`num-${type}`}>Document number</label>
                <input
                  id={`num-${type}`}
                  type="text"
                  autoCapitalize="characters"
                  autoComplete="off"
                  value={number}
                  onChange={(e) => setNumber(e.target.value)}
                />
              </>
            )}
            {needsExpiry && (
              <>
                <label htmlFor={`exp-${type}`}>Expiry date</label>
                <input
                  id={`exp-${type}`}
                  type="date"
                  min={today}
                  value={expiresAt}
                  onChange={(e) => setExpiresAt(e.target.value)}
                />
              </>
            )}
          </div>
          {error && <p role="alert" className="error">{error}</p>}
          <div className="row-actions">
            <button type="button" onClick={submit} disabled={busy || !file}>
              {busy ? 'Uploading…' : existing ? 'Resubmit' : 'Upload'}
            </button>
          </div>
        </>
      )}
    </div>
  )
}
