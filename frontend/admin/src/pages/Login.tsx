export default function Login() {
  return (
    <main className="login">
      <h1>Lady Go Admin</h1>
      <form onSubmit={(e) => e.preventDefault()} className="form">
        <label htmlFor="email">Email</label>
        <input id="email" type="email" autoComplete="username" disabled />
        <label htmlFor="password">Password</label>
        <input id="password" type="password" autoComplete="current-password" disabled />
        <button type="submit" disabled>
          Sign in
        </button>
        <p className="muted">Admin sign-in (with multi-factor authentication) is built in Phase 3.</p>
      </form>
    </main>
  )
}
