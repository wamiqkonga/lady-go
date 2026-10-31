import Icon, { type IconName } from '../components/Icons'
import Page from '../components/Page'

const steps: Array<{ icon: IconName; title: string; body: string }> = [
  { icon: 'pin', title: 'Choose your route', body: 'Open the app and choose your pickup point and destination.' },
  { icon: 'wallet', title: 'See the fare first', body: 'See the estimated distance, time and fare before you book.' },
  { icon: 'car', title: 'We find a captain', body: 'Confirm your ride and we find a captain nearby.' },
  { icon: 'id', title: 'Know who is coming', body: 'See your captain’s details and follow them on the map.' },
  { icon: 'lock', title: 'Share the PIN', body: 'Share your ride PIN with the captain to start the trip.' },
  { icon: 'star', title: 'Arrive and rate', body: 'Arrive, pay and rate your ride.' },
]

export default function HowItWorks() {
  return (
    <Page title="How it works" icon="route" lead="Booking a ride takes just a few steps.">
      <ol className="timeline">
        {steps.map((s, i) => (
          <li key={s.title}>
            <span className="step-num" aria-hidden="true">{i + 1}</span>
            <div className="step-body">
              <h3><Icon name={s.icon} size={20} /> {s.title}</h3>
              <p>{s.body}</p>
            </div>
          </li>
        ))}
      </ol>
    </Page>
  )
}
