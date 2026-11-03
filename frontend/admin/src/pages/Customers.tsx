import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { client, unwrap, ApiCallError } from '../lib/client'
import type { components } from '../lib/api-types'
import './customers.css'

type Row = components['schemas']['AdminCustomerSummaryResponse']
type Status = NonNullable<Row['status']>

const STATUSES: Status[] = ['ACTIVE', 'SUSPENDED', 'DELETED']

export function StatusBadge({ status }: { status?: string }) {
  return <span className={`status-badge ${(status ?? '').toLowerCase()}`}>{status ?? '—'}</span>
}

export default function Customers() {
  const [rows, setRows] = useState<Row[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [status, setStatus] = useState('')

  useEffect(() => {
    let current = true
    client
      .GET('/api/v1/admin/customers', {
        params: {
          query: {
            q: q || undefined,
            status: status ? (status as Status) : undefined,
            pageable: { page: 0, size: 50 },
          },
        },
      })
      .then((result) => {
        if (!current) return
        setError(null)
        setRows(unwrap(result).content ?? [])
      })
      .catch((err: unknown) => {
        if (current) setError(err instanceof ApiCallError ? err.message : 'Failed to load customers.')
      })
    return () => {
      current = false
    }
  }, [q, status])

  return (
    <section>
      <h1>Customers</h1>
      <form
        className="filters customer-search"
        role="search"
        onSubmit={(e) => {
          e.preventDefault()
          setQ(text.trim())
        }}
      >
        <label>
          Search
          <input
            type="search"
            placeholder="Name, phone or customer id"
            value={text}
            onChange={(e) => setText(e.target.value)}
          />
        </label>
        <label>
          Status
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">All</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </label>
        <button type="submit">Search</button>
      </form>

      {error && <p role="alert">{error}</p>}
      {!error && rows === null && <p className="muted">Loading…</p>}
      {!error && rows !== null && rows.length === 0 && <p className="muted">No customers match.</p>}
      {!error && rows !== null && rows.length > 0 && (
        <div className="table-scroll">
          <table className="list-table stack-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Phone</th>
                <th>Status</th>
                <th>Joined</th>
                <th>Last sign-in</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id}>
                  <td data-label="Name">
                    <Link to={`/customers/${row.id}`}>{row.name || 'Unnamed customer'}</Link>
                  </td>
                  <td data-label="Phone">{row.phoneMasked ?? '—'}</td>
                  <td data-label="Status"><StatusBadge status={row.status} /></td>
                  <td data-label="Joined">{row.createdAt ? new Date(row.createdAt).toLocaleDateString() : '—'}</td>
                  <td data-label="Last sign-in">{row.lastLoginAt ? new Date(row.lastLoginAt).toLocaleString() : 'Never'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
