import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { client, unwrap, ApiCallError } from '../lib/client'
import type { components } from '../lib/api-types'

type DocumentRow = components['schemas']['DriverDocumentAdminResponse']

export default function DriverDocumentQueue() {
  const [rows, setRows] = useState<DocumentRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    client
      .GET('/api/v1/admin/driver-documents', { params: { query: { pageable: { page: 0, size: 50 } } } })
      .then((result) => {
        if (cancelled) return
        setRows(unwrap(result).content ?? [])
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setError(err instanceof ApiCallError ? err.message : 'Failed to load the queue.')
      })
    return () => {
      cancelled = true
    }
  }, [])

  if (error) return <p role="alert">{error}</p>
  if (rows === null) return <p className="muted">Loading…</p>
  if (rows.length === 0) return <p className="muted">No documents pending review.</p>

  return (
    <section>
      <h1>Driver verification</h1>
      <table className="list-table">
        <thead>
          <tr>
            <th>Submitted</th>
            <th>Driver</th>
            <th>Document type</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id}>
              <td>{row.createdAt ? new Date(row.createdAt).toLocaleString() : '—'}</td>
              <td>
                <Link to={`/driver-verification/${row.driverId}`}>{row.driverId?.slice(0, 8)}…</Link>
              </td>
              <td>{row.type}</td>
              <td>{row.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
