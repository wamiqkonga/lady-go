import { Link } from 'react-router-dom'
import Icon from '../components/Icons'
import Page from '../components/Page'

export default function Contact() {
  return (
    <Page title="Contact us" icon="mail" lead="Questions, feedback or partnership ideas? We would like to hear from you.">
      <div className="tile contact-card">
        <span className="tile-icon"><Icon name="support" size={26} /></span>
        <h3>We are setting up support</h3>
        <p>Contact details will be published here before launch.</p>
      </div>
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
