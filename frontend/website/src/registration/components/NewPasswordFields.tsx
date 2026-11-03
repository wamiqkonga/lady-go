import { useState } from 'react'
import { PASSWORD_HINT } from '../lib/passwordRules'

type Props = { password: string; confirm: string; onPassword: (v: string) => void; onConfirm: (v: string) => void }

/** New password + confirmation with a show/hide switch. Pasting is allowed (password managers). */
export default function NewPasswordFields({ password, confirm, onPassword, onConfirm }: Props) {
  const [show, setShow] = useState(false)
  const type = show ? 'text' : 'password'
  return (
    <>
      <label htmlFor="new-password">New password</label>
      <input id="new-password" type={type} autoComplete="new-password" value={password}
        onChange={(e) => onPassword(e.target.value)} aria-describedby="password-hint" autoFocus />
      <label htmlFor="confirm-password">Type it again</label>
      <input id="confirm-password" type={type} autoComplete="new-password" value={confirm}
        onChange={(e) => onConfirm(e.target.value)} />
      <p id="password-hint" className="muted">{PASSWORD_HINT}</p>
      <label className="check-row">
        <input type="checkbox" checked={show} onChange={(e) => setShow(e.target.checked)} /> Show password
      </label>
    </>
  )
}
