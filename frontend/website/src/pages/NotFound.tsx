import { Link } from 'react-router-dom'
import Page from '../components/Page'

export default function NotFound() {
  return (
    <Page title="Page not found" icon="pin" lead="The page you are looking for does not exist or has moved.">
      <div className="actions">
        <Link className="button" to="/">
          Go to the home page
        </Link>
        <Link className="button button-outline" to="/driver">
          Drive with us
        </Link>
      </div>
    </Page>
  )
}
