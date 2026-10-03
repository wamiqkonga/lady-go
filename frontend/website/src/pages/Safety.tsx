import Page from '../components/Page'

export default function Safety() {
  return (
    <Page
      title="Safety"
      lead="Safety is a core part of Lady Go, not an extra."
    >
      <p>These are the safety features we are building into the service:</p>
      <ul>
        <li>Driver identity and vehicle details shown before every ride</li>
        <li>Driver document verification before a driver can go online</li>
        <li>Ride PIN confirmed at pickup</li>
        <li>Live trip tracking and trip sharing with a trusted contact</li>
        <li>Emergency (SOS) button that alerts our team and your emergency contact</li>
        <li>Reporting tools and follow-up by our support team</li>
      </ul>
      <p>
        No app can guarantee physical safety. In an emergency, always contact your local emergency
        services first.
      </p>
    </Page>
  )
}
