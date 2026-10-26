import { Link } from 'react-router-dom'
import Icon, { type IconName } from '../components/Icons'
import Page from '../components/Page'

const values: Array<{ icon: IconName; title: string; body: string }> = [
  { icon: 'shield', title: 'Safety designed in', body: 'Verified drivers, a ride PIN, live trip sharing and quick access to help are part of every ride, not add-ons.' },
  { icon: 'users', title: 'Women, for women', body: 'A service where riders and drivers are women, so both can travel and work with more peace of mind.' },
  { icon: 'heart', title: 'Care in the details', body: 'Clear fares, plain language and a support team that follows up when something goes wrong.' },
]

export default function About() {
  return (
    <Page
      title="About Lady Go"
      icon="heart"
      lead="Lady Go is being built to make everyday travel safer and more comfortable for women."
    >
      <p>
        We are creating a pick-and-drop service with safety designed in from the start: verified
        drivers, live trip tracking, a ride PIN and quick access to help.
      </p>
      <p>We are starting local and aim to grow city by city.</p>
      <div className="value-grid">
        {values.map((v) => (
          <article className="tile" key={v.title}>
            <span className="tile-icon">
              <Icon name={v.icon} size={26} />
            </span>
            <h3>{v.title}</h3>
            <p>{v.body}</p>
          </article>
        ))}
      </div>
      <div className="cta-pair">
        <Link className="cta-card" to="/how-it-works">
          <span className="tile-icon"><Icon name="route" size={24} /></span>
          <span><strong>Ride with us</strong><small>See how booking works</small></span>
          <Icon name="arrow" size={20} />
        </Link>
        <Link className="cta-card" to="/driver">
          <span className="tile-icon"><Icon name="car" size={24} /></span>
          <span><strong>Drive with us</strong><small>Register as a driver</small></span>
          <Icon name="arrow" size={20} />
        </Link>
      </div>
    </Page>
  )
}
