import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { ApiCallError, client, unwrap } from '../lib/client'
import { useStepUp } from '../lib/stepUpContext'
import Dialog from '../components/Dialog'
import { LinkCell } from '../components/RowLink'
import { STAFF_ROLES, statusLabel, type StaffRole, type StaffUser } from '../lib/staff'

function InviteForm({ onInvited, onCancel }: { onInvited: (email: string) => void; onCancel: () => void }) {
  const { runWithStepUp } = useStepUp()
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [role, setRole] = useState<StaffRole>('SUPPORT')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const ready = email.trim() !== '' && name.trim() !== '' && reason.trim() !== ''

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await runWithStepUp(async () =>
        unwrap(await client.POST('/api/v1/admin/users', { body: { email: email.trim(), name: name.trim(), role, reason: reason.trim() } })),
      )
      onInvited(email.trim())
    } catch (err) {
      setError(err instanceof ApiCallError ? err.message : 'The invitation was not sent. Try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title="Invite a staff member" onClose={onCancel}>
      <form className="form" onSubmit={submit} aria-label="Invite a staff member">
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
        {error && <p role="alert">{error}</p>}
        <div className="row-actions">
          <button type="submit" className="btn-primary" disabled={busy || !ready}>
            Send invitation
          </button>
          <button type="button" onClick={onCancel} disabled={busy}>Cancel</button>
        </div>
      </form>
    </Dialog>
  )
}

export default function AdminUsers() {
  const [rows, setRows] = useState<StaffUser[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [inviting, setInviting] = useState(false)
  const [sentTo, setSentTo] = useState<string | null>(null)

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
      <div className="row-actions">
        <button type="button" className="btn-primary" onClick={() => { setSentTo(null); setInviting(true) }}>Invite a staff member</button>
      </div>
      {sentTo && <p className="success" role="status">Invitation sent to {sentTo}. The link works once and expires.</p>}
      {inviting && <InviteForm onInvited={(email) => { setInviting(false); setSentTo(email); load() }} onCancel={() => setInviting(false)} />}
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
              {rows.map((u) => {
                const to = `/users/${u.id}`
                return (
                  <tr key={u.id}>
                    <LinkCell to={to} first label="Name">{u.name || u.maskedEmail || 'Unnamed'}</LinkCell>
                    <LinkCell to={to} label="Email">{u.maskedEmail}</LinkCell>
                    <LinkCell to={to} label="Role">{u.role}</LinkCell>
                    <LinkCell to={to} label="Status">{statusLabel(u)}{u.locked && ' · Locked'}</LinkCell>
                    <LinkCell to={to} label="MFA">{u.mfaEnrolled ? 'Enrolled' : 'Not set up'}</LinkCell>
                    <LinkCell to={to} label="Last sign-in">{u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleString() : 'Never'}</LinkCell>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
