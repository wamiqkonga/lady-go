import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import Icon from '../../components/Icons'
import { DriverScene } from '../../components/Illustrations'
import ThemeToggle from '../../components/ThemeToggle'
import { requestOtp, verifyOtp, AuthError } from '../lib/auth'
import { normalizePhone } from '../lib/phone'

export default function Login() {
  const navigate = useNavigate()
  const [phone, setPhone] = useState('')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [stage, setStage] = useState<'phone' | 'code'>('phone')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [cooldown, setCooldown] = useState(0)

  useEffect(() => {
    if (cooldown <= 0) return
    const timer = setTimeout(() => setCooldown(cooldown - 1), 1000)
    return () => clearTimeout(timer)
  }, [cooldown])

  async function sendCode() {
    // The API needs "+<country code><number>"; a driver usually types her plain 10-digit mobile number.
    const normalized = normalizePhone(phone)
    if ('error' in normalized) {
      setError(normalized.error)
      return
    }
    setPhone(normalized.phone)
    setBusy(true)
    setError(null)
    try {
      await requestOtp(normalized.phone, email.trim())
      setStage('code')
      setCode('')
      setCooldown(30)
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
        <div className="auth-layout">
          <aside className="welcome">
            <DriverScene className="welcome-scene" />
            <div className="welcome-copy">
              <p className="welcome-title">Welcome, driver</p>
              <p>Register in a few minutes. Your progress is saved, so you can stop and come back.</p>
              <ul className="welcome-points">
                <li><Icon name="check" size={18} /> Everything from your phone, no office visit</li>
                <li><Icon name="check" size={18} /> Our team reviews your documents</li>
                <li><Icon name="check" size={18} /> Check your status any time</li>
              </ul>
            </div>
          </aside>
          <div className="auth-card">
        <h1>Driver registration</h1>
        <p className="muted">
          {stage === 'phone' ? 'Step 1 of 2 · Tell us how to reach you' : 'Step 2 of 2 · Confirm your code'}
        </p>
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
              placeholder="9900000000"
              autoComplete="tel"
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
            <p className="muted">
              Your 10-digit mobile number is enough; we add +91 for you. For a number outside India, include the
              country code, for example +44. We email your sign-in code to this address, so use the same email each
              time you sign in.
            </p>
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
            <p className="muted">Enter the 6-digit code we emailed to {email}. It can take a minute. Check your spam folder too.</p>
            <label htmlFor="code">Code</label>
            <input
              id="code"
              type="text"
              inputMode="numeric"
              pattern="[0-9]{6}"
              autoComplete="one-time-code"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
              autoFocus
            />
            {error && <p role="alert" className="error">{error}</p>}
            <button type="submit" disabled={busy || code.length !== 6}>
              Continue
            </button>
            <button type="button" className="quiet" onClick={sendCode} disabled={busy || cooldown > 0}>
              {cooldown > 0 ? `Send a new code in ${cooldown}s` : 'Send a new code'}
            </button>
            <button type="button" className="quiet" onClick={() => setStage('phone')} disabled={busy}>
              Use different details
            </button>
          </form>
        )}
          </div>
        </div>
      </div>
    </div>
  )
}
