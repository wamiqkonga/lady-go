import { Link } from 'react-router-dom'
import Page from '../components/Page'
import { SUPPORT_EMAIL } from '../config'

export default function Privacy() {
  return (
    <Page title="Privacy policy" icon="lock" lead="What Lady Go collects, why, and who can see it.">
      <h2>What we collect from captains when they register</h2>
      <ul>
        <li>Your phone number and email address (to create your account and send you sign-in codes and account emails).</li>
        <li>Your full name, as it appears on your driving licence or RC (so our team can match your documents to you).</li>
        <li>Your city and your vehicle registration number.</li>
        <li>
          Photos you upload: your vehicle registration certificate (RC), a selfie, and, if you choose to add them, your
          driving licence and fitness certificate, with the numbers and dates you enter for them.
        </li>
        <li>A record that you accepted the captain self-declaration, and when.</li>
        <li>Your password, stored only in scrambled (hashed) form. We cannot read it.</li>
      </ul>

      <h2>Why we use it</h2>
      <p>
        To create your account, to let our team check and review your application, to keep riders and captains safe,
        to contact you about your account, and to look into complaints and problems.
      </p>

      <h2>Who can see it</h2>
      <p>
        Lady Go team members who review applications and give support can see what you submit. Our email delivery
        service handles the emails we send you. We do not sell your information.
      </p>

      <h2>Photos and documents</h2>
      <p>
        Document photos and selfies are used to review your application. Please upload only your own documents, and
        only what we ask for.
      </p>

      <h2>Riders</h2>
      <p>
        To book a ride, we use your phone number and the pickup and destination locations you choose, and we show your
        captain’s position and your trip to you while the ride is happening.
      </p>

      <h2>Your choices</h2>
      <p>
        To ask a question about your information, to correct it, or to ask us to delete your account, write to{' '}
        <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a>.
      </p>

      <p className="callout">
        This policy may change as the pilot develops. See also our <Link to="/terms">Terms of service</Link>.
      </p>
    </Page>
  )
}
