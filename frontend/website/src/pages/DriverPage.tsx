import { Link } from 'react-router-dom'

const perks = [
  { title: 'Your hours, your call', body: 'Go online when it suits you. No shifts, no minimum hours.' },
  { title: 'Women riders only', body: 'Drive a women-focused service built around rider and driver safety.' },
  { title: 'Cash in hand', body: 'Riders pay cash today; your earnings are clear on every trip.' },
  { title: 'Safety backup', body: 'In-app emergency button, live trip sharing and a support team behind you.' },
]

const steps = [
  'Log in with your phone number (one-time code).',
  'Add your vehicle details.',
  'Upload your licence, RC, insurance and other documents, plus a selfie.',
  'Accept the driver declaration.',
  'Our team reviews your application and tells you the result in the same place.',
]

export default function DriverPage() {
  return (
    <article className="page">
      <section className="earn-hero">
        <p className="earn-eyebrow">Drive with Lady Go</p>
        <h1>
          Earn <span className="earn-amount">₹30,000+</span> a month with us
        </h1>
        <p className="lead">
          Women drivers wanted. Register yourself in a few minutes from your phone, no agent and no
          office visit.
        </p>
        <div className="actions">
          <Link className="button" to="/driver/login">
            Register now
          </Link>
          <Link className="button secondary" to="/driver/login">
            Check my application
          </Link>
        </div>
        <p className="note">
          Earnings depend on city, hours driven and demand; ₹30,000+ is an indicative monthly figure for
          active drivers, not a guarantee.
        </p>
      </section>

      <section className="cards" aria-label="Why drive with Lady Go">
        {perks.map((p) => (
          <div className="card" key={p.title}>
            <h2>{p.title}</h2>
            <p>{p.body}</p>
          </div>
        ))}
      </section>

      <h2>What you will need</h2>
      <ul>
        <li>A valid driving licence</li>
        <li>Vehicle registration (RC), insurance, fitness and pollution certificates</li>
        <li>A clear photo of your vehicle showing the number plate</li>
        <li>A selfie and a phone number that can receive an SMS</li>
      </ul>

      <h2>How to register</h2>
      <ol className="steps">
        {steps.map((s) => (
          <li key={s}>{s}</li>
        ))}
      </ol>
      <div className="actions">
        <Link className="button" to="/driver/login">
          Start your registration
        </Link>
      </div>
    </article>
  )
}
