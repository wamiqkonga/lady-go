import { Link } from 'react-router-dom'
import Icon, { type IconName } from '../components/Icons'
import { RideScene } from '../components/Illustrations'

const trust: Array<{ icon: IconName; text: string }> = [
  { icon: 'shield', text: 'Drivers verified before they go online' },
  { icon: 'lock', text: 'Ride PIN confirmed at pickup' },
  { icon: 'share', text: 'Share your trip with someone you trust' },
  { icon: 'alert', text: 'One-tap call to 112' },
]

const features: Array<{ icon: IconName; title: string; body: string; wide?: boolean }> = [
  {
    icon: 'id',
    title: 'Know your driver',
    body: 'See your driver’s name, photo, vehicle and rating before the ride starts, and check the number plate when she arrives.',
    wide: true,
  },
  {
    icon: 'share',
    title: 'Share your trip',
    body: 'Send a live link to a trusted contact. You can switch it off any time.',
  },
  {
    icon: 'lock',
    title: 'Ride PIN',
    body: 'Only start the ride once your driver has confirmed the PIN you hold.',
  },
  {
    icon: 'support',
    title: 'Help when you need it',
    body: 'Report a problem in the app and our support team follows up. In an emergency, one tap calls 112.',
    wide: true,
  },
]

const steps = [
  { title: 'Choose where to go', body: 'Set your pickup and destination and see the fare before you book.' },
  { title: 'Meet your driver', body: 'Check her details, follow her on the map and share the ride PIN.' },
  { title: 'Arrive and rate', body: 'Pay at the end of the trip and tell us how it went.' },
]

function RideCardMock() {
  return (
    <div className="mock" aria-hidden="true">
      <RideScene className="mock-scene" />
      <div className="mock-card">
        <div className="mock-row">
          <span className="mock-avatar">A</span>
          <div>
            <strong>Your driver is on the way</strong>
            <span className="mock-sub">Verified · White hatchback</span>
          </div>
        </div>
        <div className="mock-row mock-pin">
          <span>Ride PIN</span>
          <strong>4 8 2 9</strong>
        </div>
        <div className="mock-chips">
          <span>Share trip</span>
          <span>Call 112</span>
        </div>
      </div>
    </div>
  )
}

export default function Home() {
  return (
    <>
      <section className="hero">
        <div className="container hero-grid">
          <div className="hero-copy">
            <h1>Safe, simple rides for women</h1>
            <p className="lead">
              Lady Go is a women-focused pick-and-drop service. Book a ride, see who is coming, and
              share your trip with someone you trust.
            </p>
            <div className="actions">
              <Link className="button" to="/how-it-works">
                How it works
              </Link>
              <Link className="button button-outline" to="/driver">
                Drive with us
              </Link>
            </div>
            <p className="note">The Lady Go apps are in development. Launch details will be announced here.</p>
          </div>
          <RideCardMock />
        </div>
      </section>

      <section className="trust" aria-label="Safety at a glance">
        <div className="container">
          <ul className="trust-list">
            {trust.map((t) => (
              <li key={t.text}>
                <Icon name={t.icon} size={22} />
                <span>{t.text}</span>
              </li>
            ))}
          </ul>
        </div>
      </section>

      <section className="section">
        <div className="container">
          <div className="section-head">
            <h2>Built around how you travel</h2>
            <p className="lead">Every ride has the details you need to feel sure, and a way to get help.</p>
          </div>
          <div className="bento">
            {features.map((f) => (
              <article key={f.title} className={f.wide ? 'tile tile-wide' : 'tile'}>
                <span className="tile-icon">
                  <Icon name={f.icon} size={26} />
                </span>
                <h3>{f.title}</h3>
                <p>{f.body}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="section section-tint">
        <div className="container">
          <div className="section-head">
            <h2>Three steps to your ride</h2>
          </div>
          <ol className="steps-row">
            {steps.map((s, i) => (
              <li key={s.title}>
                <span className="step-num" aria-hidden="true">{i + 1}</span>
                <h3>{s.title}</h3>
                <p>{s.body}</p>
              </li>
            ))}
          </ol>
        </div>
      </section>

      <section className="section" aria-label="Drive with Lady Go">
        <div className="container">
          <div className="panel panel-dark panel-split">
            <div>
              <p className="eyebrow">For women drivers</p>
              <h2>
                Earn <span className="earn-amount">₹30,000+</span> a month with us
              </h2>
              <p>Register yourself online in minutes, then drive on your own hours.</p>
            </div>
            <Link className="button button-light" to="/driver">
              Register as a driver
            </Link>
          </div>
        </div>
      </section>
    </>
  )
}
