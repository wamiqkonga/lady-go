import { Link } from 'react-router-dom'
import Icon from '../components/Icons'
import Page from '../components/Page'
import { SUPPORT_EMAIL } from '../config'

export default function Contact() {
  return (
    <Page title="Contact us" icon="mail" lead="Questions, feedback or a problem with your account? We would like to hear from you.">
      <div className="tile contact-card">
        <span className="tile-icon"><Icon name="support" size={26} /></span>
        <h3>Email support</h3>
        <p>
          <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a>
        </p>
        <p>
          Please include your phone number and a short description of what happened. Never send your password or a
          sign-in code.
        </p>
      </div>
      <p className="callout">
        <strong>In an emergency, call 112 first.</strong> Support by email is not an emergency service.
      </p>
      <div className="cta-pair">
        <Link className="cta-card" to="/faq">
          <span className="tile-icon"><Icon name="file" size={24} /></span>
          <span><strong>Read the FAQ</strong><small>Answers to common questions</small></span>
          <Icon name="arrow" size={20} />
        </Link>
        <Link className="cta-card" to="/captain/registration">
          <span className="tile-icon"><Icon name="id" size={24} /></span>
          <span><strong>Check my application</strong><small>For captains who have registered</small></span>
          <Icon name="arrow" size={20} />
        </Link>
      </div>
    </Page>
  )
}
