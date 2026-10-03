import Page from '../components/Page'

export default function Login() {
  return (
    <Page title="Log in" lead="Phone-number login will be available soon.">
      <form className="form" onSubmit={(e) => e.preventDefault()} aria-describedby="login-note">
        <label htmlFor="phone">Phone number</label>
        <input id="phone" name="phone" type="tel" inputMode="tel" autoComplete="tel" disabled />
        <button type="submit" className="button" disabled>
          Send code
        </button>
        <p id="login-note" className="note">
          Sign-in is not enabled yet. Authentication is built in a later development phase.
        </p>
      </form>
    </Page>
  )
}
