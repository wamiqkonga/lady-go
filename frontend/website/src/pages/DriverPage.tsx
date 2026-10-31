import { Link } from 'react-router-dom'
import Icon, { type IconName } from '../components/Icons'
import { DriverScene } from '../components/Illustrations'

const perks: Array<{ icon: IconName; title: string; body: string }> = [
  { icon: 'clock', title: 'Choose your own hours', body: 'Go online when it suits you. No shifts and no minimum hours.' },
  { icon: 'users', title: 'Women riders only', body: 'Drive a women-focused service built around rider and captain safety.' },
  { icon: 'wallet', title: 'Cash in hand', body: 'Riders pay cash today, and your earnings are clear on every trip.' },
  { icon: 'route', title: 'See your earnings', body: 'Your earnings, ride history and ratings are in the app, in one place.' },
  { icon: 'shield', title: 'Safety backup', body: 'Ride PIN, live trip sharing and a one-tap call to 112 come with every ride.' },
  { icon: 'support', title: 'Help when you need it', body: 'A support team and a help screen in the app, so you are never on your own.' },
]

const documentGroups: Array<{ icon: IconName; title: string; items: string[] }> = [
  {
    icon: 'id',
    title: 'Captain documents',
    items: ['Driving licence', 'A clear selfie', 'Self-declaration, accepted in the app'],
  },
  {
    icon: 'car',
    title: 'Vehicle documents',
    items: ['Registration certificate (RC)', 'Insurance', 'Fitness certificate', 'Pollution certificate'],
  },
  {
    icon: 'camera',
    title: 'Vehicle details',
    items: ['Registration number', 'Type, make, model and colour', 'Manufacture year', 'Photo with the number plate visible'],
  },
]

const steps = [
  { title: 'Register', body: 'Enter your phone number and email. We email you a six-digit code.' },
  { title: 'Add your vehicle', body: 'Tell us the registration number, type, make, model and colour.' },
  { title: 'Upload your documents', body: 'Photograph each document and add your selfie, right from your phone.' },
  { title: 'Accept the declaration', body: 'Read and accept the captain declaration.' },
  { title: 'Wait for the review', body: 'Our team checks everything. The result appears in the same place, with a note if we need a fix.' },
]

const tips = [
  'Use good light and a flat surface. All four corners and the text must be sharp.',
  'Photos must be JPEG or PNG, up to 8 MB each.',
  'Expired documents cannot be approved, so check the dates first.',
  'Use the same phone number and email each time you register.',
]

const faqs = [
  { q: 'Do I need to visit an office?', a: 'No. Registration is fully online, from your phone, with no agent involved.' },
  { q: 'How long does the review take?', a: 'Our team reviews each application. You can check your status any time by opening your registration.' },
  { q: 'What if a document is rejected?', a: 'You see a note on that document explaining why. Upload a new photo and it goes back into review.' },
  { q: 'Can I stop and continue later?', a: 'Yes. Your progress is saved, so come back and carry on where you left off.' },
  { q: 'Do I need my own vehicle?', a: 'Yes. You need a vehicle registered with valid insurance, fitness and pollution certificates.' },
]

export default function DriverPage() {
  return (
    <article className="page">
      <section className="section section-first">
        <div className="container">
          <div className="panel panel-dark hero-panel">
            <div className="hero-panel-copy">
              <p className="eyebrow">Drive with Lady Go</p>
              <h1>
                Earn <span className="earn-amount">₹30,000+</span> a month with us
              </h1>
              <p className="lead">
                Women captains wanted. Register yourself in a few minutes from your phone, no agent and no
                office visit.
              </p>
              <div className="actions">
                <Link className="button button-light" to="/captain/registration">
                  Register now
                </Link>
                <Link className="button button-ghost" to="/captain/registration">
                  Check my application
                </Link>
              </div>
              <p className="note">
                Earnings depend on city, hours driven and demand; ₹30,000+ is an indicative monthly figure for
                active captains, not a guarantee.
              </p>
            </div>
            <div className="hero-side">
              <DriverScene className="hero-scene" />
              <aside className="checklist-card" aria-label="What registration involves">
              <h2>Register in 5 steps</h2>
              <ul>
                {steps.map((s) => (
                  <li key={s.title}>
                    <Icon name="check" size={18} />
                    <span>{s.title}</span>
                  </li>
                ))}
              </ul>
              <Link className="button button-sm" to="/captain/registration">
                Start now
              </Link>
              </aside>
            </div>
          </div>
        </div>
      </section>

      <section className="section" aria-labelledby="why-title">
        <div className="container">
          <div className="section-head">
            <h2 id="why-title">Your hours. Your earnings. Our support.</h2>
            <p className="lead">Why women are choosing to drive with Lady Go.</p>
          </div>
          <div className="perk-grid">
            {perks.map((p) => (
              <article className="tile" key={p.title}>
                <span className="tile-icon">
                  <Icon name={p.icon} size={26} />
                </span>
                <h3>{p.title}</h3>
                <p>{p.body}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="section section-tint" aria-labelledby="docs-title">
        <div className="container">
          <div className="section-head">
            <h2 id="docs-title">Registration is super simple.</h2>
            <p className="lead">Keep these ready and you can finish in one sitting.</p>
          </div>
          <div className="doc-grid">
            {documentGroups.map((g) => (
              <article className="doc-card" key={g.title}>
                <span className="doc-icon">
                  <Icon name={g.icon} size={30} />
                </span>
                <h3>{g.title}</h3>
                <ul>
                  {g.items.map((item) => (
                    <li key={item}>
                      <Icon name="file" size={18} />
                      <span>{item}</span>
                    </li>
                  ))}
                </ul>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="section" aria-labelledby="how-title">
        <div className="container split">
          <div>
            <h2 id="how-title">How to register</h2>
            <p className="lead">Five short steps, and you can pause at any point.</p>
            <ol className="timeline">
              {steps.map((s, i) => (
                <li key={s.title}>
                  <span className="step-num" aria-hidden="true">{i + 1}</span>
                  <div>
                    <h3>{s.title}</h3>
                    <p>{s.body}</p>
                  </div>
                </li>
              ))}
            </ol>
          </div>
          <aside className="tips-card">
            <h2>Before you start</h2>
            <ul>
              {tips.map((t) => (
                <li key={t}>
                  <Icon name="check" size={18} />
                  <span>{t}</span>
                </li>
              ))}
            </ul>
            <p className="note">
              <Icon name="lock" size={16} /> Our team reviews your documents before you can go online.
            </p>
          </aside>
        </div>
      </section>

      <section className="section section-tint" aria-labelledby="faq-title">
        <div className="container narrow">
          <div className="section-head">
            <h2 id="faq-title">Captain questions</h2>
          </div>
          <div className="faq-list">
            {faqs.map((f) => (
              <details key={f.q}>
                <summary>{f.q}</summary>
                <p>{f.a}</p>
              </details>
            ))}
          </div>
        </div>
      </section>

      <section className="section">
        <div className="container">
          <div className="panel panel-dark panel-split">
            <div>
              <h2>Ready to start earning?</h2>
              <p>It takes a few minutes, and you can save and come back.</p>
            </div>
            <Link className="button button-light" to="/captain/registration">
              Start your registration
            </Link>
          </div>
        </div>
      </section>
    </article>
  )
}
