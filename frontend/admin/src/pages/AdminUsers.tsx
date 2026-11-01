import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiCallError, client, unwrap } from '../lib/client'
import { useStepUp } from '../lib/stepUpContext'
import { STAFF_ROLES, statusLabel, type StaffRole, type StaffUser } from '../lib/staff'

function InviteForm({ onInvited }: { onInvited: () => void }) {
  const { runWithStepUp } = useStepUp()
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [role, setRole] = useState<StaffRole>('SUPPORT')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sentTo, setSentTo] = useState<string | null>(null)

  const ready = email.trim() !== '' && name.trim() !== '' && reason.trim() !== ''

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    setSentTo(null)
    try {
      await runWithStepUp(async () =>
        unwrap(await client.POST('/api/v1/admin/users', { body: { email: email.trim(), name: name.trim(), role, reason: reason.trim() } })),
      )
      setSentTo(email.trim())
      setEmail('')
      setName('')
      setReason('')
      onInvited()
    } catch (err) {
      setError(err instanceof ApiCallError ? err.message : 'The invitation was not sent. Try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="form panel" onSubmit={submit} aria-label="Invite a staff member">
      <h2>Invite a staff member</h2>
      <p className="muted">They get an email with a one-time link to choose their own password. You never see or set it.</p>
      <label htmlFor="invite-name">Full name</label>
      <input id="invite-name" type="text" maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
      <label htmlFor="invite-email">Email</label>
      <input id="invite-email" type="email" maxLength={254} value={email} onChange={(e) => setEmail(e.target.value)} />
      <label htmlFor="invite-role">Role</label>
      <select id="invite-role" value={role} onChange={(e) => setRole(e.target.value as StaffRole)}>
        {STAFF_ROLES.map((r) => (
          <option key={r} value={r}>
            {r}
          </option>
        ))}
      </select>
      <label htmlFor="invite-reason">Reason (required)</label>
      <input id="invite-reason" type="text" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      <button type="submit" disabled={busy || !ready}>
        Send invitation
      </button>
      {error && <p role="alert">{error}</p>}
      {sentTo && <p role="status">Invitation sent to {sentTo}. The link works once and expires.</p>}
    </form>
  )
}

export default function AdminUsers() {
  const [rows, setRows] = useState<StaffUser[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    setError(null)
    client
      .GET('/api/v1/admin/users')
      .then((result) => setRows(unwrap(result) ?? []))
      .catch((err: unknown) => setError(err instanceof ApiCallError ? err.message : 'Failed to load staff accounts.'))
  }, [])

  useEffect(() => load(), [load])

  return (
    <section>
      <h1>Staff accounts</h1>
      <InviteForm onInvited={load} />
      {error && <p role="alert">{error}</p>}
      {rows === null && !error && <p className="muted">Loading…</p>}
      {rows && (
        <div className="table-scroll">
          <table className="list-table stack-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Email</th>
                <th>Role</th>
                <th>Status</th>
                <th>MFA</th>
                <th>Last sign-in</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((u) => (
                <tr key={u.id}>
                  <td data-label="Name">
                    <Link to={`/users/${u.id}`}>{u.name || 'Unnamed'}</Link>
                  </td>
                  <td data-label="Email">{u.maskedEmail}</td>
                  <td data-label="Role">{u.role}</td>
                  <td data-label="Status">
                    {statusLabel(u)}
                    {u.locked && ' · Locked'}
                  </td>
                  <td data-label="MFA">{u.mfaEnrolled ? 'Enrolled' : 'Not set up'}</td>
                  <td data-label="Last sign-in">{u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleString() : 'Never'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
