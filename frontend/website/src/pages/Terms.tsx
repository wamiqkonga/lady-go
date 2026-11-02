import { Link } from 'react-router-dom'
import Page from '../components/Page'
import { SUPPORT_EMAIL } from '../config'

export default function Terms() {
  return (
    <Page title="Terms of service" icon="file" lead="Plain-language terms for riders and captains using Lady Go during our pilot.">
      <h2>1. What Lady Go is</h2>
      <p>
        Lady Go is a platform that connects riders with captains who offer rides on two-wheelers. Lady Go is not a
        transport operator and is not the employer of any captain. Lady Go does not own, inspect or guarantee any
        captain’s vehicle or documents.
      </p>

      <h2>2. The pilot</h2>
      <p>
        Lady Go is currently a pilot in Kashmir, for two-wheelers only. Rides are paid in cash directly to the captain;
        there is no online payment yet. The service, the areas it covers and these terms may change as the pilot
        develops. We will publish changes on this page.
      </p>

      <h2>3. Using the service</h2>
      <ul>
        <li>Give true and current information when you register, and keep your sign-in details to yourself.</li>
        <li>Treat other people with respect. Harassment, threats or discrimination are not accepted.</li>
        <li>Do not misuse the service, try to break into it, or use it for anything unlawful.</li>
      </ul>

      <h2>4. Captains</h2>
      <ul>
        <li>
          You are responsible for holding a valid driving licence, vehicle registration certificate (RC), insurance,
          fitness certificate and any other permit or document the law requires for your vehicle, and for following
          all traffic and transport laws.
        </li>
        <li>
          Before you can accept rides you accept the captain self-declaration, which says that everything you submit is
          your own, genuine and accurate, and that you are solely responsible for your vehicle, your documents and the
          way you ride.
        </li>
        <li>
          Our team reviews the documents you upload. A review is a check on what you send us. It is not a guarantee
          that your vehicle or documents meet the law; that stays your responsibility.
        </li>
        <li>
          A fake, tampered, expired or missing document, or a false declaration, can lead to permanent removal from
          Lady Go.
        </li>
      </ul>

      <h2>5. Suspension and removal</h2>
      <p>
        We may suspend or remove an account, for example when a document turns out to be fake or expired, when there
        is a safety concern or complaint we need to look into, or when these terms are broken. While an account is
        suspended, the captain cannot go online or accept rides. If you believe a suspension is a mistake, contact us
        at <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a>.
      </p>

      <h2>6. Safety</h2>
      <p>
        Lady Go cannot guarantee anyone’s physical safety. In an emergency, call 112 first. Where the SOS feature is
        available in the app, you can also use it to alert our team. Please report any concern to us so we can follow
        up.
      </p>

      <h2>7. Responsibility</h2>
      <p>
        Because Lady Go connects riders and captains and does not provide the ride itself, Lady Go and its owners are
        not responsible for a captain’s or rider’s unlawful acts, for fake or tampered documents, or for loss that
        results from them, to the extent the law allows. Nothing in these terms limits any right you have under law
        that cannot be limited.
      </p>

      <h2>8. Your information</h2>
      <p>
        How we collect and use your information is described in our <Link to="/privacy">Privacy policy</Link>.
      </p>

      <h2>9. Contact</h2>
      <p>
        Questions about these terms: <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a>.
      </p>
    </Page>
  )
}
