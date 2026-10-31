import { useCallback, useEffect, useState } from 'react'
import { client, unwrap, ApiCallError } from '../lib/client'
import type { components } from '../lib/api-types'
import { Link } from 'react-router-dom'
import { useSession } from '../lib/sessionContext'
import { useStepUp } from '../lib/stepUpContext'

type Entry = components['schemas']['AdminConfigEntryResponse']

function EditRow({ entry, onSaved }: { entry: Entry; onSaved: () => void }) {
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState(entry.configValue ?? '')
  const [reason, setReason] = useState('')
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [needsSetup, setNeedsSetup] = useState(false)
  const { runWithStepUp } = useStepUp()

  async function save() {
    if (!entry.id || !reason.trim()) return
    setBusy(true)
    setError(null)
    setNeedsSetup(false)
    try {
      await runWithStepUp(async () => {
        const result = await client.PUT('/api/v1/admin/config/{id}', {
          params: { path: { id: entry.id! } },
          body: { value, reason },
        })
        return unwrap(result)
      })
      setEditing(false)
      setConfirming(false)
      setReason('')
      onSaved()
    } catch (err) {
      if (err instanceof ApiCallError && err.code === 'MFA_ENROLMENT_REQUIRED') setNeedsSetup(true)
      setError(err instanceof ApiCallError ? err.message : 'Failed to update this value.')
    } finally {
      setBusy(false)
    }
  }

  if (!editing) {
    return (
      <button type="button" onClick={() => setEditing(true)}>
        Edit
      </button>
    )
  }

  return (
    <div className="row-actions config-edit">
      <input type="text" value={value} onChange={(e) => setValue(e.target.value)} disabled={confirming} />
      {!confirming ? (
        <>
          <button type="button" onClick={() => setConfirming(true)} disabled={value === entry.configValue}>
            Save
          </button>
          <button type="button" onClick={() => { setEditing(false); setValue(entry.configValue ?? ''); setError(null) }}>
            Cancel
          </button>
        </>
      ) : (
        <>
          <p className="muted">
            Change {entry.configKey}: <strong>{entry.configValue}</strong> → <strong>{value}</strong>
          </p>
          <input
            type="text"
            placeholder="Reason for this change (required)"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          <button type="button" onClick={save} disabled={busy || !reason.trim()}>
            Confirm
          </button>
          <button type="button" onClick={() => setConfirming(false)} disabled={busy}>
            Back
          </button>
        </>
      )}
      {error && <p role="alert">{error}</p>}
      {needsSetup && <Link to="/security">Go to Security to set up an authenticator</Link>}
    </div>
  )
}

export default function ConfigScreen() {
  const { role } = useSession()
  const canEdit = role === 'SUPER_ADMIN'
  const [rows, setRows] = useState<Entry[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    setError(null)
    client
      .GET('/api/v1/admin/config')
      .then((result) => setRows(unwrap(result) ?? []))
      .catch((err: unknown) => setError(err instanceof ApiCallError ? err.message : 'Failed to load config.'))
  }, [])

  useEffect(() => load(), [load])

  if (error) return <p role="alert">{error}</p>
  if (rows === null) return <p className="muted">Loading…</p>

  return (
    <section>
      <h1>Pricing &amp; configuration</h1>
      {!canEdit && <p className="muted">Read-only - only a SUPER_ADMIN can change these values.</p>}
      <table className="list-table">
        <thead>
          <tr>
            <th>Key</th>
            <th>Value</th>
            <th>Type</th>
            <th>Bounds</th>
            <th>Updated</th>
            {canEdit && <th>Action</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((entry) => (
            <tr key={entry.id}>
              <td>{entry.configKey}</td>
              <td>{entry.configValue}</td>
              <td>{entry.valueType}</td>
              <td>{entry.minValue != null && entry.maxValue != null ? `${entry.minValue}–${entry.maxValue}` : '—'}</td>
              <td>{entry.updatedAt ? new Date(entry.updatedAt).toLocaleString() : '—'}</td>
              {canEdit && <td><EditRow entry={entry} onSaved={load} /></td>}
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
