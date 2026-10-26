import Icon, { type IconName } from '../components/Icons'
import Page from '../components/Page'

const features: Array<{ icon: IconName; text: string }> = [
  { icon: 'id', text: 'Driver identity and vehicle details shown before every ride' },
  { icon: 'shield', text: 'Driver document verification before a driver can go online' },
  { icon: 'lock', text: 'Ride PIN confirmed at pickup' },
  { icon: 'share', text: 'Live trip tracking and trip sharing with a trusted contact' },
  { icon: 'alert', text: 'Emergency (SOS) button that alerts our team and your emergency contact' },
  { icon: 'support', text: 'Reporting tools and follow-up by our support team' },
]

export default function Safety() {
  return (
    <Page title="Safety" icon="shield" lead="Safety is a core part of Lady Go, not an extra.">
      <p>These are the safety features we are building into the service:</p>
      <ul className="feature-list">
        {features.map((f) => (
          <li key={f.text}>
            <span className="tile-icon">
              <Icon name={f.icon} size={22} />
            </span>
            <span>{f.text}</span>
          </li>
        ))}
      </ul>
      <p className="callout">
        No app can guarantee physical safety. In an emergency, always contact your local emergency
        services first.
      </p>
    </Page>
  )
}
