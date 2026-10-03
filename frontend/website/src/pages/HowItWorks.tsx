import Page from '../components/Page'

const steps = [
  'Open the app and choose your pickup point and destination.',
  'See the estimated distance, time and fare before you book.',
  'Confirm your ride and we find a driver nearby.',
  'See your driver’s details and follow them on the map.',
  'Share your ride PIN with the driver to start the trip.',
  'Arrive, pay and rate your ride.',
]

export default function HowItWorks() {
  return (
    <Page title="How it works" lead="Booking a ride takes just a few steps.">
      <ol className="steps">
        {steps.map((s) => (
          <li key={s}>{s}</li>
        ))}
      </ol>
    </Page>
  )
}
