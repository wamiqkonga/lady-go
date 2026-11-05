import { useEffect, useState } from 'react'
import { LinkCell } from '../components/RowLink'
import { documentLabel, documentStatusLabel } from '../lib/documents'
import { client, unwrap, ApiCallError } from '../lib/client'
import VerificationTabs from '../components/VerificationTabs'
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

  return (
    <section>
      <h1>Driver verification</h1>
      <VerificationTabs />
      {error && <p role="alert">{error}</p>}
      {!error && rows === null && <p className="muted">Loading…</p>}
      {rows !== null && rows.length === 0 && <p className="muted">No documents pending review.</p>}
      {rows !== null && rows.length > 0 && <div className="table-scroll">
        <table className="list-table stack-table">
          <thead>
            <tr>
              <th>Submitted</th>
              <th>Driver</th>
              <th>Phone</th>
              <th>Document type</th>
              <th>Status</th>
              <th>Expires</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const expired = Boolean(row.expiresAt) && new Date(row.expiresAt!) < new Date()
              const to = `/driver-verification/${row.driverId}`
              return (
                <tr key={row.id}>
                  <LinkCell to={to} label="Submitted">{row.createdAt ? new Date(row.createdAt).toLocaleString() : '—'}</LinkCell>
                  <LinkCell to={to} first label="Driver">{row.driverName ?? 'Name not provided'}</LinkCell>
                  <LinkCell to={to} label="Phone">{row.driverPhoneMasked ?? '—'}</LinkCell>
                  <LinkCell to={to} label="Document type">{documentLabel(row.type)}</LinkCell>
                  <LinkCell to={to} label="Status">{documentStatusLabel(row.status)}</LinkCell>
                  <LinkCell to={to} label="Expires">
                    {row.expiresAt ?? '—'}
                    {expired && <span className="badge-expired"> expired</span>}
                  </LinkCell>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>}
    </section>
  )
}
