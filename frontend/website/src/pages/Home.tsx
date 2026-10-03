import { Link } from 'react-router-dom'

export default function Home() {
  return (
    <>
      <section className="hero">
        <h1>Safe, simple rides for women</h1>
        <p className="lead">
          Lady Go is a women-focused pick-and-drop service. Book a ride, see who is coming, and share
          your trip with someone you trust.
        </p>
        <div className="actions">
          <Link className="button" to="/how-it-works">
            How it works
          </Link>
          <Link className="button secondary" to="/driver">
            Drive with us
          </Link>
        </div>
        <p className="note">The Lady Go apps are in development. Launch details will be announced here.</p>
      </section>
      <section className="cards" aria-label="Highlights">
        <div className="card">
          <h2>Know your driver</h2>
          <p>See your driver’s name, photo, vehicle and rating before the ride starts.</p>
        </div>
        <div className="card">
          <h2>Share your trip</h2>
          <p>Let a trusted contact follow your ride live.</p>
        </div>
        <div className="card">
          <h2>Help when you need it</h2>
          <p>An in-app emergency button alerts our team and your emergency contact.</p>
        </div>
      </section>
    </>
  )
}
