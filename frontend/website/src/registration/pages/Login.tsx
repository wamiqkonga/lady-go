import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import Icon from '../../components/Icons'
import { DriverScene } from '../../components/Illustrations'
import NewPasswordFields from '../components/NewPasswordFields'
import {
  requestOtp, verifyOtp, passwordLogin, getPasswordStatus, isNewCaptain, setPassword as saveNewPassword, forgotPassword, AuthError,
} from '../lib/auth'
import { passwordProblem } from '../lib/passwordRules'
import { normalizePhone } from '../lib/phone'

export default function Login() {
  const navigate = useNavigate()
  const [phone, setPhone] = useState('')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [stage, setStage] = useState<'phone' | 'code' | 'set-password' | 'password' | 'forgot'>('phone')
  const [identifier, setIdentifier] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [canSkip, setCanSkip] = useState(false)
  const [forgotReply, setForgotReply] = useState<string | null>(null)
  const [showPassword, setShowPassword] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [cooldown, setCooldown] = useState(0)

  useEffect(() => {
    if (cooldown <= 0) return
    const timer = setTimeout(() => setCooldown(cooldown - 1), 1000)
    return () => clearTimeout(timer)
  }, [cooldown])

  async function sendCode() {
    // The API needs "+<country code><number>"; a captain usually types her plain 10-digit mobile number.
    if (!phone.trim()) {
      setError('Enter your mobile number.')
      return
    }
    if (!email.trim()) {
      setError('Enter your email address. We send your verification code there.')
      return
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      setError('Enter a valid email address, for example you@example.com.')
      return
    }
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
      // Ask for a password unless the captain already has one. Never block the sign-in if this check fails.
      const hasPassword = await getPasswordStatus().catch(() => true)
      if (!hasPassword) {
        setCanSkip(!(await isNewCaptain().catch(() => false))) // a brand-new sign-up must set one; existing captains may wait
        setStage('set-password')
        return
      }
      navigate('/captain/register', { replace: true })
    } catch (err) {
      setError(err instanceof AuthError ? err.message : 'Incorrect code.')
    } finally {
      setBusy(false)
    }
  }

  async function savePassword() {
    const problem = passwordProblem(password, confirm)
    if (problem) {
      setError(problem)
      return
    }
    setBusy(true)
    setError(null)
    try {
      await saveNewPassword(password)
      navigate('/captain/register', { replace: true })
    } catch (err) {
      setError(err instanceof AuthError ? err.message : 'Could not save the password.')
    } finally {
      setBusy(false)
    }
  }

  function identifierToSend(): string {
    const typed = identifier.trim()
    if (typed.includes('@')) return typed
    const normalized = normalizePhone(typed)
    return 'phone' in normalized ? normalized.phone : typed
  }

  async function signInWithPassword() {
    setBusy(true)
    setError(null)
    try {
      await passwordLogin(identifierToSend(), password)
      navigate('/captain/register', { replace: true })
    } catch (err) {
      setError(
        err instanceof AuthError && err.code === 'RATE_LIMITED'
          ? 'Too many tries. Wait a little and try again, or sign in with a code.'
          : err instanceof AuthError && err.code === 'UNAUTHORIZED'
            ? 'Phone, email or password is not correct.'
            : err instanceof AuthError ? err.message : 'Could not sign in.',
      )
    } finally {
      setBusy(false)
    }
  }

  async function sendResetLink() {
    setBusy(true)
    setError(null)
    try {
      setForgotReply(await forgotPassword(identifierToSend()))
    } catch (err) {
      setError(err instanceof AuthError ? err.message : 'Could not send the link.')
    } finally {
      setBusy(false)
    }
  }

  function goTo(next: typeof stage) {
    setStage(next)
    setError(null)
    setForgotReply(null)
  }

  return (
    <div className="app-shell">
      <div className="topbar">
        <Link to="/captain" className="brand">Lady Go — Drive with us</Link>
      </div>
      <div className="content login">
        <div className="auth-layout">
          <aside className="welcome">
            <DriverScene className="welcome-scene" />
            <div className="welcome-copy">
              <p className="welcome-title">Welcome, captain</p>
              <p>Register in a few minutes. Your progress is saved, so you can stop and come back.</p>
              <ul className="welcome-points">
                <li><Icon name="check" size={18} /> Everything from your phone, no office visit</li>
                <li><Icon name="check" size={18} /> Our team reviews your documents</li>
                <li><Icon name="check" size={18} /> Check your status any time</li>
              </ul>
            </div>
          </aside>
          <div className="auth-card">
        <h1>Captain registration</h1>
        <p className="muted">
          {stage === 'phone' && 'Step 1 of 3 · Tell us how to reach you'}
          {stage === 'code' && 'Step 2 of 3 · Confirm your code'}
          {stage === 'set-password' && 'Step 3 of 3 · Create your password'}
          {stage === 'password' && 'Sign in with your password'}
          {stage === 'forgot' && 'Forgot your password?'}
        </p>
        {stage === 'phone' ? (
          <form
            className="form"
            noValidate
            aria-busy={busy}
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
              country code, for example +44. We email your verification code to this address, so use the same email each
              time you register.
            </p>
            {error && <p role="alert" className="error">{error}</p>}
            <button type="submit" disabled={busy}>
              {busy ? (
                <>
                  <span className="spinner" aria-hidden="true" /> Sending your code…
                </>
              ) : (
                'Send code'
              )}
            </button>
            {/* Announced to screen readers; the visible text is in the button. Gone once the code screen replaces this form. */}
            <span role="status" className="sr-only">{busy ? 'Sending your code, please wait.' : ''}</span>
            <button type="button" className="quiet" onClick={() => goTo('password')} disabled={busy}>
              Already registered? Sign in with your password
            </button>
          </form>
        ) : stage === 'set-password' ? (
          <form className="form" aria-busy={busy} onSubmit={(e) => { e.preventDefault(); savePassword() }}>
            <p className="muted">
              Choose a password. You can then sign in to the Lady Go captain app and to this website with your phone
              number or email and this password.
            </p>
            <NewPasswordFields password={password} confirm={confirm} onPassword={setPassword} onConfirm={setConfirm} />
            {error && <p role="alert" className="error">{error}</p>}
            <button type="submit" disabled={busy || !password || !confirm}>
              {busy ? (<><span className="spinner" aria-hidden="true" /> Saving…</>) : 'Save password and continue'}
            </button>
            {canSkip && (
              <button type="button" className="quiet" disabled={busy} onClick={() => navigate('/captain/register', { replace: true })}>
                Not now
              </button>
            )}
          </form>
        ) : stage === 'password' ? (
          <form className="form" aria-busy={busy} onSubmit={(e) => { e.preventDefault(); signInWithPassword() }}>
            <label htmlFor="identifier">Phone number or email</label>
            <input id="identifier" type="text" inputMode="email" autoComplete="username" autoFocus
              value={identifier} onChange={(e) => setIdentifier(e.target.value)} />
            <label htmlFor="password">Password</label>
            <input id="password" type={showPassword ? 'text' : 'password'} autoComplete="current-password"
              value={password} onChange={(e) => setPassword(e.target.value)} />
            <label className="check-row">
              <input type="checkbox" checked={showPassword} onChange={(e) => setShowPassword(e.target.checked)} /> Show password
            </label>
            {error && <p role="alert" className="error">{error}</p>}
            <button type="submit" disabled={busy || !identifier.trim() || !password}>
              {busy ? (<><span className="spinner" aria-hidden="true" /> Signing in…</>) : 'Sign in'}
            </button>
            <button type="button" className="quiet" onClick={() => goTo('forgot')} disabled={busy}>Forgot password?</button>
            <button type="button" className="quiet" onClick={() => goTo('phone')} disabled={busy}>Use a code instead</button>
          </form>
        ) : stage === 'forgot' ? (
          <form className="form" aria-busy={busy} onSubmit={(e) => { e.preventDefault(); sendResetLink() }}>
            <label htmlFor="forgot-identifier">Phone number or email</label>
            <input id="forgot-identifier" type="text" inputMode="email" autoComplete="username" autoFocus
              value={identifier} onChange={(e) => setIdentifier(e.target.value)} />
            {forgotReply && <p role="status">{forgotReply} It can take a minute; check your spam folder too.</p>}
            {error && <p role="alert" className="error">{error}</p>}
            <button type="submit" disabled={busy || !identifier.trim()}>
              {busy ? (<><span className="spinner" aria-hidden="true" /> Sending…</>) : 'Email me a link'}
            </button>
            <button type="button" className="quiet" onClick={() => goTo('password')} disabled={busy}>Back to sign in</button>
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
