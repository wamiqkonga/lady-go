import { Link } from 'react-router-dom'
import Page from '../components/Page'
import { SUPPORT_EMAIL } from '../config'

type Group = { title: string; items: Array<{ q: string; a: string }> }

const groups: Group[] = [
  {
    title: 'About Lady Go',
    items: [
      {
        q: 'What is Lady Go?',
        a: 'Lady Go is a ride platform that connects women riders with captains who drive two-wheelers. We run a pilot in Kashmir while we learn what works well for riders and captains.',
      },
      {
        q: 'Where and when does it run?',
        a: 'For now, only in our Kashmir pilot, with a small group of riders and captains. We will announce new areas on this site when they are ready.',
      },
      {
        q: 'What vehicles are used?',
        a: 'Two-wheelers only for now.',
      },
      {
        q: 'How do I pay for a ride?',
        a: 'During the pilot, rides are paid in cash directly to your captain. There is no card or online payment yet.',
      },
    ],
  },
  {
    title: 'For riders',
    items: [
      {
        q: 'How do I book a ride?',
        a: 'Open the app, choose your pickup point and destination, check the fare shown, and confirm. We then look for a captain nearby. See How it works for the steps.',
      },
      {
        q: 'How do I know who is coming?',
        a: 'Before the ride you can see your captain’s details and follow them on the map. You share a ride PIN with the captain to start the trip.',
      },
    ],
  },
  {
    title: 'For captains',
    items: [
      {
        q: 'Is Lady Go my employer?',
        a: 'No. Lady Go is a platform that connects riders with captains. Captains are not employees of Lady Go. Lady Go does not own your vehicle and does not inspect your vehicle or your documents.',
      },
      {
        q: 'How do I register?',
        a: 'Register on this website from your phone. You enter your phone number and email, type the 6-digit code we email you, create a password, choose your city, then complete the steps, starting with your full name. Your progress is saved, so you can stop and come back.',
      },
      {
        q: 'What do I need to register?',
        a: 'Your full name as it appears on your driving licence or RC, your vehicle registration number and a clear photo of your vehicle registration certificate (RC), a selfie, and your acceptance of the captain self-declaration. A driving licence photo and a fitness certificate photo are optional at registration.',
      },
      {
        q: 'What happens after I finish all the steps?',
        a: 'Our team reviews your application and documents. You do not need to do anything else. Come back to the registration page any time to see the status of each document. If we cannot accept a document, we will tell you why there and you can send a new photo.',
      },
      {
        q: 'I forgot my password. What do I do?',
        a: 'On the captain sign-in page choose “Forgot password” and enter your phone number or email. If an account exists, we email you a link to choose a new password. You can also sign in with an emailed code instead.',
      },
      {
        q: 'I did not get my code.',
        a: 'Codes are sent by email and can take a minute. Check your spam folder, and make sure you use the same email you used the first time. Then ask for a new code. If it still does not arrive, write to us.',
      },
      {
        q: 'Can my account be suspended?',
        a: 'Yes. We may suspend or remove a captain, for example if a document turns out to be fake, expired or not yours, or if there is a safety concern. A suspended captain cannot go online or accept rides. If you think this was a mistake, contact support.',
      },
    ],
  },
  {
    title: 'Safety and privacy',
    items: [
      {
        q: 'What should I do in an emergency?',
        a: 'Call 112 (India’s emergency number) first. Lady Go cannot replace emergency services. Where the SOS feature is available in the app, you can use it to alert our team as well.',
      },
      {
        q: 'What information do you collect from captains?',
        a: 'Your phone number, email, city, vehicle number, the document photos you upload (such as your RC), a selfie, and a record that you accepted the self-declaration. We use it to check and review your application. See the Privacy policy for details.',
      },
    ],
  },
  {
    title: 'Getting help',
    items: [
      {
        q: 'How do I contact support?',
        a: `Email ${SUPPORT_EMAIL}. Please include your phone number and a short description of the problem. Do not send passwords or codes.`,
      },
    ],
  },
]

export default function Faq() {
  return (
    <Page title="Frequently asked questions" icon="support" lead="Quick answers about riding and driving with Lady Go.">
      {groups.map((g) => (
        <section key={g.title} aria-labelledby={`faq-${g.title.replace(/\W+/g, '-').toLowerCase()}`}>
          <h2 id={`faq-${g.title.replace(/\W+/g, '-').toLowerCase()}`}>{g.title}</h2>
          <div className="faq-list">
            {g.items.map((f) => (
              <details key={f.q}>
                <summary>{f.q}</summary>
                <p>{f.a}</p>
              </details>
            ))}
          </div>
        </section>
      ))}
      <p className="callout">
        Want to drive with us? See <Link to="/captain">how captain registration works</Link>. Still stuck?{' '}
        <Link to="/contact">Contact us</Link>.
      </p>
    </Page>
  )
}
