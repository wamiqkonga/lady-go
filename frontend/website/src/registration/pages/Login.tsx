import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import ThemeToggle from '../../components/ThemeToggle'
import { requestOtp, verifyOtp, AuthError } from '../lib/auth'

export default function Login() {
  const navigate = useNavigate()
  const [phone, setPhone] = useState('')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [stage, setStage] = useState<'phone' | 'code'>('phone')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function sendCode() {
    setBusy(true)
    setError(null)
    try {
      await requestOtp(phone, email.trim())
      setStage('code')
    } catch (err) {
      setError(err instanceof AuthError ? err.message : 'Failed to send the code.')
    } finally {
      setBusy(false)
    }
  }

  async function confirmCode() {
    setBusy(true)
    setError(null)
    try {
      await verifyOtp(phone, code)
      navigate('/driver/register', { replace: true })
    } catch (err) {
      setError(err instanceof AuthError ? err.message : 'Incorrect code.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="app-shell">
      <div className="topbar">
        <Link to="/driver" className="brand">Lady Go — Drive with us</Link>
        <ThemeToggle />
      </div>
      <div className="content login">
        <h1>Driver registration</h1>
        {stage === 'phone' ? (
          <form
            className="form"
            onSubmit={(e) => {
              e.preventDefault()
              sendCode()
            }}
          >
            <label htmlFor="phone">Phone number</label>
            <input
              id="phone"
              type="tel"
              inputMode="tel"
              placeholder="+919900000000"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              autoFocus
            />
            <label htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              inputMode="email"
              autoComplete="email"
              placeholder="you@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
            <p className="muted">We email your sign-in code to this address. Use the same email each time you sign in.</p>
            {error && <p role="alert" className="error">{error}</p>}
            <button type="submit" disabled={busy || !phone.trim() || !email.trim()}>
              Send code
            </button>
          </form>
        ) : (
          <form
            className="form"
            onSubmit={(e) => {
              e.preventDefault()
              confirmCode()
            }}
          >
            <p className="muted">Enter the 6-digit code we emailed to {email}.</p>
            <label htmlFor="code">Code</label>
            <input
              id="code"
              type="text"
              inputMode="numeric"
              pattern="[0-9]{6}"
              maxLength={6}
              value={code}
              onChange={(e) => setCode(e.target.value)}
              autoFocus
            />
            {error && <p role="alert" className="error">{error}</p>}
            <button type="submit" disabled={busy || code.length !== 6}>
              Continue
            </button>
            <button type="button" className="quiet" onClick={() => setStage('phone')} disabled={busy}>
              Use different details
            </button>
          </form>
        )}
      </div>
    </div>
  )
}
